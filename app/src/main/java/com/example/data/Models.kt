package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class MusicTrack(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val path: String,
    val duration: Long,
    val isFavorite: Boolean = false,
    val dateAdded: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val trackIds: String, // Comma-separated track IDs
    val isSmart: Boolean = false,
    val smartQuery: String? = null
)

@Entity(tableName = "eq_presets")
data class EqPreset(
    @PrimaryKey val id: String = "current",
    val isEnabled: Boolean = true,
    val band1: Float = 0f, // in dB (-15 to +15)
    val band2: Float = 0f,
    val band3: Float = 0f,
    val band4: Float = 0f,
    val band5: Float = 0f
)

@Entity(tableName = "lyrics_cache")
data class LyricsCache(
    @PrimaryKey val trackId: String,
    val lyricsText: String
)
