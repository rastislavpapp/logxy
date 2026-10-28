plugins {
    id("logxy.kotlin")
    // Runs JMH's annotation processor, which generates the code that runs the @Benchmark methods.
    // In the Kotlin Gradle plugin, which the convention plugins already put on the classpath.
    id("org.jetbrains.kotlin.kapt")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.jsvg)
    // The app's fonts, for the splash screen.
    implementation(libs.flatlaf.fonts.inter)
    implementation(libs.flatlaf.fonts.jetbrains.mono)
    // core logs, but the tools print their own output: without a provider, SLF4J warns in every JVM.
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.jmh.core)
    kapt(libs.jmh.generator.annprocess)
}

// JMH reads field offsets through sun.misc.Unsafe, which JDK 25 warns about in every JVM; its forks inherit these.
val jmhJvmArgs = listOf(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
    "--add-modules",
    VECTOR_MODULE,
)

application {
    mainClass = "io.github.rastislavpapp.logxy.tools.ToolsKt"
    applicationDefaultJvmArgs = jmhJvmArgs
}

// Paths given in --args are relative to the project root.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

// The speed regression check is slow and machine-specific: not part of `test`, run by `benchmark`.
tasks.test {
    useJUnitPlatform { excludeTags("benchmark") }
}

tasks.register<Test>("benchmark") {
    description = "Fails if indexing, parsing, filtering or merging got slower than the saved baseline."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("benchmark") }
    workingDir = rootDir
    maxHeapSize = "8g"
    jvmArgs(jmhJvmArgs)
    outputs.upToDateWhen { false }
    providers.gradleProperty("baselineTolerance").orNull?.let { systemProperty("baseline.tolerance", it) }
    // JVMs per row, 3 by default; recording a baseline uses the same.
    providers.gradleProperty("benchmarkForks").orNull?.let { systemProperty("benchmark.forks", it) }
    // -PbenchmarkOnly=<row> measures and compares that row alone, by the name the check prints.
    providers.gradleProperty("benchmarkOnly").orNull?.let { systemProperty("benchmark.only", it) }
    // -PbaselineRecord writes the rows the baseline lacks into it instead of comparing.
    if (providers.gradleProperty("baselineRecord").isPresent) systemProperty("baseline.record", "true")
    // The comparison table is the result, so it shows also with -q, as AGENTS.md runs it.
    testLogging.showStandardStreams = true
    testLogging.quiet.showStandardStreams = true
}
