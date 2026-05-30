package org.fivesevenfive.wearvian.companion.util

import android.util.Log

/**
 * Tiny logging helpers so every line shares one tag, filterable with
 * `adb logcat -s wearvian-companion`. Verbose by design — debugging build.
 */
const val TAG = "wearvian-companion"

fun logd(msg: String) {
    Log.d(TAG, msg)
}

fun logi(msg: String) {
    Log.i(TAG, msg)
}

fun logw(msg: String, t: Throwable? = null) {
    if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg)
}

fun loge(msg: String, t: Throwable? = null) {
    if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
}
