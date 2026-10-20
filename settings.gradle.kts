pluginManagement {
    // Convention plugins (logxy.kotlin), kept with the rest of the build plumbing under gradle/.
    includeBuild("gradle/plugins")
}

plugins {
    id("logxy.intellij-platform")
}

rootProject.name = "logxy"

include("core", "ui", "app", "tools", "plugin")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // IntelliJ Platform icons (Apache 2.0) are published here, not on Maven Central.
        maven("https://www.jetbrains.com/intellij-repository/releases") {
            content { includeGroup("com.jetbrains.intellij.platform") }
        }
    }
}
