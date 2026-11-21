pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Light's LP3 keyboard is open source and published through JitPack, the
        // same repository the Light SDK itself resolves it from. No token needed.
        // Restricted to the keyboard's own groups (JitPack's com.github.lightphone
        // naming, and the library's own com.thelightphone.lp3keyboard name in case
        // JitPack's metadata refers to it) so nothing else can be resolved from
        // JitPack by accident.
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
            content {
                includeGroupByRegex("com\\.github\\.lightphone(\\..*)?")
                includeGroup("com.thelightphone.lp3keyboard")
            }
        }
    }
}

rootProject.name = "livrospeak"
include(":app")
