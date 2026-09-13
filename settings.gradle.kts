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
        maven {
            name = "KapeGitHubPackages"
            url = uri("https://maven.pkg.github.com/pia-foss/mobile-android-vpn-manager")
            credentials {
                username = System.getenv("GITHUB_USERNAME") ?: "n/a"
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
            content {
                includeGroup("com.kape.android")
            }
        }
    }
}

rootProject.name = "I Launcher"
include(":app")
include(":joyntv")
