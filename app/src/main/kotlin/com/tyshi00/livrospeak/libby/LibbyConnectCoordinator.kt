package com.tyshi00.livrospeak.libby

import android.util.Log
import com.tyshi00.livrospeak.LibbyWebPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

private const val TAG = "LibbyConnect"
private const val SETUP_ROUTE = "interview/authenticate/recover"
private const val SETUP_TIMEOUT_MILLISECONDS = 15_000L

/**
 * Owns Livrospeak's native Libby connection flow.
 *
 * Stable Libby 22.0.2 hooks:
 * - SPA route `interview/authenticate/recover`
 * - exact semantic action label `Display Setup Code`
 * - `.chip-code-control-field` for the eight generated digits
 * - `.chip-code-control-status` for Libby's authoritative countdown
 * - `.chip-code-control-field.fulfilled` / `Success!` for transfer completion
 */
object LibbyConnectCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableConnectState = MutableStateFlow<LibbyConnectState>(LibbyConnectState.Idle)
    private val mutableSessionState = MutableStateFlow(LibbySessionState.Checking)
    private var pollingJob: Job? = null
    private var sessionCheckJob: Job? = null
    private var shelfRequestedAfterFulfillment = false
    private var activeCodeDigits: String? = null
    private var codeExpiresAt: Long? = null

    val connectState: StateFlow<LibbyConnectState> = mutableConnectState.asStateFlow()
    val sessionState: StateFlow<LibbySessionState> = mutableSessionState.asStateFlow()

    fun refreshSessionState() {
        sessionCheckJob?.cancel()
        mutableSessionState.value = LibbySessionState.Checking
        sessionCheckJob = scope.launch {
            repeat(12) {
                if (LibbyWebPlayer.checkIsLoggedInSuspend()) {
                    mutableSessionState.value = LibbySessionState.Connected
                    return@launch
                }
                delay(1_000)
            }
            mutableSessionState.value = LibbySessionState.Disconnected
        }
    }

    fun startConnection() {
        pollingJob?.cancel()
        sessionCheckJob?.cancel()
        shelfRequestedAfterFulfillment = false
        activeCodeDigits = null
        codeExpiresAt = null
        transition(LibbyConnectState.Loading, "setup page opened")
        LibbyWebPlayer.loadLoginPage()
        pollingJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                if (checkForConnectedSession()) return@launch
                inspectSetupPage { result -> handleInspection(result) }

                val expiry = codeExpiresAt
                if (expiry != null && System.currentTimeMillis() >= expiry) {
                    transition(LibbyConnectState.Expired, "code expired")
                    return@launch
                }

                if (
                    mutableConnectState.value is LibbyConnectState.Loading &&
                    System.currentTimeMillis() - startedAt >= SETUP_TIMEOUT_MILLISECONDS
                ) {
                    failToStart()
                    return@launch
                }
                if (mutableConnectState.value is LibbyConnectState.Expired) return@launch
                delay(1_000)
            }
        }
    }

    fun requestNewCode() = startConnection()

    fun cancelConnection() {
        pollingJob?.cancel()
        sessionCheckJob?.cancel()
        pollingJob = null
        shelfRequestedAfterFulfillment = false
        activeCodeDigits = null
        codeExpiresAt = null
        mutableConnectState.value = LibbyConnectState.Idle
        LibbyWebPlayer.loadLoginPage()
    }

    fun disconnect() {
        pollingJob?.cancel()
        sessionCheckJob?.cancel()
        pollingJob = null
        mutableSessionState.value = LibbySessionState.Disconnected
        mutableConnectState.value = LibbyConnectState.Idle
        LibbyWebPlayer.clearLocalLibbySession {
            Log.d(TAG, "disconnected")
        }
    }

    private suspend fun checkForConnectedSession(): Boolean {
        val connected = LibbyWebPlayer.checkIsLoggedInSuspend()
        if (!connected) return false

        pollingJob = null
        mutableSessionState.value = LibbySessionState.Connected
        transition(LibbyConnectState.Connected, "connected")
        return true
    }

    private fun inspectSetupPage(onResult: (SetupInspection) -> Unit) {
        val script = """
            (() => {
              const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
              const fields = Array.from(document.querySelectorAll('.chip-code-control-field'));
              const status = document.querySelector('.chip-code-control-status');
              const statusText = normalize(status && status.textContent);
              const digits = fields.map(field => normalize(field.textContent)).join('');
              const countdownMatch = statusText.match(/(\d+)\s+seconds?\s+remaining/i);
              const fulfilled = fields.length === 8 && fields.every(field =>
                field.classList.contains('fulfilled')
              ) || /^success!?$/i.test(statusText);

              if (fulfilled) {
                return JSON.stringify({ phase: 'fulfilled' });
              }
              if (/^\d{8}$/.test(digits) && countdownMatch) {
                return JSON.stringify({
                  phase: 'code',
                  digits,
                  secondsRemaining: Number(countdownMatch[1])
                });
              }

              const candidates = Array.from(document.querySelectorAll(
                'button, [role="button"], a[href]'
              ));
              const displayCode = candidates.find(element => {
                const accessible = normalize(element.getAttribute('aria-label'));
                const visible = normalize(element.textContent);
                return accessible === 'Display Setup Code' || visible === 'Display Setup Code';
              });
              if (displayCode) {
                displayCode.click();
                return JSON.stringify({ phase: 'action-opened' });
              }

              if (
                window.APP && APP.nav && typeof APP.nav.go === 'function' &&
                !window.__livrospeakLibbySetupRouteOpened
              ) {
                window.__livrospeakLibbySetupRouteOpened = true;
                APP.nav.go('$SETUP_ROUTE');
                return JSON.stringify({ phase: 'route-opened' });
              }
              return JSON.stringify({ phase: 'waiting' });
            })();
        """.trimIndent()

        LibbyWebPlayer.requireWebView().evaluateJavascript(script) { raw ->
            try {
                val decoded = decodeJavascriptString(raw) ?: return@evaluateJavascript
                val json = JSONObject(decoded)
                onResult(
                    SetupInspection(
                        phase = json.optString("phase"),
                        digits = json.optString("digits"),
                        secondsRemaining = json.optInt("secondsRemaining", -1),
                    ),
                )
            } catch (_: Exception) {
                // The bounded setup timeout turns repeated extraction failures into safe UI state.
            }
        }
    }

    private fun handleInspection(result: SetupInspection) {
        when (result.phase) {
            "code" -> {
                if (result.digits.length != 8 || result.secondsRemaining < 0) return
                val existingDigits = activeCodeDigits
                if (existingDigits != null && existingDigits != result.digits) {
                    transition(LibbyConnectState.Expired, "code expired")
                } else if (result.secondsRemaining == 0) {
                    transition(LibbyConnectState.Expired, "code expired")
                } else {
                    activeCodeDigits = result.digits
                    codeExpiresAt = System.currentTimeMillis() + result.secondsRemaining * 1_000L
                    transition(
                        LibbyConnectState.Code(result.digits, result.secondsRemaining),
                        "code available",
                    )
                }
            }
            "fulfilled" -> {
                if (!shelfRequestedAfterFulfillment) {
                    shelfRequestedAfterFulfillment = true
                    Log.d(TAG, "connection transfer accepted")
                    scope.launch {
                        // Allow Libby's transfer interview to persist the copied session before
                        // requesting the authenticated shelf used by Livrospeak's stronger ready check.
                        delay(2_000)
                        if (mutableConnectState.value !is LibbyConnectState.Connected) {
                            LibbyWebPlayer.loadLibraryPage()
                        }
                    }
                }
            }
        }
    }

    private fun failToStart() {
        pollingJob?.cancel()
        pollingJob = null
        transition(
            LibbyConnectState.Error("Livrospeak could not start Libby setup."),
            "connection failed",
        )
    }

    private fun transition(state: LibbyConnectState, safeLog: String) {
        val previous = mutableConnectState.value
        mutableConnectState.value = state
        if (previous::class != state::class) Log.d(TAG, safeLog)
    }

    private fun decodeJavascriptString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try {
            JSONTokener(raw).nextValue() as? String
        } catch (_: Exception) {
            null
        }
    }

    private data class SetupInspection(
        val phase: String,
        val digits: String,
        val secondsRemaining: Int,
    )
}
