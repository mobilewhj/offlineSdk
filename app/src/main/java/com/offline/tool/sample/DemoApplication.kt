package com.offline.tool.sample

import android.app.Application
import com.offline.tool.PackageInstaller
import com.offline.tool.sample.offline.DemoOfflinePackages
import com.offline.tool.sample.offline.InstallOfflinePackageUseCase
import com.offline.tool.sample.offline.OfflinePageAdapter
import com.offline.tool.sample.offline.logOffline
import com.offline.tool.sample.ui.welcome.WelcomeRepositoryImpl
import com.offline.tool.sample.ui.welcome.WelcomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class DemoApplication : Application() {
    internal val graph by lazy { DemoGraph(this) }
}

/** 小型示例采用构造注入，同一个资源根目录只创建一个安装器。 */
internal class DemoGraph(application: Application) {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val installer = PackageInstaller(File(application.filesDir, "offline/packages"))
    private val packages = DemoOfflinePackages(installer, File(application.filesDir, "offline/current.txt"))
    private val repository = WelcomeRepositoryImpl { application.assets.open("sample.zip") }
    private val installPackage = InstallOfflinePackageUseCase(packages, installer)
    val pageAdapter = OfflinePageAdapter(packages)

    fun welcomeViewModel() = WelcomeViewModel(repository, packages, installPackage, processScope) {
        logOffline("failure=${it.reason} stage=${it.stage} http=${it.httpStatus}")
    }

    companion object {
        const val BASE_URL = "https://offline.example/demo/"
    }
}
