rootProject.name = "logxy"

include("core", "app", "tools")

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
