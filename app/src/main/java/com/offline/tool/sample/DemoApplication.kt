package com.offline.tool.sample

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.annotation.MainThread
import com.offline.tool.ConfigResponse
import com.offline.tool.InstallationOutcome
import com.offline.tool.ManagedConfigProvider
import com.offline.tool.ManagedOfflineSdk
import com.offline.tool.OfflineCandidate
import com.offline.tool.OfflineConfiguration
import com.offline.tool.PackageRecord
import com.offline.tool.PackageSource
import com.offline.tool.sample.offline.DemoManagedStorage
import com.offline.tool.sample.offline.DemoOutcomeReporter
import com.offline.tool.sample.offline.logOffline
import java.io.File

class DemoApplication : Application() {
    private var startedActivities = 0
    private var privacyAllowed = true
    private val reporter = DemoOutcomeReporter(
        report = { outcome ->
            when (outcome) {
                is InstallationOutcome.Installed -> logOffline("installed version=${outcome.record.version}")
                is InstallationOutcome.Failed -> logOffline(
                    "install failed version=${outcome.failure.targetVersion} " +
                        "stage=${outcome.failure.stage} reason=${outcome.failure.reason}"
                )
            }
            // 真实宿主在此调用可取消的 Repository 挂起接口；SDK 不负责 HTTP 或后端编码。
        },
        onFailure = { error -> logOffline("report failed type=${error.javaClass.simpleName}") },
    )
    internal lateinit var manager: ManagedOfflineSdk
        private set

    override fun onCreate() {
        super.onCreate()
        // 路径只是数据，规范化和安装器接管由 SDK 的首次挂起入口在 IO 完成。
        manager = ManagedOfflineSdk(
            root = File(applicationInfo.dataDir, "files/offline/packages"),
            storage = DemoManagedStorage(this),
            configProvider = ManagedConfigProvider { currentVersion ->
                logOffline("configuration requested with usable version=$currentVersion")
                ConfigResponse.Success(
                    OfflineConfiguration(
                        enabled = true,
                        onlineVersion = BUILTIN_VERSION,
                        candidate = OfflineCandidate(
                            PackageRecord(BUILTIN_VERSION, BUILTIN_SHA256),
                            PackageSource.Local { assets.open("sample.zip") },
                        ),
                    )
                )
            },
            minimumVersion = BUILTIN_VERSION,
            onInstallationOutcome = reporter::submit,
            onDiagnostic = { failure ->
                logOffline("diagnostic stage=${failure.stage} reason=${failure.reason}")
            },
        )
        // 示例没有隐私页面；真实宿主通过 setPrivacyAllowed 接入授权与撤回。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                updateConditions()
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) updateConditions()
            }
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Main 上同步传入最新授权与前台事实；SDK 自行调度初始化和检查。 */
    @MainThread
    fun setPrivacyAllowed(allowed: Boolean) {
        privacyAllowed = allowed
        updateConditions()
    }

    private fun updateConditions() {
        reporter.setPrivacyAllowed(privacyAllowed)
        manager.setConditions(privacyAllowed, startedActivities > 0)
    }

    companion object {
        const val BASE_URL = "https://offline.example/demo/"
        private const val BUILTIN_VERSION = 10_000
        private const val BUILTIN_SHA256 = "28069f115248f28f7bc5d8dc42799c2d75b9c641caa374549657042f2b3ab47b"
    }
}
