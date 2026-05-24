package com.example

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.EqPreset
import com.example.data.MusicTrack
import com.example.ui.MusicViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }

        // Setup notification triggers
        startPeriodicNotificationTicker()
    }

    private fun startPeriodicNotificationTicker() {
        val sharedPrefs = getSharedPreferences("aura_settings", Context.MODE_PRIVATE)
        // Background notification loop - check every 5 minutes and post random reminder if enabled
        val schedulerJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            while (true) {
                val notifOption = sharedPrefs.getString("notif_option", "Reminders") ?: "Reminders"
                if (notifOption != "Ninguno") {
                    showReminderNotification(this@MainActivity)
                }
                delay(300000L) // Scan and trigger every 5 minutes
            }
        }
    }
}

// Global reminder notification sender
fun showReminderNotification(context: Context) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "aura_reminders_channel"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            channelId,
            "Aura Recordatorios",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Mensajes interactivos inspiradores de Aura"
        }
        notificationManager.createNotificationChannel(channel)
    }

    val messages = listOf(
        "¿Ya no quieres escuchar música conmigo? 🖤 Ven y sintoniza Aura.",
        "Oye, tu biblioteca musical se siente sola. Escuchemos algo.",
        "Equilibra tus sentidos. Abre el ecualizador y siente la alta fidelidad.",
        "Tu música favorita te espera. Entra a Aura y canta con letras IA.",
        "Ajustemos la vibra acústica hoy. ¿Abrimos Aura?"
    )
    val randomMessage = messages.random()

    val intent = Intent(context, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
        context,
        System.currentTimeMillis().toInt(),
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle("Aura • Alta Fidelidad")
        .setContentText(randomMessage)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    notificationManager.notify(99, notification)
}

@Composable
fun MainScreen(viewModel: MusicViewModel = viewModel()) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Permissions logic
    var isNotificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var isStorageGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isNotificationGranted = granted
    }

    val storageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isStorageGranted = granted
        if (granted) {
            viewModel.scanLocalTracks()
            Toast.makeText(context, "Escaneando biblioteca local...", Toast.LENGTH_SHORT).show()
        }
    }

    val writeSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Handled system permission callback is standard
    }

    // Request permissions dynamically on initial startup
    LaunchedEffect(Unit) {
        if (!isNotificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!isStorageGranted) {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            storageLauncher.launch(perm)
        } else {
            viewModel.scanLocalTracks()
        }
    }

    // Navigation and screen state
    var selectedTab by remember { mutableStateOf(0) } // 0: Library, 1: Player & EQ, 2: Aura-AI, 3: Settings
    val activeTrack by viewModel.currentTrack.collectAsState()
    val listSongs by viewModel.tracks.collectAsState()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        bottomBar = {
            Column {
                // Mini Player (Only visible if a song is loaded and we are not on the Player tab itself)
                if (activeTrack != null && selectedTab != 1) {
                    MiniPlayerBar(
                        track = activeTrack!!,
                        isPlayingState = viewModel.isPlaying.collectAsState().value,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onNext = { viewModel.next() },
                        onClick = { selectedTab = 1 }
                    )
                }

                XBottomNavBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> LibraryScreen(
                    tracks = listSongs,
                    activeTrack = activeTrack,
                    onTrackSelected = { track -> viewModel.playTrack(track, listSongs) },
                    onToggleFavorite = { track -> viewModel.toggleFavorite(track) },
                    onSetRingtone = { track ->
                        val res = viewModel.setAsRingtone(context, track)
                        Toast.makeText(context, res, Toast.LENGTH_LONG).show()
                    },
                    onRequestScan = {
                        if (isStorageGranted) {
                            viewModel.scanLocalTracks()
                            Toast.makeText(context, "Sincronizando...", Toast.LENGTH_SHORT).show()
                        } else {
                            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Manifest.permission.READ_MEDIA_AUDIO
                            } else {
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            }
                            storageLauncher.launch(perm)
                        }
                    }
                )
                1 -> PlayerAndEqualizerScreen(
                    viewModel = viewModel,
                    track = activeTrack,
                    onSetRingtone = { track ->
                        val res = viewModel.setAsRingtone(context, track)
                        Toast.makeText(context, res, Toast.LENGTH_LONG).show()
                    }
                )
                2 -> GeminiScreen(
                    viewModel = viewModel,
                    activeTrack = activeTrack
                )
                3 -> SettingsScreen(
                    apiKey = viewModel.geminiApiKey.collectAsState().value,
                    onSaveApiKey = { viewModel.saveApiKey(it) },
                    notificationChoice = viewModel.notificationOption.collectAsState().value,
                    onNotificationChoiceChanged = { viewModel.saveNotificationOption(it) }
                )
            }
        }
    }
}

// --- SHARED MODERN NAV BAR ---
@Composable
fun XBottomNavBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val items = listOf("BIBLIOTECA", "SONAR", "AURA-AI", "SINTONÍA")
    val outlineColor = MaterialTheme.colorScheme.outline

    Column {
        HorizontalDivider(color = outlineColor, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 12.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, label ->
                val isSelected = selectedTab == index
                val textColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                val fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, radius = 48.dp)
                        ) { onTabSelected(index) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = label,
                            color = textColor,
                            fontWeight = fontWeight,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        AnimatedVisibility(
                            visible = isSelected,
                            enter = fadeIn(animationSpec = twinSpring()) + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 20.dp, height = 3.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- MINI PLAYER FLOATING BAR ---
@Composable
fun MiniPlayerBar(
    track: MusicTrack,
    isPlayingState: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit
) {
    val outlineColor = MaterialTheme.colorScheme.outline

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, outlineColor, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Disk/Wave simple visualizer
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.Black)
                .border(1.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("🎚️", fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(36.dp).testTag("mini_play_button")
            ) {
                Text(
                    text = if (isPlayingState) "❚❚" else "▶",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onNext,
                modifier = Modifier.size(36.dp).testTag("mini_next_button")
            ) {
                Text(
                    text = "»",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// --- TAB 0: LIBRARY SCREEN ---
@Composable
fun LibraryScreen(
    tracks: List<MusicTrack>,
    activeTrack: MusicTrack?,
    onTrackSelected: (MusicTrack) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onSetRingtone: (MusicTrack) -> Unit,
    onRequestScan: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "AURA",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "SONIDO PURO Y MINIMALISTA",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.secondary,
                    letterSpacing = 2.sp
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                    .clickable(onClick = onRequestScan)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "SINCRONIZAR",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Tracks list
        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("☆", fontSize = 36.sp, color = MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Tu biblioteca está vacía.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Toca SINCRONIZAR para buscar audios locales.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "PISTAS DISPONIBLES (${tracks.size})",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(tracks) { item ->
                    val isActive = item.id == activeTrack?.id
                    TrackItemRow(
                        track = item,
                        isActive = isActive,
                        onClick = { onTrackSelected(item) },
                        onToggleFavorite = { onToggleFavorite(item) },
                        onSetRingtone = { onSetRingtone(item) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackItemRow(
    track: MusicTrack,
    isActive: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSetRingtone: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val outlineColor = MaterialTheme.colorScheme.outline
    val textColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
            .background(
                if (isActive) MaterialTheme.colorScheme.surface else Color.Transparent,
                RoundedCornerShape(16.dp)
            )
            .border(
                1.dp,
                if (isActive) MaterialTheme.colorScheme.primary else outlineColor,
                RoundedCornerShape(16.dp)
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // High fidelity indicator
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(
                    1.dp,
                    if (isActive) Color.Transparent else MaterialTheme.colorScheme.outline,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (track.path.startsWith("http")) "Hz" else "Hi",// format high-fidelity
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                fontSize = 12.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Actions: Favorite and Options
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(32.dp)
            ) {
                Text(
                    text = if (track.isFavorite) "★" else "☆",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("⠇", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Fijar como Tono",
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            showMenu = false
                            onSetRingtone()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Detalles acústicos",
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            showMenu = false
                            // Details simulated custom alert
                        }
                    )
                }
            }
        }
    }
}

// --- TAB 1: PLAYER & EQUALIZER SCREEN ---
@Composable
fun PlayerAndEqualizerScreen(
    viewModel: MusicViewModel,
    track: MusicTrack?,
    onSetRingtone: (MusicTrack) -> Unit
) {
    if (track == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔊", fontSize = 32.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "No hay pistas en reproducción.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Selecciona una canción en Biblioteca.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        return
    }

    val isPlayingState by viewModel.isPlaying.collectAsState()
    val progressState by viewModel.currentProgress.collectAsState()
    val durationState by viewModel.duration.collectAsState()
    val eqPresetState by viewModel.eqPreset.collectAsState()

    var showEqualizerPanel by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Switch between Player and Equalizer Panel with custom layout transition
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onSetRingtone(track) },
                modifier = Modifier.size(40.dp)
            ) {
                Text("🔔", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            }

            Text(
                text = "REPRODUCIENDO",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )

            IconButton(
                onClick = { showEqualizerPanel = !showEqualizerPanel },
                modifier = Modifier.size(40.dp)
            ) {
                Text(
                    text = if (showEqualizerPanel) "🎵" else "🎚️",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (showEqualizerPanel) {
            // HIGH FIDELITY EQUALIZER UI
            Text(
                text = "ECUALIZADOR HW",
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 2.sp
            )
            Text(
                text = "Optimizador acústico integrado de baja latencia",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Multiband EQ sliders (HW control is normalized)
            EqBandSlider("60 Hz", eqPresetState.band1) {
                viewModel.updateEqPreset(eqPresetState.copy(band1 = it))
            }
            EqBandSlider("230 Hz", eqPresetState.band2) {
                viewModel.updateEqPreset(eqPresetState.copy(band2 = it))
            }
            EqBandSlider("910 Hz", eqPresetState.band3) {
                viewModel.updateEqPreset(eqPresetState.copy(band3 = it))
            }
            EqBandSlider("4 kHz", eqPresetState.band4) {
                viewModel.updateEqPreset(eqPresetState.copy(band4 = it))
            }
            EqBandSlider("14 kHz", eqPresetState.band5) {
                viewModel.updateEqPreset(eqPresetState.copy(band5 = it))
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Presets row quick-selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Button(
                    onClick = { viewModel.updateEqPreset(EqPreset(band1 = 6f, band2 = 4f, band3 = 0f, band4 = -2f, band5 = -4f)) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Reforzar Graves", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                }
                Button(
                    onClick = { viewModel.updateEqPreset(EqPreset()) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Plano", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                }
                Button(
                    onClick = { viewModel.updateEqPreset(EqPreset(band1 = -4f, band2 = -2f, band3 = 1f, band4 = 4f, band5 = 6f)) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Focal Agudos", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                }
            }

        } else {
            // MAIN PLAYER CORE UI
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.Black)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Audio Canvas dynamic wave representation
                CustomAcousticWaveIndicator(isPlaying = isPlayingState)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = track.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = track.artist.uppercase(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Seek slider
            val normalizedProgress = if (durationState > 0) progressState.toFloat() / durationState else 0f
            Slider(
                value = normalizedProgress,
                onValueChange = { percent ->
                    val dest = (percent * durationState).toLong()
                    viewModel.seekTo(dest)
                },
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outline,
                    thumbColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.padding(horizontal = 16.dp).testTag("seek_slider")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMillisecondTime(progressState),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = formatMillisecondTime(durationState),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Playback buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.previous() },
                    modifier = Modifier.size(56.dp).testTag("prev_button")
                ) {
                    Text("«", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(36.dp))

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { viewModel.togglePlayPause() }
                        .testTag("play_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isPlayingState) "❚❚" else "▶",
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(36.dp))

                IconButton(
                    onClick = { viewModel.next() },
                    modifier = Modifier.size(56.dp).testTag("next_button")
                ) {
                    Text("»", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun EqBandSlider(label: String, currentValue: Float, onValueChange: (Float) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(72.dp)
        )

        Slider(
            value = currentValue,
            onValueChange = onValueChange,
            valueRange = -15f..15f,
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
                thumbColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = "${currentValue.toInt()} dB",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(48.dp)
        )
    }
}

// Custom rotating / wave-drawn Canvas illustration
@Composable
fun CustomAcousticWaveIndicator(isPlaying: Boolean) {
    val infiniteTransition = rememberInfiniteTransition()
    val waveScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Canvas(modifier = Modifier.size(140.dp)) {
        val radius = size.minDimension / 2
        val count = 28
        val baseRadius = radius * if (isPlaying) waveScale else 0.9f

        // Draw an ultra elegant high-contrast concentric audio signal network
        drawCircle(
            color = Color.White.copy(alpha = 0.05f),
            radius = baseRadius * 1.3f,
            style = Stroke(width = 2f)
        )

        drawCircle(
            color = Color.White.copy(alpha = 0.1f),
            radius = baseRadius * 0.9f,
            style = Stroke(width = 1.5f)
        )

        drawCircle(
            color = Color.White.copy(alpha = 0.2f),
            radius = baseRadius * 0.5f,
            style = Stroke(width = 1f)
        )

        // Wave spikes representation
        for (i in 0 until count) {
            val angle = (360f / count) * i
            val rad = Math.toRadians(angle.toDouble())
            val spikeLength = if (isPlaying) (15..45).random().toFloat() else 10f

            val startX = (center.x + baseRadius * Math.cos(rad)).toFloat()
            val startY = (center.y + baseRadius * Math.sin(rad)).toFloat()

            val endX = (center.x + (baseRadius + spikeLength) * Math.cos(rad)).toFloat()
            val endY = (center.y + (baseRadius + spikeLength) * Math.sin(rad)).toFloat()

            drawLine(
                color = Color.White,
                start = androidx.compose.ui.geometry.Offset(startX, startY),
                end = androidx.compose.ui.geometry.Offset(endX, endY),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }
    }
}

// --- TAB 2: GEMINI SCREEN (AI GEN LYRICS OR MUSIC CONTEXT SEARCH) ---
@Composable
fun GeminiScreen(
    viewModel: MusicViewModel,
    activeTrack: MusicTrack?
) {
    val lyrics by viewModel.lyricsText.collectAsState()
    val lyricsLoading by viewModel.lyricsLoading.collectAsState()

    val aiResult by viewModel.aiRecommendationResult.collectAsState()
    val aiLoading by viewModel.aiLoading.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var screenTab by remember { mutableStateOf(0) } // 0: Lyrics, 1: Mood search
    val outlineColor = MaterialTheme.colorScheme.outline

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Mode Header Selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .border(1.dp, outlineColor, RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (screenTab == 0) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { screenTab = 0 }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "CANTAR (LETRAS IA)",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (screenTab == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (screenTab == 1) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { screenTab = 1 }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "BÚSQUEDA AI",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (screenTab == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (screenTab == 0) {
            // KARAOKE / LYRICS VIEW
            if (activeTrack == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Para cantar, por favor reproduce primero una pista.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LETRAS: ${activeTrack.title.uppercase()}",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Generado dinámicamente y guardado localmente",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (lyrics == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Button(
                                onClick = { viewModel.generatePlayAlongLyrics(activeTrack) },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Generar Letra con Gemini AI", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .border(1.dp, outlineColor, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            if (lyricsLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = lyrics!!,
                                        fontSize = 14.sp,
                                        lineHeight = 22.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { viewModel.generatePlayAlongLyrics(activeTrack) },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, outlineColor),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Re-generar Letras", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

        } else {
            // ADVANCED AI SEARCH
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "BÚSQUEDA AVANZADA CON GUSTO ACÚSTICO",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Gemini analiza tu biblioteca local y calibra la sintonía perfecta.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("¿Qué vibra deseas escuchar hoy?", fontSize = 12.sp) },
                    placeholder = { Text("ej. Algo melancólico pero moderno", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = outlineColor
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (searchQuery.isNotBlank()) {
                            viewModel.searchAiRecommendation(searchQuery)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Recomendar con Gemini AI", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, outlineColor, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    if (aiLoading) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                aiResult ?: "Analizando pistas...",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (aiResult == null) {
                        Text(
                            text = "Escribe tu mood de música arriba para que Gemini busque en tu biblioteca. (Requiere API Key configurada)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = aiResult!!,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                fontFamily = FontFamily.Serif,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- TAB 3: SETTINGS SCREEN ---
@Composable
fun SettingsScreen(
    apiKey: String,
    onSaveApiKey: (String) -> Unit,
    notificationChoice: String,
    onNotificationChoiceChanged: (String) -> Unit
) {
    var keyInput by remember { mutableStateOf(apiKey) }
    val outlineColor = MaterialTheme.colorScheme.outline

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "SINTONÍA & AJUSTES",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Calibración del ecosistema y llaves de inteligencia",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(28.dp))

        // AI Settings Section
        Text(
            text = "GOOGLE GEMINI GOOGLE AI STUDIO",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("Llave API de Gemini", fontSize = 12.sp) },
            placeholder = { Text("Pega tu AI Studio API Key", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = outlineColor
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                onSaveApiKey(keyInput)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Guardar Llave Localmente", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Security Warning Banner (Security panel in AI Studio mandatory compliance)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(1.dp, outlineColor, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column {
                Text(
                    text = "Aviso de Seguridad de Claves API",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Aura valora tu privacidad absoluta. Tu clave API de Gemini de Google AI Studio se almacena cifrada localmente mediante las preferencias compartidas de tu dispositivo Android y realiza consultas HTTPS directas y seguras sin pasar por servidores intermediarios.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Notifications Settings Section
        Text(
            text = "NOTIFICACIONES INTERACTIVAS",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))

        val choices = listOf("Reminders", "Ninguno")
        choices.forEach { label ->
            val isSelected = notificationChoice == label
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNotificationChoiceChanged(label) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { onNotificationChoiceChanged(label) },
                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (label == "Reminders") "Alertas de re-conexión musical frecuentes" else "Apagar notificaciones periódicas",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Footnote
        Text(
            text = "Versión 1.0.0 • Optimizado para Gama ultra-baja y rendimiento de alta fidelidad.",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
    }
}

// --- UTILITY STUFF / HELPER METHODS ---

fun twinSpring(): SpringSpec<Float> {
    return spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )
}

fun formatMillisecondTime(ms: Long): String {
    val sec = (ms / 1000) % 60
    val min = (ms / (1000 * 60)) % 60
    return String.format("%02d:%02d", min, sec)
}
