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
    }
}

rootProject.name = "CryptoAssistant"
include(":app")
include(":crypto-core")
// 内核是两端共享的上游，源码放在仓库根 shared/，不再归 Android 所有。
project(":crypto-core").projectDir = file("../shared/crypto-core")
include(":tools:desktop-bridge")
