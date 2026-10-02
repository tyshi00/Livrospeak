package com.tyshi00.livrospeak.library

enum class AudiobookSource {
    Libby,
    Local,
    Rss,
}

data class Audiobook(
    val id: String,
    val source: AudiobookSource,
    val title: String,
    val author: String = "",
    val playbackReference: String,
    val durationMilliseconds: Long = 0,
    val positionMilliseconds: Long = 0,
    val playbackSpeed: Float = 1f,
    val completed: Boolean = false,
    val lastPlayedAtMilliseconds: Long = 0,
    val lastUpdatedAtMilliseconds: Long = 0,
    val progressPercentOverride: Int? = null,
    val dueText: String = "",
    val fileSizeBytes: Long = 0,
) {
    val progressPercent: Int
        get() = progressPercentOverride ?: if (durationMilliseconds > 0) {
            ((positionMilliseconds * 100) / durationMilliseconds).toInt().coerceIn(0, 100)
        } else {
            0
        }
}
