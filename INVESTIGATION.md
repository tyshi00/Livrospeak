# Libby Screen-Off Playback Investigation

## Iteration 1 — 2026-07-20 12:44 EDT

### Hypothesis

The previous failure at `nativeBridge.postMessage()` may have been caused by diagnostic or routine bridge traffic rather than the Libby player. Livrospeak's normal player-state poll and the temporary diagnostic probe both use the same WebMessage channel.

### Code or instrumentation changed

- Replaced bursty diagnostic bridge-step messages with console-only intermediate markers.
- Limited the screen-off experiment to one position-only bridge response at 15 seconds.
- Temporarily suppressed Livrospeak's routine 700 ms Libby state polling while the screen-off experiment is active.
- Added aggregate native-command, received-message, and suppressed-poll counters.
- Retained WebView identity, renderer, evaluation, and lifecycle diagnostics.

### Commands run

- Inspected `AGENTS.md`, Git status/history, `LibbyBridge`, `LibbyPlaybackDiagnostics`, `LibbyWebPlayer`, `MainActivity`, and the Compose polling loop.
- Searched every `getPlayerState()` call site.
- Built with `./gradlew assembleDebug` and checked with `git diff --check`.
- Installed `app-debug.apk` with `adb install -r` and launched `com.tyshi00.livrospeak/.MainActivity`.
- Cleared and captured Logcat, then inspected `dumpsys power`, `dumpsys audio`, `dumpsys activity processes`, Device Idle, `/proc`, and process cgroups.
- Ran `adb shell am unfreeze com.tyshi00.livrospeak` once after the failure.

### Human action requested

Requested the exact two physical steps after the APK and capture were ready. The user started a Libby book, turned off the screen, and replied `DONE`.

### Evidence collected

Facts before this iteration:

- `LibbyBridge` uses `WebViewCompat.addWebMessageListener()` with document-start JavaScript in the cross-origin Libby player frame.
- JavaScript-to-Android messages enter `WebViewCompat.WebMessageListener.onPostMessage()` without an app-supplied executor.
- The temporary diagnostic markers used the same JavaScript-to-Android bridge as the final state response.
- The Compose player loop invokes `LibbyBridge.getPlayerState()` every 700 ms while the authenticated loan route remains active, including after Activity stop unless its coroutine is destroyed.
- Only one production call site invokes `getPlayerState()`.
- The Android diagnostic-step receiver parsed JSON, logged, and returned; it did not call back into WebView.

Facts from the device experiment:

- Screen-off was received at `12:48:49.026`.
- The 10-second checkpoint completed at `12:48:59.037` with the same WebView identity and renderer handle.
- No diagnostic bridge probe had run. Routine polling had been suppressed 14 times; native-frame and received-frame message counts remained unchanged at 12 and 16.
- At `12:48:59.067`, 30 ms after the completed checkpoint, Android logged `ActivityManager: freezing 14937 com.tyshi00.livrospeak`.
- Android classified Livrospeak as cached/empty with `oom_adj=900`, `procState=16`, `isFreezeExempt=false`, and `isFrozen=true`.
- The WebView renderer was also cached and frozen with `oom_adj=910`, `procState=17`, and `isFrozen=true`.
- Audio focus remained granted to Chromium with no loss notification.
- Android continued to report Livrospeak's Chromium AAudio player as `state:started`.
- While frozen, the scheduled 15-, 30-, 60-, and 90-second executor tasks did not execute.
- Manually unfreezing only Livrospeak's host process caused all overdue executor tasks to run immediately, proving they had been suspended rather than lost.
- The renderer remained frozen after the host-only unfreeze. All queued `evaluateJavascript()` calls returned from Java but received no callbacks.
- Chromium then logged repeated `SyncReader::Read timed out, audio glitch count` messages and `No room in socket buffer`, consistent with a frozen renderer/audio producer.

### Result

The low-traffic experiment disproved bridge saturation as the primary failure. Android's cached-app freezer activates at the observed failure boundary and freezes both Livrospeak and its WebView renderer.

### Proven conclusion

Libby playback stops because Livrospeak has no ongoing Android component that marks it as perceptible playback. Once its Activity is stopped, ActivityManager demotes it to a cached process and freezes it after approximately 10 seconds. The Libby audio producer lives in Livrospeak/Chromium rather than a separate Android media service, so freezing halts audio production even though audio focus and the registered AAudio state remain nominally active.

The WebMessage bridge is not the root cause. Its apparent blocking was an observation made at or around process freezing, amplified by high diagnostic traffic.

### Next hypothesis

A minimal Android media-playback foreground service should be tested as the production correction. It should keep the existing application-scoped WebView host process non-cached only while Libby is authoritatively playing, without creating or hosting a second WebView. No foreground service has been implemented in this investigation.

## Iteration 2 — 2026-07-20

### Hypothesis

A media-playback foreground service running in Livrospeak's existing process will keep both the host process and its client WebView renderer out of the cached-app freezer while Libby is authoritatively playing.

### Code or instrumentation changed

- Added a minimal `mediaPlayback` foreground service with the required Android permissions and a private ongoing notification.
- Start/stop is driven only by authoritative Libby `PlayerState.isPlaying` and command-result state.
- The service stops when playback pauses, the player route is left, the bridge reports an error, or the local Libby session is cleared.
- The service does not create a WebView, own playback, issue commands, or affect Local/RSS playback.

### Commands run

- Built with `./gradlew assembleDebug` and checked with `git diff --check`.
- Installed the debug APK with `adb install -r` and launched `com.tyshi00.livrospeak/.MainActivity`.
- Cleared and captured Logcat while inspecting the foreground-service record, ActivityManager process state, renderer process state, audio focus, AAudio state, and device power state.

### Human action requested

Requested the exact two physical steps after the APK and capture were ready. The user started a Libby book, turned off the screen, and replied `DONE`.

### Evidence collected

- The Play command was observed at `13:03:29.592`; Android allowed the foreground-service start because Livrospeak was the top application.
- `LibbyPlaybackForegroundService` entered the foreground at `13:03:29.863` with service type `mediaPlayback`.
- More than 17 seconds after screen-off, while the device reported `mWakefulness=Dozing`, Livrospeak remained foreground-service importance, `cached=false`, and `isFrozen=false`.
- The Chromium WebView renderer inherited foreground-service importance and also remained `cached=false` and unfrozen.
- More than two minutes after service start, Livrospeak and its renderer still had foreground-service process state and were not frozen.
- Audio focus remained granted to Chromium and its AAudio player remained `state:started`.
- The run produced no cached-app `freezing` event for Livrospeak and no Chromium sync-reader timeout/audio-glitch sequence seen in Iteration 1.
- No screen wake lock, partial wake lock, second WebView, or Local/RSS playback change was used.

### Result

The minimal media-playback foreground service prevented the exact cached-app freezer transition that stopped Libby playback in Iteration 1. Both the host process and WebView renderer remained runnable beyond the former ten-second boundary, while Android continued to report the Chromium audio path as started.

### Proven conclusion

For this device and reproduced failure, an in-process media-playback foreground service is sufficient to keep the existing hidden Libby WebView playback architecture alive during screen-off. A wake lock is not justified by the collected evidence.

### Next hypothesis

Verify the user-visible notification treatment and pause/end/disconnect stop transitions on the physical device. The freezer-prevention mechanism itself is proven by the process, renderer, and audio-state evidence above.
