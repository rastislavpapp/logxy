// Spike (#86): the IntelliJ plugin showing the current LogTab in the Run tool window. Thrown away afterwards.
plugins {
    id("logxy.kotlin")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    implementation(project(":app"))
    intellijPlatform {
        intellijIdea(libs.versions.intellij.idea)
    }
}

// The IDE provides the Kotlin stdlib, coroutines and SLF4J; second copies in the plugin would clash with them.
// kotlinx-coroutines-swing is not in the IDE, so it stays.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.slf4j")
    exclude(group = "org.apache.logging.log4j")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}
