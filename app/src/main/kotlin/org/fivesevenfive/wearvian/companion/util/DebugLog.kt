package org.fivesevenfive.wearvian.companion.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-memory ring buffer of log lines, surfaced in an on-screen pane so the
 * enrollment/import flow is debuggable on a device without adb. Fed by the
 * [logi]/[logw]/[loge] helpers (so existing log calls show up automatically).
 * Thread-safe; bounded to [MAX] lines.
 */
object DebugLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Append a line. Does NOT call back into [logi] — the log helpers call this. */
    @Synchronized
    fun add(line: String) {
        lines.addLast("${ts.format(Date())}  $line")
        while (lines.size > MAX) lines.removeFirst()
        _flow.value = lines.toList()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        _flow.value = emptyList()
    }
}
