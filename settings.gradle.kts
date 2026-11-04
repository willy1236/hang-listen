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
        // NewPipeExtractor 與它依賴的 nanojson 只發布在 JitPack
        maven("https://jitpack.io")
    }
}

rootProject.name = "HangListen"
include(":app")
