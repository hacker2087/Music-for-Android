package com.example.data

import android.content.ContentResolver
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.flow.Flow
import java.io.File

class MusicRepository(private val context: Context, private val musicDao: MusicDao) {

    val allTracks: Flow<List<MusicTrack>> = musicDao.getAllTracks()
    val playlists: Flow<List<Playlist>> = musicDao.getAllPlaylists()

    suspend fun insertDefaultFallbackTracks() {
        val fallbackList = listOf(
            MusicTrack(
                id = "ambient_drift",
                title = "Ambient Drift",
                artist = "Soma FM Loop",
                album = "High Fidelity Dreams",
                path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                duration = 372000L,
                isFavorite = true
            ),
            MusicTrack(
                id = "midnight_velvet",
                title = "Midnight Velvet",
                artist = "Retro Wave",
                album = "Neon Noir",
                path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                duration = 423000L,
                isFavorite = false
            ),
            MusicTrack(
                id = "sleek_monologue",
                title = "Sleek Monologue",
                artist = "Aura Beats",
                album = "Minimal Pulse",
                path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3",
                duration = 302000L,
                isFavorite = false
            ),
            MusicTrack(
                id = "cyber_acoustic",
                title = "Cyber Acoustic",
                artist = "Lofi Core",
                album = "Futurism",
                path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3",
                duration = 318000L,
                isFavorite = false
            )
        )
        musicDao.insertTracks(fallbackList)
    }

    suspend fun scanLocalMusic() {
        val resolver: ContentResolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )

        val scannedList = mutableListOf<MusicTrack>()

        try {
            val cursor = resolver.query(uri, projection, selection, null, null)
            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val pathCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val durationCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (it.moveToNext()) {
                    val path = it.getString(pathCol)
                    if (!path.isNullOrBlank()) {
                        // Ensure it exists on disk
                        val file = File(path)
                        if (file.exists()) {
                            scannedList.add(
                                MusicTrack(
                                    id = it.getString(idCol),
                                    title = it.getString(titleCol) ?: "Desconocido",
                                    artist = it.getString(artistCol) ?: "Artista Desconocido",
                                    album = it.getString(albumCol) ?: "Álbum Desconocido",
                                    path = path,
                                    duration = it.getLong(durationCol)
                                )
                            )
                        }
                    }
                }
            }

            if (scannedList.isNotEmpty()) {
                musicDao.insertTracks(scannedList)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun toggleFavorite(trackId: String, isFavorite: Boolean) {
        musicDao.updateFavorite(trackId, isFavorite)
    }

    suspend fun savePlaylist(playlist: Playlist) {
        musicDao.insertPlaylist(playlist)
    }

    suspend fun deletePlaylist(playlistId: Long) {
        musicDao.deletePlaylist(playlistId)
    }

    suspend fun getSavedEq(): EqPreset {
        return musicDao.getEqPreset() ?: EqPreset()
    }

    suspend fun saveEq(preset: EqPreset) {
        musicDao.saveEqPreset(preset)
    }

    suspend fun getCachedLyrics(trackId: String): String? {
        return musicDao.getLyricsCache(trackId)?.lyricsText
    }

    suspend fun cacheLyrics(trackId: String, lyricsText: String) {
        musicDao.saveLyrics(LyricsCache(trackId, lyricsText))
    }
}
