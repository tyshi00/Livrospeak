pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProperties = java.util.Properties().apply {
    val file = File(rootDir, "local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun privateRepositoryValue(name: String): String =
    localProperties.getProperty(name) ?: System.getenv(name).orEmpty()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/lightphone/light-keyboard")
            credentials {
                username = privateRepositoryValue("gpr.user")
                    .ifBlank { privateRepositoryValue("GITHUB_ACTOR") }
                password = privateRepositoryValue("gpr.key")
                    .ifBlank { privateRepositoryValue("GITHUB_TOKEN") }
            }
        }
    }
}

rootProject.name = "livrospeak"
include(":app")
