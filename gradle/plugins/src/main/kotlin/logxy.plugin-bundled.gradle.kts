import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// A module the IntelliJ plugin bundles, and the plugin itself: built for the oldest supported IDE, which runs it on
// its JBR and with its Kotlin stdlib, so code built for a newer JDK or against a newer Kotlin API would not load or
// link. Both versions come from the version catalog, next to that IDE's.
plugins {
    id("logxy.kotlin")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val oldestIdeJdk = libs.findVersion("oldest-ide-jdk").get().requiredVersion

kotlin {
    compilerOptions {
        apiVersion = KotlinVersion.fromVersion(libs.findVersion("oldest-ide-kotlin-api").get().requiredVersion)
        // The JDK 25 toolchain still compiles; this rejects its APIs that the older JDK lacks.
        freeCompilerArgs.add("-Xjdk-release=$oldestIdeJdk")
    }
}

// Set on the tasks, not on `kotlin`: the IntelliJ Platform plugin gives each task a default from the toolchain, 25,
// which takes precedence over the extension's.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget = JvmTarget.fromTarget(oldestIdeJdk)
}

// The Kotlin plugin fails the build when the Java tasks' target differs from Kotlin's.
tasks.withType<JavaCompile>().configureEach {
    options.release = oldestIdeJdk.toInt()
}
