// The IntelliJ plugin: a logxy view of a run's output in the Run tool window, from the shared views in `ui`.
plugins {
    id("logxy.kotlin")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    compilerOptions.apiVersion = PLUGIN_KOTLIN_API
}

// The views use the new-UI icons by their `expui/` paths, which the IDE's own jars do not have; only those are bundled.
val intellijIcons = configurations.detachedConfiguration(dependencies.create(libs.intellij.icons.get()))
val expuiIcons = tasks.register<Jar>("expuiIcons") {
    archiveBaseName = "logxy-expui-icons"
    destinationDirectory = layout.buildDirectory.dir("icons")
    // A local, so the lambda does not capture the build script, which the configuration cache rejects.
    val archives = objects.newInstance<Services>().archives
    from(intellijIcons.elements.map { jars -> jars.map { archives.zipTree(it) } }) { include("expui/**") }
}

// Tests run on the IDE's runtime, which lacks the Vector API module; core falls back without it, as in the IDE.
tasks.test {
    jvmArgs = jvmArgs.orEmpty() - listOf("--add-modules", VECTOR_MODULE)
}

dependencies {
    implementation(project(":ui"))
    implementation(files(expuiIcons))
    intellijPlatform {
        intellijIdea(libs.versions.intellij.idea)
    }
}

// The IDE provides the Kotlin stdlib, coroutines and SLF4J; second copies in the plugin would clash with them.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.slf4j")
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

interface Services {
    @get:Inject val archives: ArchiveOperations
}
