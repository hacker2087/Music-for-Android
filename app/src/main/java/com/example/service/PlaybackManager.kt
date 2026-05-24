package com.example.service

import com.example.data.MusicTrack
import com.example.data.EqPreset
import kotlinx.coroutines.flow.MutableStateFlow

object PlaybackManager {
    val currentTrack = MutableStateFlow<MusicTrack?>(null)
    val isPlaying = MutableStateFlow(false)
    val currentProgress = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val playlist = MutableStateFlow<List<MusicTrack>>(emptyList())
    var currentIndex = -1

    // Store custom EQ configuration dynamically
    val eqPreset = MutableStateFlow<EqPreset>(EqPreset())
}
