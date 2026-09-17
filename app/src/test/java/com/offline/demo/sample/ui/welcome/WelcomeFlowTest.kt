package com.offline.demo.sample.ui.welcome

import androidx.lifecycle.ViewModelStore
import com.offline.demo.PackageInstaller
import com.offline.demo.PackageRecord
import com.offline.demo.sample.offline.DemoOfflinePackages
import com.offline.demo.sample.offline.InstallOfflinePackageUseCase
import com.offline.demo.sample.offline.LocalPreparationProgress
import com.offline.demo.sample.offline.OfflineInstallResult
import com.offline.demo.sample.offline.OfflinePackageCandidate
import com.offline.demo.sample.offline.OfflinePackageSource
import com.offline.demo.sample.offline.OfflinePageAdapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeFlowTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val processScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val viewModels = ViewModelStore()
    private lateinit var installer: PackageInstaller
    private lateinit var packages: DemoOfflinePackages
    private lateinit var install: InstallOfflinePackageUseCase
    private lateinit var recordFile: File
    private val failures = mutableListOf<OfflineInstallResult.Failure>()
    private val bytes = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("index.html"))
            zip.write("<h1>Offline fixture</h1>".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    private fun candidate(version: Int = 10_000) = OfflinePackageCandidate(
        PackageRecord(version, bytes.toByteString().sha256().hex()),
        OfflinePackageSource.Builtin { bytes.inputStream() },
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        installer = PackageInstaller(folder.root.resolve("packages"), ioDispatcher = dispatcher)
        recordFile = folder.root.resolve("current.txt")
        packages = DemoOfflinePackages(installer, recordFile, dispatcher)
        install = InstallOfflinePackageUseCase(packages, installer, dispatcher)
    }

    @After
    fun teardown() {
        viewModels.clear()
        processScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(repository: WelcomeRepository = WelcomeRepository { candidate() }): WelcomeViewModel =
        WelcomeViewModel(repository, packages, install, processScope, failures::add)
            .also { viewModels.put("welcome", it) }

    @Test
    fun firstStartInstallsAndPersistsBeforeReady() = runTest {
        val vm = viewModel()
        vm.prepare()
        vm.prepare()
        advanceUntilIdle()
        assertEquals(WelcomeUiState.Ready, vm.uiState.value)
        assertEquals(candidate().record, packages.current())
        assertTrue(packages.hasUsablePackage())
        assertTrue(failures.isEmpty())
        // 重新创建存储入口，验证不是只有进程内缓存。
        assertEquals(candidate().record, DemoOfflinePackages(installer, recordFile, dispatcher).current())
    }

    @Test
    fun configurationFailureStaysAndExplicitRetryCanRecover() = runTest {
        var attempts = 0
        val vm = viewModel(
            WelcomeRepository {
                if (attempts++ == 0) throw IOException("unavailable")
                candidate()
            }
        )
        vm.prepare()
        advanceUntilIdle()
        assertEquals(WelcomeUiState.Failed("CONFIG_REQUEST_FAILED"), vm.uiState.value)
        assertNull(packages.current())
        vm.prepare()
        advanceUntilIdle()
        assertEquals(WelcomeUiState.Ready, vm.uiState.value)
        assertEquals(2, attempts)
    }

    @Test
    fun hashMismatchDoesNotSaveOrNavigate() = runTest {
        val wrong = candidate().copy(record = PackageRecord(10_000, "0".repeat(64)))
        val vm = viewModel(WelcomeRepository { wrong })
        vm.prepare()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is WelcomeUiState.Failed)
        assertEquals("INSTALL_HASH_MISMATCH", failures.single().reason)
        assertNull(packages.current())
        assertFalse(installer.directory(10_000).exists())
    }

    @Test
    fun failedRecordSaveStaysAndRetryReinstallsUnboundDirectory() = runTest {
        assertTrue(recordFile.mkdir())
        val vm = viewModel()
        vm.prepare()
        advanceUntilIdle()
        assertEquals(WelcomeUiState.Failed("RECORD_SAVE_FAILED"), vm.uiState.value)
        assertTrue(installer.directory(10_000).isDirectory)
        assertNull(packages.current())
        assertTrue(recordFile.delete())
        vm.prepare()
        advanceUntilIdle()
        assertEquals(WelcomeUiState.Ready, vm.uiState.value)
        assertEquals(candidate().record, packages.current())
    }

    @Test
    fun existingPackageEntersDespiteBackgroundConfigFailure() = runTest {
        assertTrue(install(candidate()) is OfflineInstallResult.Success)
        val vm = viewModel(WelcomeRepository { throw IOException("offline") })
        vm.prepare()
        advanceUntilIdle()
        assertEquals(WelcomeUiState.Ready, vm.uiState.value)
        assertEquals("CONFIG_REQUEST_FAILED", failures.single().reason)
        assertEquals(candidate().record, packages.current())
    }

    @Test
    fun missingLocalEntryIsRepairedBeforeReady() = runTest {
        install(candidate())
        assertTrue(installer.directory(10_000).resolve("index.html").delete())
        val vm = viewModel()
        vm.prepare()
        advanceUntilIdle()
        assertEquals(WelcomeUiState.Ready, vm.uiState.value)
        assertTrue(packages.hasUsablePackage())
    }

    @Test
    fun backgroundUpgradePreservesAlreadyBoundPage() = runTest {
        install(candidate())
        val adapter = OfflinePageAdapter(packages, dispatcher)
        val oldPage = checkNotNull(adapter.acquireForPage("https://example.com/demo/", "https://example.com/demo/"))
        val vm = viewModel(WelcomeRepository { candidate(10_001) })
        vm.prepare()
        advanceUntilIdle()
        assertEquals(WelcomeUiState.Ready, vm.uiState.value)
        assertEquals(10_001, packages.current()?.version)
        assertEquals(installer.directory(10_000), oldPage.directory)
        assertTrue(oldPage.directory.resolve("index.html").isFile)
        assertEquals("TARGET_IN_USE", packages.prepareTarget(candidate().record)?.reason)
        // 下一次冷启动才清理无人绑定的旧资源。
        DemoOfflinePackages(installer, recordFile, dispatcher).prepareLocal()
        assertFalse(oldPage.directory.exists())
        assertTrue(installer.directory(10_001).isDirectory)
    }

    @Test
    fun clearingViewModelCancelsFirstPreparationWithoutFailure() = runTest {
        var cancelled = false
        val vm = viewModel(
            WelcomeRepository {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        )
        vm.prepare()
        advanceUntilIdle()
        viewModels.clear()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertTrue(failures.isEmpty())
        assertNull(packages.current())
        assertTrue(vm.uiState.value is WelcomeUiState.Preparing)
    }

    @Test
    fun backgroundUpdateOutlivesWelcomeViewModel() = runTest {
        install(candidate())
        var cancelled = false
        val vm = viewModel(
            WelcomeRepository {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        )
        vm.prepare()
        advanceUntilIdle()
        assertEquals(WelcomeUiState.Ready, vm.uiState.value)
        viewModels.clear()
        advanceUntilIdle()
        assertFalse(cancelled)
        processScope.cancel()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun remoteInstallReportsRealProgressAndSavesRecord() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val target = candidate().copy(source = OfflinePackageSource.Remote(server.url("/package.zip").toString()))
            val progress = mutableListOf<LocalPreparationProgress>()
            assertTrue(install(target, progress::add) is OfflineInstallResult.Success)
            assertEquals(target.record, packages.current())
            assertTrue(progress.any { it.stage == LocalPreparationProgress.Stage.DOWNLOADING && it.percent == 100 })
            assertTrue(progress.any { it.stage == LocalPreparationProgress.Stage.EXTRACTING })
            assertEquals(LocalPreparationProgress.Stage.SAVING, progress.last().stage)
        }
    }

    @Test
    fun bundledCandidateDigestMatchesShippedAsset() = runTest {
        val asset = File("src/main/assets/sample.zip")
        val repository = WelcomeRepositoryImpl { asset.inputStream() }
        val candidate = repository.getOfflinePackage()
        assertTrue(install(candidate) is OfflineInstallResult.Success)
        assertEquals(candidate.record, packages.current())
    }

    @Test
    fun malformedRecordDoesNotClaimUsableFiles() = runTest {
        recordFile.writeText("broken\nnot-a-digest\n")
        assertNull(packages.current())
        val vm = viewModel()
        vm.prepare()
        advanceUntilIdle()
        assertEquals(WelcomeUiState.Ready, vm.uiState.value)
    }
}
