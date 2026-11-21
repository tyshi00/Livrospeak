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
        versionCode = 37
        versionName = "1.2.0"
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
    // Light's open-source LP3 keyboard (github.com/lightphone/light-keyboard), at
    // the same version and coordinate the Light SDK uses (SDK 0.2.9). Its key
    // handling is unchanged from 0.0.19, which LightUrlInputEditor.kt is written
    // against.
    implementation("com.github.lightphone:light-keyboard:v0.0.21")
}
