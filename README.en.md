# Offline SDK

`0.3.0-rc.1` is a managed SDK candidate for app integration testing. **The final `0.3.0` has not been released.** The code and structure review passed; the fixed tag, JitPack build, and remote dependency consumption still require verification in this release run. The SDK owns initial preparation, five-minute foreground checks, failed-version gating, typed installation outcomes, and page-directory protection. See the [managed migration guide](docs/MIGRATION-MANAGED-0.3.0.md), [thin demo](docs/DEMO.md), and [pre-release acceptance snapshot](docs/verification/2026-09-29-complexity-reduction/README.md). The `0.2.2` coordinates below describe the previously published low-level release.

[![CI](https://github.com/mobilewhj/offlineSdk/actions/workflows/ci.yml/badge.svg)](https://github.com/mobilewhj/offlineSdk/actions/workflows/ci.yml) [![JitPack](https://jitpack.io/v/mobilewhj/offlineSdk.svg)](https://jitpack.io/#mobilewhj/offlineSdk)

[中文](README.md) | **English**

A **single-package offline solution for small Android projects**. Bundle one set of H5 static assets into a ZIP, install it into a versioned local directory, and serve matching WebView requests using their original URLs.

Designed for apps maintaining one H5 resource bundle with full-package updates. Multi-package orchestration, delta updates, and plugin systems are outside the current scope.

## Features and responsibilities

- HTTP(S) ZIP downloads, trusted SHA-256 verification, bounded extraction, and version directory publication.
- Kotlin suspending APIs, cancellation propagation, and download / extraction progress; Okio for file operations.
- System WebView resource mapping and an optional X5 response adapter.
- A thin Welcome sample with first-preparation progress, storage adapters, privacy conditions, and independent reporting tasks. The SDK owns updates and directory protection.

The managed entry owns update decisions and directory protection. The host supplies configuration, storage encoding, and page operations. The low-level integration below still leaves management to the caller. **A single package can have multiple version directories**: existing pages retain their original resources until cleanup is safe.

The test candidate's managed API uses one `prepareFirst(onProgress)` callback for first preparation and one suspending `loadPage(url, baseUrl, callbacks)` call for page selection and loading. See the [migration guide](docs/MIGRATION-MANAGED-0.3.0.md#最小接入链).

## Requirements

- Android API 24+.
- Source build: JDK 17, Gradle 8.11.1, AGP 8.10.1, Kotlin 2.0.21, Android SDK 35.
- JVM 17 bytecode; consumers need a toolchain compatible with Kotlin 2.0 metadata.
- System WebView does not require TBS. Add `com.tencent.tbs:tbssdk:44286` separately only when using X5.

## Installation

### `0.3.0-rc.1` integration-test candidate

These are the **expected** coordinates for the SDK module. The remote POM, build, and consumer resolution still need verification in this release run; check those results before integration testing. A local Maven artifact does not establish remote availability.

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mobilewhj.offlineSdk") }
        }
    }
}
```

In your application module's `build.gradle.kts`:

```kotlin
implementation("com.github.mobilewhj.offlineSdk:offlineSdk:0.3.0-rc.1")
```

### Previously published low-level `0.2.2`

`0.2.2` uses a different group. The following is historical guidance for hosts continuing to use the low-level API.

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

The public Kotlin package is `com.offline.tool`. The checked-out sample uses `implementation(project(":offlineSdk"))`.

`0.2.2` lets the entry-file check complete during an unrelated background download, without changing public API signatures. The result fields added in `0.2.1` changed some binary signatures; rebuild consumers upgrading from `0.2.0`. See the [host migration notes](docs/MIGRATION-INSTALL-FACTS.md) and [concurrency fix record (Chinese)](docs/EXECUTION-ISUSABLE-CONCURRENCY.md).

## Quick start

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
- Call `clearOldVersions(...)` only when no page uses any directory to be removed.
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

The previously published `0.2.2` source passed 72 JVM tests, Debug / R8 Release builds, and integration against the local Maven AAR. The [`0.3.0-rc.1` pre-release acceptance snapshot](docs/verification/2026-09-29-complexity-reduction/README.md) records 129 SDK tests, 14 Demo tests, and local AAR consumption; remote artifacts still require this release run's verification. **F4 device acceptance remains open.** The current candidate has no passing result for the full Demo lifecycle, system WebView cache / Cookie / headers / Range behavior, or the X5 runtime. Historical evidence is in the [0.2.2 execution record (Chinese)](docs/EXECUTION-ISUSABLE-CONCURRENCY.md), [0.2.1 execution record (Chinese)](docs/EXECUTION-INSTALL-FACTS.md), and [0.2.0 validation record (Chinese)](docs/VALIDATION-0.2.0.md).

With a connected device, run `./gradlew :offlineSdk:connectedDebugAndroidTest`. Compiling Android test sources does not mean device tests passed.

[Changelog](CHANGELOG.md) · [Release guide (Chinese)](docs/RELEASING.md) · [GitHub Issues](https://github.com/mobilewhj/offlineSdk/issues)

## Development and license

`.editorconfig` defines Kotlin official style, four-space indentation, and XML attribute wrapping. Use Android Studio Reformat Code.

Licensed under the [Apache License 2.0](LICENSE).

Prefer Maven coordinates to resolve transitive dependencies. When using an AAR directly, provide the Kotlin standard library, OkHttp 4.12.0, Okio 3.7.0, and kotlinx-coroutines-android 1.7.3 yourself; the AAR does not bundle these dependencies.

## Reproduce the demo package

The ZIP is generated locally from the reviewed files in `sample-web/`; the generator accepts no download URL or external archive. Python 3 uses a fixed entry order, timestamp and permissions. It updates the demo SHA-256 together with the ZIP.

```sh
python3 scripts/generate-sample.py
python3 scripts/generate-sample.py --check
```

CI verifies that the committed ZIP and configured hash match the example source. After changing the sample content, increment the demo package version before testing against an existing installation, or clear the demo app's data. Test archives are synthesized locally by the test fixtures. No business web assets or account are required.

[0.2.0 migration / 改名接入说明](docs/MIGRATION-0.2.0.md)
