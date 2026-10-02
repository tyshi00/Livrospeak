import java.util.Properties

plugins {
    id("com.android.application") version "8.6.0"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val releaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}

if (releaseTaskRequested && !keystorePropertiesFile.isFile) {
    throw GradleException(
        "Release signing requires the ignored keystore.properties file. See RELEASE.md.",
    )
}

android {
    namespace = "com.tyshi00.livrospeak"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tyshi00.livrospeak"
        minSdk = 33
        targetSdk = 34
        versionCode = 34
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = file(requireNotNull(keystoreProperties.getProperty("storeFile")))
                storePassword = requireNotNull(keystoreProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(keystoreProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(keystoreProperties.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.webkit:webkit:1.9.0")
    // Bumped from 0.0.11 -- that version's EnBaseViewModel forwarded Space to the
    // callback on PRESS+REPEAT; the code in LightUrlInputEditor.kt was rewritten
    // against the library's current (0.0.19) main-branch source, which forwards it on
    // RELEASE instead (see UrlKeyboardCallback's doc comment there for the exact
    // contract). 0.0.19 is NOT published to this GitHub Packages registry, though --
    // confirmed via its maven-metadata.xml that 0.0.17-dirty is the newest version
    // actually available here, so that's what's pinned. It should carry the same
    // post-refactor API (concrete per-locale view models, Space-on-release) that
    // 0.0.19 does, since none of what changed between 0.0.18 and 0.0.19 touched that,
    // but this hasn't been confirmed against 0.0.17-dirty's actual source -- if the
    // build fails on the keyboard integration files specifically (not just dependency
    // resolution), that mismatch is the first thing to suspect.
    implementation("com.thelightphone.lp3keyboard:ui:0.0.17-dirty")
}
