# Offline SDK

[![CI](https://github.com/mobilewhj/offlineSdk/actions/workflows/ci.yml/badge.svg)](https://github.com/mobilewhj/offlineSdk/actions/workflows/ci.yml) [![JitPack](https://jitpack.io/v/mobilewhj/offlineSdk.svg)](https://jitpack.io/#mobilewhj/offlineSdk)

[中文](README.md) | **English**

A **single-package offline solution for small Android projects**. Bundle one set of H5 static assets into a ZIP, install it into a versioned local directory, and serve matching WebView requests using their original URLs.

Designed for apps maintaining one H5 resource bundle with full-package updates. Multi-package orchestration, delta updates, and plugin systems are outside the current scope.

## Features and responsibilities

- HTTP(S) ZIP downloads, trusted SHA-256 verification, bounded extraction, and version directory publication.
- Kotlin suspending APIs, cancellation propagation, and download / extraction progress; Okio for file operations.
- System WebView resource mapping and an optional X5 response adapter.
- A Welcome sample covering first installation, retry, local record persistence, background updates, and existing-page protection.

The SDK manages resource files. The host owns candidate selection, configuration APIs, record persistence, and page lifecycle. **A single package can have multiple version directories**: existing pages retain their original resources until cleanup is safe.

## Requirements

- Android API 24+.
- Source build: JDK 17, Gradle 8.11.1, AGP 8.10.1, Kotlin 2.0.21, Android SDK 35.
- JVM 17 bytecode; consumers need a toolchain compatible with Kotlin 2.0 metadata.
- System WebView does not require TBS. Add `com.tencent.tbs:tbssdk:44286` separately only when using X5.

## Installation

Version: `0.2.1`. Confirm a successful build for this version on [JitPack](https://jitpack.io/#mobilewhj/offlineSdk) before using the coordinates below.

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
implementation("com.github.mobilewhj:offlineSdk:0.2.1")
```

The public Kotlin package is `com.offline.tool`. The checked-out sample uses `implementation(project(":offlineSdk"))`.

`0.2.1` adds installation facts and an entry-file check. The new result fields change some binary signatures; rebuild consumers upgrading from `0.2.0`. See the [host migration notes](docs/MIGRATION-INSTALL-FACTS.md).

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

**After `Success`, persist `result.record` before allowing new pages to use that version.** The SDK does not save the active record. See the [Welcome sample guide (Chinese)](docs/DEMO.md) for the complete integration flow.

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
- The sample has one process and one Welcome update entry point; it does not coordinate multiple processes.

See the [SDK API guide (Chinese)](offlineSdk/README.md) for progress, stream ownership, cancellation, cleanup, mapping rules, and X5 details.

## Run the sample

Open the project in Android Studio and run `app`. Its application ID is `com.offline.tool.sample`. The sample installs a synthetic ZIP bundled in the APK, so it requires no server or account.

On first launch, Welcome prepares resources and persists the record before opening WebView. Failures remain on Welcome with retry available. Later launches open an available local package first and check for updates in the background. The default Repository returns the built-in candidate. Integrate a real configuration endpoint through the host's existing Retrofit / Moshi stack. The remote ZIP installation path is covered by MockWebServer tests.

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

Current source passed 71 JVM tests, Debug / R8 Release builds, and integration against the actual local Maven AAR. **Device acceptance remains pending.** Welcome / system WebView and X5 have not been verified on a device. See the [current execution record (Chinese)](docs/EXECUTION-INSTALL-FACTS.md); the [0.2.0 validation record (Chinese)](docs/VALIDATION-0.2.0.md) remains historical.

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
