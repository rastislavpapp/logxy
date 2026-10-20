// The Swing views the app and the IntelliJ plugin share. The plugin runs them under IntelliJ's look and feel and
// with the IDE's coroutines, so this module names neither FlatLaf nor kotlinx-coroutines-swing (see detekt.yml).
plugins {
    id("logxy.kotlin")
    `java-library`
}

kotlin {
    compilerOptions.apiVersion = PLUGIN_KOTLIN_API
}

detekt {
    config.from(file("detekt.yml"))
}

tasks.test {
    // The views are made in tests, without a screen.
    systemProperty("java.awt.headless", "true")
}

dependencies {
    api(project(":core"))
    api(libs.miglayout.swing)
    implementation(libs.slf4j.api)
}
