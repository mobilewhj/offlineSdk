plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "com.github.mobilewhj.offlineSdk"
val releaseVersion = "0.3.0"
version = providers.gradleProperty("sdkVersion").getOrElse(releaseVersion)

android {
    namespace = "com.offline.tool"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.7.0")
    compileOnly(libs.tbs)
    api(libs.kotlinx.coroutines.android)
    api(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.gson)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "offlineSdk"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Offline SDK")
                description.set("Single-package offline ZIP installation and WebView resource mapping for small Android projects")
                url.set("https://github.com/mobilewhj/offlineSdk")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("mobilewhj")
                        url.set("https://github.com/mobilewhj")
                    }
                }
                scm {
                    url.set("https://github.com/mobilewhj/offlineSdk")
                    connection.set("scm:git:https://github.com/mobilewhj/offlineSdk.git")
                    developerConnection.set("scm:git:ssh://git@github.com/mobilewhj/offlineSdk.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "localRelease"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}

// Keep the license with standalone source distributions as well as the AAR.
tasks.withType<org.gradle.api.tasks.bundling.Jar>().configureEach {
    if (name == "sourceReleaseJar") {
        from(rootProject.file("LICENSE")) {
            into("META-INF/offline-sdk")
        }
    }
}

// 当前源码只能发布为显式新身份；已发布 RC 与任何既有本地版本均不可覆盖。
fun verifyNewPublicationIdentity(repositoryDirectory: File, publication: MavenPublication) {
    val explicitVersion = providers.gradleProperty("sdkVersion").orNull
    check(!explicitVersion.isNullOrBlank()) { "Local publication requires an explicit new -PsdkVersion" }
    check(explicitVersion.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Invalid sdkVersion" }
    check(explicitVersion != "0.3.0-rc.1") { "Published 0.3.0-rc.1 is immutable" }
    val target = repositoryDirectory.resolve(
        "${publication.groupId.replace('.', '/')}/${publication.artifactId}/$explicitVersion"
    )
    check(!target.exists()) { "Publication identity already exists: $target; choose a new sdkVersion" }
}

tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
    doFirst {
        check(repository.url.scheme == "file") { "This working tree only permits local candidate publication" }
        verifyNewPublicationIdentity(File(repository.url), publication)
    }
}

tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenLocal>().configureEach {
    doFirst {
        // JitPack 正式入口显式指定仓库，避免 Maven settings 改写实际输出位置。
        check(providers.gradleProperty("jitpackRelease").orNull == "true") {
            "Use isolated build/repo for candidates; Maven local requires -PjitpackRelease=true"
        }
        check(providers.gradleProperty("sdkVersion").orNull == releaseVersion) {
            "JitPack publication requires explicit -PsdkVersion=$releaseVersion"
        }
        val localRepository = providers.systemProperty("maven.repo.local").orNull
        check(!localRepository.isNullOrBlank()) { "JitPack publication requires explicit -Dmaven.repo.local" }
        val localRepositoryDirectory = File(localRepository)
        check(localRepositoryDirectory.isAbsolute) { "maven.repo.local must be an absolute path" }
        verifyNewPublicationIdentity(localRepositoryDirectory, publication)
    }
}
