package com.tyshi00.livrospeak.libby

sealed interface LibbyConnectState {
    data object Idle : LibbyConnectState
    data object Loading : LibbyConnectState
    data class Code(
        val digits: String,
        val secondsRemaining: Int,
    ) : LibbyConnectState
    data object Expired : LibbyConnectState
    data object Connected : LibbyConnectState
    data class Error(val userMessage: String) : LibbyConnectState
}

enum class LibbySessionState {
    Checking,
    Disconnected,
    Connected,
}
