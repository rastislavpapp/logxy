import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

/**
 * The Kotlin API level of what the IntelliJ plugin bundles: the IDE provides the stdlib, and the oldest supported one
 * (2026.1) ships 2.3, so code built against a newer API may call what is not there.
 */
val PLUGIN_KOTLIN_API = KotlinVersion.KOTLIN_2_3
