import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformRepositoriesExtension

// The IDE distributions and the repositories the IntelliJ Platform plugin needs for the plugin module. A settings
// plugin of its own, so the platform plugin's version comes from the version catalog like the others.
plugins {
    id("org.jetbrains.intellij.platform.settings")
}

dependencyResolutionManagement {
    repositories {
        // No accessor is generated for a settings plugin's extension in a precompiled script.
        (this as ExtensionAware).extensions.configure<IntelliJPlatformRepositoriesExtension>("intellijPlatform") {
            defaultRepositories()
        }
    }
}
