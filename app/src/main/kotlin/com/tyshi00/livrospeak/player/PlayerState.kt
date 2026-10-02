package com.tyshi00.livrospeak.player

import org.json.JSONObject

enum class PlayerReadiness {
    Preparing,
    Buffering,
    Ready,
    Unavailable,
    Error,
}

data class PlayerState(
    val title: String = "Libby",
    val chapter: String? = null,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val isPlaying: Boolean = false,
    val playbackSpeed: Double = 1.0,
    val controlsFound: Boolean = false,
    val pageUrl: String = "",
    val diagnostic: String = "Waiting for player…",
    val readiness: PlayerReadiness = PlayerReadiness.Preparing,
) {
    companion object {
        fun fromJson(json: JSONObject): PlayerState = PlayerState(
            title = json.optString("title", "Libby"),
            chapter = json.optString("chapter").takeIf { it.isNotBlank() },
            positionSeconds = json.optDouble("positionSeconds", 0.0),
            durationSeconds = json.optDouble("durationSeconds", 0.0),
            isPlaying = json.optBoolean("isPlaying", false),
            playbackSpeed = json.optDouble("playbackSpeed", 1.0),
            controlsFound = json.optBoolean("controlsFound", false),
            pageUrl = json.optString("pageUrl", ""),
            diagnostic = json.optString("diagnostic", ""),
            readiness = if (json.optBoolean("controlsFound", false)) {
                PlayerReadiness.Ready
            } else {
                PlayerReadiness.Preparing
            },
        )
    }
}
