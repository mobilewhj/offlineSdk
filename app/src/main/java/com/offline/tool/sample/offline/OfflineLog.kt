package com.offline.tool.sample.offline

import android.util.Log
import com.offline.tool.sample.BuildConfig

internal fun logOffline(message: String) {
    if (BuildConfig.DEBUG) Log.d("OfflineDemo", message)
}
