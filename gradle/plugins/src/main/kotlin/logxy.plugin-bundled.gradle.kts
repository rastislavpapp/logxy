import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// A module the IntelliJ plugin bundles, built for the oldest supported IDE (2025.2): it runs on that IDE's JBR 21
// and with its Kotlin stdlib, 2.2, so code built for a newer JDK or against a newer Kotlin API would not load or link.
// Moving that IDE moves these, `intellij-idea` and `pycharm` in the version catalog and the plugin's `sinceBuild`.
plugins {
    id("logxy.kotlin")
}

val oldestIdeJdk = 21

kotlin {
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_2_2
        // The JDK 25 toolchain still compiles; this rejects its APIs that the older JDK lacks.
        freeCompilerArgs.add("-Xjdk-release=$oldestIdeJdk")
    }
}

// Set on the tasks, not on `kotlin`: the IntelliJ Platform plugin gives each task a default from the toolchain, 25,
// which takes precedence over the extension's.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget = JvmTarget.fromTarget(oldestIdeJdk.toString())
}

// The Kotlin plugin fails the build when the Java tasks' target differs from Kotlin's.
tasks.withType<JavaCompile>().configureEach {
    options.release = oldestIdeJdk
}
