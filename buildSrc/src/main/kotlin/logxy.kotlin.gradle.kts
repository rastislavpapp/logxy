// Shared setup for every module.
plugins {
    id("org.jetbrains.kotlin.jvm")
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
}

tasks.test {
    useJUnitPlatform()
}
