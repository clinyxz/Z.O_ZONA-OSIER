pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        // Shizuku API dari Maven Central
        // JitPack untuk android-vad (Silero VAD)
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack — android-vad (Silero VAD), dan library lain yang belum di Maven Central
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "zona-osier"

include(":android:app")
