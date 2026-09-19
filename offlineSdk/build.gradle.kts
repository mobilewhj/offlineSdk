plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "com.github.mobilewhj.offlineSdk"
version = providers.gradleProperty("sdkVersion").getOrElse("0.2.0")

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
    compileOnly(libs.tbs)
    api(libs.kotlinx.coroutines.android)
    api(libs.okhttp)
    implementation(libs.okio)
    testImplementation(libs.junit)
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
