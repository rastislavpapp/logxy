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
