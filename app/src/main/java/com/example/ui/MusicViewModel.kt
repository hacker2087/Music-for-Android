package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.service.MusicPlaybackService
import com.example.service.PlaybackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = MusicRepository(application, db.musicDao())
    private val sharedPrefs = application.getSharedPreferences("aura_settings", Context.MODE_PRIVATE)

    // DB States
    val tracks = repository.allTracks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val playlists = repository.playlists.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Player States (delegating to PlaybackManager)
    val currentTrack = PlaybackManager.currentTrack
    val isPlaying = PlaybackManager.isPlaying
    val currentProgress = PlaybackManager.currentProgress
    val duration = PlaybackManager.duration
    val currentPlaylist = PlaybackManager.playlist
    val eqPreset = PlaybackManager.eqPreset

    // AI states
    private val _lyricsText = MutableStateFlow<String?>(null)
    val lyricsText: StateFlow<String?> = _lyricsText

    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading

    private val _aiRecommendationResult = MutableStateFlow<String?>(null)
    val aiRecommendationResult: StateFlow<String?> = _aiRecommendationResult

    private val _aiLoading = MutableStateFlow(false)
    val aiLoading: StateFlow<Boolean> = _aiLoading

    // Config states
    private val _geminiApiKey = MutableStateFlow(sharedPrefs.getString("gemini_key", "") ?: "")
    val geminiApiKey: StateFlow<String> = _geminiApiKey

    private val _notificationOption = MutableStateFlow(sharedPrefs.getString("notif_option", "Reminders") ?: "Reminders")
    val notificationOption: StateFlow<String> = _notificationOption

    init {
        viewModelScope.launch {
            // First time launch: preload fallback high-fidelity tracks
            repository.allTracks.first().let { currentList ->
                if (currentList.isEmpty()) {
                    repository.insertDefaultFallbackTracks()
                }
            }

            // Load saved EQpreset
            val savedEq = repository.getSavedEq()
            PlaybackManager.eqPreset.value = savedEq
        }
    }

    // --- Media Controls ---

    fun playTrack(track: MusicTrack, list: List<MusicTrack>) {
        viewModelScope.launch {
            PlaybackManager.playlist.value = list
            val index = list.indexOf(track)
            val intent = Intent(getApplication(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_PLAY
                putExtra(MusicPlaybackService.EXTRA_TRACK_INDEX, index)
            }
            getApplication<Application>().startService(intent)
        }
    }

    fun playTrackAt(index: Int, list: List<MusicTrack>) {
        if (index < 0 || index >= list.size) return
        playTrack(list[index], list)
    }

    fun togglePlayPause() {
        val intent = Intent(getApplication(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PLAY_PAUSE
        }
        getApplication<Application>().startService(intent)
    }

    fun next() {
        val intent = Intent(getApplication(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_NEXT
        }
        getApplication<Application>().startService(intent)
    }

    fun previous() {
        val intent = Intent(getApplication(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_PREV
        }
        getApplication<Application>().startService(intent)
    }

    fun seekTo(position: Long) {
        val intent = Intent(getApplication(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_SEEK
            putExtra(MusicPlaybackService.EXTRA_SEEK_POS, position)
        }
        getApplication<Application>().startService(intent)
    }

    // --- Equalizer Controls ---

    fun updateEqPreset(preset: EqPreset) {
        PlaybackManager.eqPreset.value = preset
        viewModelScope.launch {
            repository.saveEq(preset)
            val intent = Intent(getApplication(), MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_UPDATE_EQ
            }
            getApplication<Application>().startService(intent)
        }
    }

    // --- Track Scanning ---

    fun scanLocalTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.scanLocalMusic()
        }
    }

    fun toggleFavorite(track: MusicTrack) {
        viewModelScope.launch {
            repository.toggleFavorite(track.id, !track.isFavorite)
            // Update current track if it is the toggled one
            if (currentTrack.value?.id == track.id) {
                currentTrack.value = currentTrack.value?.copy(isFavorite = !track.isFavorite)
                // Force playlist state to update
                PlaybackManager.playlist.value = PlaybackManager.playlist.value.map {
                    if (it.id == track.id) it.copy(isFavorite = !track.isFavorite) else it
                }
            }
        }
    }

    // --- Saved Playlists ---

    fun createPlaylist(name: String, tracksList: List<MusicTrack>) {
        viewModelScope.launch {
            val ids = tracksList.joinToString(",") { it.id }
            val newPlaylist = Playlist(name = name, trackIds = ids)
            repository.savePlaylist(newPlaylist)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
        }
    }

    // --- Gemini AI ---

    fun saveApiKey(key: String) {
        sharedPrefs.edit().putString("gemini_key", key).apply()
        _geminiApiKey.value = key
    }

    fun saveNotificationOption(option: String) {
        sharedPrefs.edit().putString("notif_option", option).apply()
        _notificationOption.value = option
    }

    fun generatePlayAlongLyrics(track: MusicTrack) {
        viewModelScope.launch {
            _lyricsLoading.value = true
            _lyricsText.value = "Generando letras de alta fidelidad desde la IA..."

            // Check db cache
            val cached = repository.getCachedLyrics(track.id)
            if (!cached.isNullOrBlank()) {
                _lyricsText.value = cached
                _lyricsLoading.value = false
                return@launch
            }

            val systemPrompt = "Eres un generador de letras musicales avanzado sincronizado en formato Podcast/Karaoke. Tu formato debe ser visualmente limpio, elegante, separado por líneas que representan fragmentos musicales cortos acompañados de marcas de tiempo poéticas o secciones como [Estrofa 1], [Estribillo]. Idioma: Español/Inglés según la pista."
            val prompt = "Genera la letra completa y detallada para cantar de la canción '${track.title}' del artista/álbum '${track.artist}'. Adorna la letra con marcas de ritmo minimalistas estilo podcast para que los usuarios puedan cantarla guiándose con el texto de forma fluida."

            val result = GeminiClient.queryGemini(prompt, geminiApiKey.value, systemPrompt)
            repository.cacheLyrics(track.id, result)
            _lyricsText.value = result
            _lyricsLoading.value = false
        }
    }

    fun clearLyrics() {
        _lyricsText.value = null
    }

    fun searchAiRecommendation(query: String) {
        viewModelScope.launch {
            _aiLoading.value = true
            _aiRecommendationResult.value = "Analizando biblioteca musical..."

            val trackListSummary = tracks.value.joinToString("\n") { "- ID: ${it.id} | Título: ${it.title} | Artista: ${it.artist} | Álbum: ${it.album}" }

            val systemPrompt = "Eres Aura-AI, un analizador y curador musical inteligente de alta fidelidad. Tu respuesta debe ser concisa, sumamente elegante, con un tono profesional y directo. Generas sugerencias de listas basadas en el estado de ánimo o vibe solicitado utilizando estrictamente la biblioteca del usuario."
            val prompt = """
                El usuario busca música con la siguiente vibra o instrucción: "$query".
                
                Nuestra biblioteca local disponible contiene las siguientes canciones:
                $trackListSummary
                
                Analiza estas canciones e identifica cuáles encajan de manera perfecta y profesional con lo solicitado.
                
                Escribe una respuesta minimalista y elegante que contenga:
                1. Una breve reseña de alta fidelidad que explique por qué encajan con la vibra "$query".
                2. IDs o títulos de las canciones que el usuario debería reproducir en una playlist recomendada.
                3. Un consejo técnico/acústico de ecualizador personalizado para maximizar la acústica de esta lista (por ejemplo, realzar bajos, atenuar agudos).
            """.trimIndent()

            val result = GeminiClient.queryGemini(prompt, geminiApiKey.value, systemPrompt)
            _aiRecommendationResult.value = result
            _aiLoading.value = false
        }
    }

    // --- Ringtone Configuration ---

    fun setAsRingtone(context: Context, track: MusicTrack): String {
        // Checking custom file paths. HTTP streaming fallbacks can't be set as ringtone directly
        if (track.path.startsWith("http")) {
            return "No se puede establecer una pista de transmisión de red como tono de llamada. Por favor descarga la canción localmente."
        }

        val file = File(track.path)
        if (!file.exists()) {
            return "El archivo de música local no se encuentra en el disco."
        }

        // Android 6+ requires WRITE_SETTINGS permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return "Aura requiere el permiso de ajustes de sistema para cambiar tu tono. Se ha abierto la pantalla de permisos. Confiérelo y vuelve a intentarlo."
            }
        }

        return try {
            val contentUri = Uri.fromFile(file)
            RingtoneManager.setActualDefaultRingtoneUri(
                context,
                RingtoneManager.TYPE_RINGTONE,
                contentUri
            )
            "¡Tono de llamada actualizado con éxito a: ${track.title}!"
        } catch (e: Exception) {
            e.printStackTrace()
            "Error al intentar cambiar el tono de llamada: ${e.localizedMessage}"
        }
    }
}
