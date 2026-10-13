// Shared setup for every module.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("dev.detekt")
}

group = "io.github.rastislavpapp.logxy"

kotlin {
    jvmToolchain(25)
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
    detektPlugins(libs.findLibrary("detekt-rules-ktlint-wrapper").get())
}

detekt {
    // Only what differs from detekt's defaults.
    buildUponDefaultConfig = true
    config.setFrom(rootDir.resolve("config/detekt.yml"))
    ignoreFailures = true
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    // zstd-jni loads a native library, which Java 24+ warns about without this.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Failures show their assertion and stack trace in the console, also with -q (the quiet level).
    testLogging {
        for (level in listOf(lifecycle, quiet)) {
            level.events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
            level.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
