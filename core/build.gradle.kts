plugins {
    id("logxy.kotlin")
    `java-library`
}

kotlin {
    compilerOptions.freeCompilerArgs.add("-Xadd-modules=$VECTOR_MODULE")
}

tasks.test {
    useJUnitPlatform { excludeTags("without-vector-module") }
}

// The fallback a JVM without the module takes, as `java -jar logxy.jar` is started.
val testWithoutVectorModule = tasks.register<Test>("testWithoutVectorModule") {
    description = "Runs the tests of the newline search in a JVM without the Vector API module."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("without-vector-module") }
}

tasks.check {
    dependsOn(testWithoutVectorModule)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.zstd.jni)
}
