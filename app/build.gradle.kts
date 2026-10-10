plugins {
    id("logxy.kotlin")
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.flatlaf)
    implementation(libs.flatlaf.extras)
    implementation(libs.flatlaf.intellij.themes)
    implementation(libs.flatlaf.fonts.inter)
    implementation(libs.flatlaf.fonts.jetbrains.mono)
    implementation(libs.miglayout.swing)
    implementation(libs.rsyntaxtextarea)
    implementation(libs.autocomplete)
    runtimeOnly(libs.intellij.icons)
}

application {
    mainClass = "io.github.rastislavpapp.logxy.app.MainKt"
}

tasks.jar {
    manifest {
        attributes("Implementation-Title" to "logxy", "Implementation-Version" to version)
    }
}

// Only the new-UI icons are used; the icons jar holds all of IntelliJ's.
val intellijIcons = configurations.detachedConfiguration(dependencies.create(libs.intellij.icons.get()))

tasks.shadowJar {
    archiveFileName = "logxy.jar"
    dependencies { exclude(dependency(libs.intellij.icons.get())) }
    // A local, so the lambda does not capture the build script, which the configuration cache rejects.
    val archives = objects.newInstance<Services>().archives
    from(intellijIcons.elements.map { jars -> jars.map { archives.zipTree(it) } }) { include("expui/**") }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
    // Duplicates keep the first copy, except files the transformers merge.
    mergeServiceFiles()
    filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

interface Services {
    @get:Inject val archives: ArchiveOperations
}
