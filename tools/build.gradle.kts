plugins {
    id("logxy.kotlin")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.jsvg)
}

application {
    mainClass = "io.github.rastislavpapp.logxy.tools.ToolsKt"
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
    description = "Fails if parsing, filtering or merging got slower than the saved baseline."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("benchmark") }
    workingDir = rootDir
    maxHeapSize = "8g"
    outputs.upToDateWhen { false }
    providers.gradleProperty("baselineTolerance").orNull?.let { systemProperty("baseline.tolerance", it) }
    // -PbaselineRecord writes the rows the baseline lacks into it instead of comparing.
    if (providers.gradleProperty("baselineRecord").isPresent) systemProperty("baseline.record", "true")
    // The comparison table is the result, so it shows also with -q, as AGENTS.md runs it.
    testLogging.showStandardStreams = true
    testLogging.quiet.showStandardStreams = true
}
