plugins {
    id("logxy.kotlin")
    `java-library`
}

// Experiment: VectorNewlineScanner uses the incubating Vector API.
kotlin {
    compilerOptions.freeCompilerArgs.add("-Xadd-modules=jdk.incubator.vector")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.zstd.jni)
}
