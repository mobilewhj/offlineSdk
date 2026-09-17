package com.offline.demo.sample.offline

import android.util.Log
import com.offline.demo.sample.BuildConfig

internal fun logOffline(message: String) {
    if (BuildConfig.DEBUG) Log.d("OfflineDemo", message)
}
