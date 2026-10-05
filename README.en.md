# Offline SDK

This page describes the **`0.3.0` regular-release source** and coordinates `com.github.mobilewhj:offlineSdk:0.3.0`. See the [fixed `0.3.0` sample](https://github.com/mobilewhj/offlineSdk/tree/0.3.0/app) and [release scope and verification requirements](docs/RELEASE-0.3.0.md). `0.3.0-rc.1` remains the previously published integration-test candidate: `com.github.mobilewhj:offlineSdk:0.3.0-rc.1`. Use the [compilable Demo at the fixed tag](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/app) and its [fixed API guide](https://github.com/mobilewhj/offlineSdk/tree/ea4f042e98533c94694001503c2522ba0d1e7446/docs/MIGRATION-MANAGED-0.3.0.md). The [remote release evidence](docs/verification/2026-09-29-test-release/README.md) identifies the published artifact.

**New capabilities in `0.3.0`.** Its B2 responsibility split, default file storage, four-primitive keyValue adapter, stable codec, and `prepareStartup` are implemented; these APIs are not in the fixed RC. The current integration flow is below and in the [Demo guide](docs/DEMO.md). An app using the fixed RC need not switch to a local candidate.

[![CI](https://github.com/mobilewhj/offlineSdk/actions/workflows/ci.yml/badge.svg)](https://github.com/mobilewhj/offlineSdk/actions/workflows/ci.yml) [![JitPack](https://jitpack.io/v/mobilewhj/offlineSdk.svg)](https://jitpack.io/#mobilewhj/offlineSdk)

[中文](README.md) | **English**

A **single-package offline solution for small Android projects**. Bundle one set of H5 static assets into a ZIP, install it into a versioned local directory, and serve matching WebView requests using their original URLs.

Designed for apps maintaining one H5 resource bundle with full-package updates. Multi-package orchestration, delta updates, and plugin systems are outside the current scope.

## Features and responsibilities

- HTTP(S) ZIP downloads, trusted SHA-256 verification, bounded extraction, and version directory publication.
- Kotlin suspending APIs, cancellation propagation, and download / extraction progress; Okio for file operations.
- System WebView resource mapping and an optional X5 response adapter.
- A thin Welcome sample with first-preparation progress, storage adapters, privacy conditions, and independent reporting tasks. The SDK owns updates and directory protection.

The managed entry owns updates and directory protection; the host supplies configuration, storage, consent/foreground facts and page operations. `0.3.0` supplies default storage and stable encoding. **A single package can have multiple version directories**: existing pages retain their original resources until cleanup is safe.

## 0.3.0: complete minimal flow

A new app implements no storage methods. Keep one manager on Application/Main, map configuration through the existing Repository, and forward current consent and legal foreground facts. `root` is an exclusive installation directory. Default state is stored in `noBackupFilesDir/offline-sdk-state/<namespace>`, separately from that root.

```kotlin
import android.webkit.WebViewClient
import com.offline.tool.ManagedOfflineSdk
import com.offline.tool.ManagedOfflineStorage
import com.offline.tool.ManagedPageCallbacks
import com.offline.tool.OfflineInterceptor
import com.offline.tool.StartupResult
import java.io.File

// Application / Main: existingConfigProvider maps the existing configuration chain.
val manager = ManagedOfflineSdk(
    root = File(context.filesDir, "offline-packages"),
    storage = ManagedOfflineStorage.default(context, namespace = "main"),
    configProvider = existingConfigProvider,
    minimumVersion = 100_000,
    onInstallationOutcome = receiveInstallationOutcome,
    onDiagnostic = receiveDiagnostic,
)
manager.setConditions(privacyAllowed, foreground) // Main; forward fresh facts.

// Welcome lifecycle coroutine: one call; progress must be thread-safe or dispatched to Main.
when (manager.prepareStartup(onProgress = showProgress)) {
    StartupResult.Continue -> finishOfflineWait()
    StartupResult.Deferred -> showExplicitRetry()
}

// After business configuration, ad and navigation gates, invoke once from the actual page.
manager.loadPage(originalUrl, baseUrl, object : ManagedPageCallbacks {
    override fun clearResourceCache(): Boolean {
        webView.clearCache(true)
        return true
    }
    override fun loadOffline(directory: File, interceptor: OfflineInterceptor, url: String) {
        webView.webViewClient = interceptor
        webView.loadUrl(url)
    }
    override fun loadOnline(url: String) {
        webView.webViewClient = WebViewClient()
        webView.loadUrl(url)
    }
})
```

The provider, callbacks and UI functions are host integration points. Compilable code is in [`DefaultStorageSample.kt`](app/src/main/java/com/offline/tool/sample/offline/DefaultStorageSample.kt) and [`MainActivity.kt`](app/src/main/java/com/offline/tool/sample/MainActivity.kt). Use the supplied interceptor and original URL, preserving WebView/X5 JSBridge, Cookie, localStorage and destruction guards. Hosts with an existing WebViewClient integrate `interceptor.resolve(...)` into their resource callback.

`Continue` ends this offline wait, including normal failure or disabled configuration; host gates still decide navigation. `Deferred` means temporary conditions are insufficient, without queued retries. Cancellation propagates. Use a stable namespace and a main-process singleton. Missing records return null; read faults throw. A successful write confirms synchronous commit. A false result does not promise rollback of visible file effects or cross-key transactions.

Existing media can implement only the String/Boolean primitives of `OfflineKeyValueStore` and use `ManagedOfflineStorage.keyValue(values, keys, legacyEvidence)`; writing a null string deletes that key. **Active/history strings must already use the SDK codec format.** Four primitives adapt the medium and do not convert arbitrary old strings. `legacyEvidence` supplies reliable historical facts, not format conversion. Keep a necessary `ManagedOfflineStorage` adapter for an old format instead of passing it directly to keyValue.

Configuration and untargeted faults use `onDiagnostic`; actual installation outcomes use `onInstallationOutcome`, once through their own channel. The host maps types into its existing reporting chain. SDK callbacks do not send HTTP; Demo logs are not backend receipts. Success protocol and backend acceptance remain separate.

### Safe detail for current configuration diagnostics

The stable reason/stage remain unchanged. Existing `ManagedFailure.detail` distinguishes missing versions, version floors, missing candidates, mismatched versions, SHA format, URL and same-version SHA conflicts, without presenting a candidate version as targetVersion. `ConfigResponse.Failure.detail` accepts only the exact safe markers `timeout`, `network`, `http`, `empty_response`, `response_decode` and `exception`, mapped to fixed `provider_*` text. Null has no detail; other non-null values produce `provider_detail_withheld`. Arbitrary exception messages, full responses, signed URLs and Tokens are not forwarded. Detail is diagnostic only, never a business branch, and SDK codec does not persist it. Invalid configuration creates no installation outcome or failed-version threshold; valid disabled configuration still takes priority.

## Local candidates and historical identity

The regular release uses an immutable `0.3.0` tag. For separate local validation, assign an explicit, fresh local version to each final source snapshot and generate the AAR, sources, POM and module. Verify the actual consumer's resolved coordinate, path and digest; the Demo's default project dependency only validates source compilation.

```sh
# SDK root; replace the placeholder with a new unique identity for this source.
./gradlew :offlineSdk:publishReleasePublicationToLocalReleaseRepository \
  -PsdkVersion=0.3.0-local-UNIQUE-SOURCE-ID --console=plain
```

Local candidate publication only accepts file repositories (default: `build/repo`), requires an explicit nonempty path-safe version, and rejects `0.3.0-rc.1` or an existing version directory. Source defaults to `0.3.0`; publishing still requires sdkVersion. Ordinary `publishToMavenLocal` remains refused. Only the explicit regular-release entry `-PjitpackRelease=true -PsdkVersion=0.3.0` can produce Maven-local artifacts under a fresh identity; the release entry also requires an explicit absolute `-Dmaven.repo.local` path, with a fresh directory for isolated checks. See the [release entry](docs/RELEASE-0.3.0.md#发布入口与身份保护). Workstation paths, former App candidate init scripts and old candidate commands remain frozen historical evidence, not instructions for republishing current source under an old identity. See the [historical provenance](docs/RELEASE-0.3.0.md#历史来源与验证边界) and the fixed RC's [release evidence](docs/verification/2026-09-29-test-release/README.md).

## Requirements

- Android API 24+.
- Source build: JDK 17, Gradle 8.11.1, AGP 8.10.1, Kotlin 2.0.21, Android SDK 35.
- JVM 17 bytecode; consumers need a toolchain compatible with Kotlin 2.0 metadata.
- System WebView does not require TBS. Add `com.tencent.tbs:tbssdk:44286` separately only when using X5.

## Installation

### Regular `0.3.0`

The dependency below uses the immutable `0.3.0` tag. Release verification requires an actual JitPack build, remote AAR/sources/POM/module, and ordinary Gradle consumption. Local builds cannot establish remote availability; see the [release guide](docs/RELEASE-0.3.0.md).

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mobilewhj") }
        }
    }
}
```

In your application module's `build.gradle.kts`:

```kotlin
implementation("com.github.mobilewhj:offlineSdk:0.3.0")
```

### Published fixed `0.3.0-rc.1`

Hosts staying on the RC keep `implementation("com.github.mobilewhj:offlineSdk:0.3.0-rc.1")` and use its fixed-tag samples above. See the [historical release evidence](docs/verification/2026-09-29-test-release/README.md). The RC has no new default/keyValue/codec/prepareStartup entry points.

### Previously published low-level `0.2.2`

`0.2.2` is already published. The following is historical guidance for hosts continuing to use the low-level API.

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mobilewhj") }
        }
    }
}
```

In your application module's `build.gradle.kts`:

```kotlin
implementation("com.github.mobilewhj:offlineSdk:0.2.2")
```

The public Kotlin package is `com.offline.tool`. The current source Demo defaults to `implementation(project(":offlineSdk"))`; use the fixed tag above for RC samples.

`0.2.2` lets the entry-file check complete during an unrelated background download, without changing public API signatures. The result fields added in `0.2.1` changed some binary signatures; rebuild consumers upgrading from `0.2.0`. See the [host migration notes](docs/MIGRATION-INSTALL-FACTS.md) and [concurrency fix record (Chinese)](docs/EXECUTION-ISUSABLE-CONCURRENCY.md).

## Historical low-level integration

Reuse one installer for each resource root:

```kotlin
import com.offline.tool.InstallResult
import com.offline.tool.PackageInstaller
import com.offline.tool.PackageRecord
import java.io.File

val installer = PackageInstaller(File(context.filesDir, "offline-packages"))

suspend fun installCandidate(record: PackageRecord, url: String): InstallResult =
    installer.install(record, url)
```

Versions start at `10000`. Supply a trusted, 64-character lowercase hexadecimal `sha256`. Cancellation propagates as an exception. Installation failures return `InstallResult.Failure`, including `reason`, `stage`, and `httpStatus` diagnostics. Diagnostic messages may be Chinese; use the typed reason and stage for application logic.

**After `Success`, persist `result.record` before allowing new pages to use that version.** The low-level `PackageInstaller` does not save the active record; the managed entry calls the host storage adapter to save it. See the [Welcome sample guide (Chinese)](docs/DEMO.md) for the complete integration flow.

On the main thread, bind WebView to an installed and persisted version:

```kotlin
import com.offline.tool.OfflineInterceptor

webView.webViewClient = OfflineInterceptor(
    directory = installer.directory(installedVersion),
    baseUrl = "https://your-site.example/app/",
)
webView.loadUrl("https://your-site.example/app/")
```

`baseUrl` must end with `/`. Mapping preserves the original URL and handles only matching GET requests without Range headers. Misses fall back to normal WebView loading. If you already have a WebViewClient, call `interceptor.resolve(...)` from your `shouldInterceptRequest` implementation.

## ZIP and update contracts

A ZIP must contain a non-empty `index.html` at its root or inside `dist/`:

```text
site.zip
├── index.html
├── assets/
│   ├── app.js
│   └── app.css
└── images/
```

- Maximum archive size and individual file size: 64 MiB each. Maximum total extracted size: 256 MiB. Maximum entries: 10000.
- Existing version directories are never overwritten. Do not change content under an existing version number.
- Each WebView binds to a fixed version. Background updates do not reload existing pages.
- Low-level callers use `clearOldVersions(...)` only when no page uses any directory to be removed; the manager owns cleanup of managed roots.
- The sample has one process manager: Welcome owns its first-preparation call, and the SDK schedules later checks from Application conditions. Cross-process coordination is not provided.

See the [SDK API guide (Chinese)](offlineSdk/README.md) for progress, stream ownership, cancellation, cleanup, mapping rules, and X5 details.

## Run the sample

Open the project in Android Studio and run `app`. Its application ID is `com.offline.tool.sample`. The sample installs a synthetic ZIP bundled in the APK, so it requires no server or account.

On first launch, Welcome waits for the SDK to prepare resources, save active, and confirm usability. A failed first offline preparation ends the offline wait and the page loads the original URL. Later launches skip first-install UI even when no package remains; the SDK checks silently while the app is foreground and consented. The Demo configuration adapter returns the built-in candidate. Integrate a real endpoint through the host's existing Retrofit / Moshi stack. See the [Demo guide](docs/DEMO.md).

| Directory | Contents |
| --- | --- |
| `offlineSdk/` | Publishable SDK and tests |
| `app/` | ViewModel / StateFlow / ViewBinding sample and host integration |
| `docs/` | Sample guide, validation record, and release steps |

## Validation and release status

```bash
./gradlew :offlineSdk:testDebugUnitTest :offlineSdk:lintRelease \
  :offlineSdk:compileDebugAndroidTestKotlin :offlineSdk:assembleRelease \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The previously published `0.2.2` source passed 72 JVM tests, Debug / R8 Release builds, and integration against the local Maven AAR. The [`0.3.0-rc.1` pre-release acceptance snapshot](docs/verification/2026-09-29-complexity-reduction/README.md) records 129 SDK tests, 14 Demo tests, and local AAR consumption. The isolated tag Demo consumed the remote JitPack AAR and passed Debug, Release/R8, 14/14 tests, lint, and AndroidTest Kotlin compilation; see the [release evidence](docs/verification/2026-09-29-test-release/README.md). **F4 device acceptance remains open.** The final `0.3.0` artifact has no passing result for the full Demo lifecycle, system WebView cache / Cookie / headers / Range behavior, or the X5 runtime. Historical evidence is in the [0.2.2 execution record (Chinese)](docs/EXECUTION-ISUSABLE-CONCURRENCY.md), [0.2.1 execution record (Chinese)](docs/EXECUTION-INSTALL-FACTS.md), and [0.2.0 validation record (Chinese)](docs/VALIDATION-0.2.0.md).

The `0.3.0` functional baseline was independently accepted as a local candidate. Regular release verification separately binds the final tag, remote artifacts and ordinary Gradle consumption; the RC counts above are not new regular-release results. See the [validation boundary](docs/RELEASE-0.3.0.md#历史来源与验证边界). API24, real X5, real cold processes, the default five-minute interval, full business startup/two-hour paths and backend receipts remain separate open items. SDK publication does not upgrade the business App or complete its acceptance. With a connected device, run `./gradlew :offlineSdk:connectedDebugAndroidTest`; compiling Android test sources does not mean device tests passed.

[Changelog](CHANGELOG.md) · [0.3.0 release guide (Chinese)](docs/RELEASE-0.3.0.md) · [GitHub Issues](https://github.com/mobilewhj/offlineSdk/issues)

## Development and license

`.editorconfig` defines Kotlin official style, four-space indentation, and XML attribute wrapping. Use Android Studio Reformat Code.

Licensed under the [Apache License 2.0](LICENSE).

Prefer Maven coordinates for transitive dependencies; the AAR does not bundle them. The fixed RC uses the Kotlin standard library, OkHttp 4.12.0, Okio 3.7.0 and kotlinx-coroutines-android 1.7.3. `0.3.0` additionally uses Gson 2.11.0 for strict JSON syntax validation. Its regular-release remote POM/module defines the full dependency set; directly copying an AAR does not remove those requirements.

## Reproduce the demo package

The ZIP is generated locally from the reviewed files in `sample-web/`; the generator accepts no download URL or external archive. Python 3 uses a fixed entry order, timestamp and permissions. It updates the demo SHA-256 together with the ZIP.

```sh
python3 scripts/generate-sample.py
python3 scripts/generate-sample.py --check
```

CI verifies that the committed ZIP and configured hash match the example source. After changing sample content, increment the package version before testing against an existing installation; clearing business data is not an upgrade or compatibility strategy. Test archives are synthesized locally by the test fixtures. No business web assets or account are required.

[0.2.0 migration / 改名接入说明](docs/MIGRATION-0.2.0.md)
