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
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor（と依存の nanojson）は JitPack にしか無い。
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.TeamNewPipe.*") }
        }
        // 未リリースの修正版 NewPipeExtractor（localMaven/README.md 参照）。
        maven {
            url = uri("localMaven")
            content { includeGroup("net.newpipe") }
        }
    }
}

rootProject.name = "AutoTube"
include(":app")
