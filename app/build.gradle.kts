plugins {
    id("logxy.kotlin")
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.license.report)
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
    implementation(libs.slf4j.api)
    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j2.impl)
    runtimeOnly(libs.intellij.icons)
}

// zstd-jni loads a native library, which Java 24+ warns about, and future versions block, without this.
val nativeAccess = "--enable-native-access=ALL-UNNAMED"

application {
    mainClass = "io.github.rastislavpapp.logxy.app.MainKt"
    applicationDefaultJvmArgs = listOf(nativeAccess)
}

tasks.jar {
    manifest {
        attributes("Implementation-Title" to "logxy", "Implementation-Version" to version)
    }
}

// The licences of everything that ships in the jar, for the notices the libraries' licences require us to pass on.
licenseReport {
    configurations = arrayOf("runtimeClasspath")
    renderers = arrayOf(com.github.jk1.license.render.InventoryHtmlReportRenderer("third-party-licenses.html"))
    filters = arrayOf(com.github.jk1.license.filter.LicenseBundleNormalizer())
}

// The report plus the licence of the icons, whose POM names none, so the report lists them as Unknown.
val thirdPartyLicenses = tasks.register<Sync>("thirdPartyLicenses") {
    from(tasks.named("generateLicenseReport"))
    from("src/licenses")
    into(layout.buildDirectory.dir("third-party-licenses"))
}

// Only the new-UI icons are used; the icons jar holds all of IntelliJ's.
val intellijIcons = configurations.detachedConfiguration(dependencies.create(libs.intellij.icons.get()))

tasks.shadowJar {
    archiveFileName = "logxy.jar"
    // log4j-core is a multi-release jar; without this its Java 9+ classes are ignored.
    manifest { attributes("Multi-Release" to "true", "Enable-Native-Access" to "ALL-UNNAMED") }
    dependencies { exclude(dependency(libs.intellij.icons.get())) }
    // A local, so the lambda does not capture the build script, which the configuration cache rejects.
    val archives = objects.newInstance<Services>().archives
    from(intellijIcons.elements.map { jars -> jars.map { archives.zipTree(it) } }) { include("expui/**") }
    from(thirdPartyLicenses) { into("META-INF/third-party-licenses") }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
    // Duplicates keep the first copy, except files the transformers merge.
    mergeServiceFiles()
    filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

// Native app image with a bundled runtime, archived for the OS it is built on (Windows or Linux).
val platform = if (System.getProperty("os.name").startsWith("Windows")) "windows-x64" else "linux-x64"

val jpackageInput = tasks.register<Sync>("jpackageInput") {
    // jpackage bundles its whole input directory, so give it only the jar.
    from(tasks.shadowJar)
    into(layout.buildDirectory.dir("jpackage-input"))
}

val jpackage = tasks.register<Exec>("jpackage") {
    val image = layout.buildDirectory.dir("jpackage")
    val icon = file(if (platform.startsWith("windows")) "src/packaging/logxy.ico" else "src/packaging/logxy.png")
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }.get().metadata.installationPath
    inputs.files(jpackageInput, icon)
    outputs.dir(image)
    executable = jdk.file("bin/jpackage").asFile.path
    args(
        "--type", "app-image",
        "--name", "logxy",
        // jpackage only accepts numeric versions.
        "--app-version", version.toString().removeSuffix("-SNAPSHOT"),
        "--vendor", "Rastislav Papp",
        // Windows shows this as the process name in Task Manager.
        "--description", "logxy",
        "--icon", icon.path,
        "--input", jpackageInput.get().destinationDir.path,
        "--main-jar", "logxy.jar",
        "--main-class", application.mainClass.get(),
        "--add-modules", "java.base,java.desktop,java.logging,java.prefs,jdk.unsupported",
        // Twice the JVM default, so few users need a limit in Preferences, which costs a second JVM at every start.
        "--java-options", "-XX:MaxRAMPercentage=50",
        "--java-options", nativeAccess,
        "--dest", image.get().asFile.path,
    )
    // jpackage refuses to overwrite an existing image.
    val fs = objects.newInstance<Services>().fs
    doFirst { fs.delete { delete(image) } }
    if (platform.startsWith("windows")) {
        // By default the launcher restarts itself in a child process to put the app dir on PATH; logxy needs no DLLs from there.
        val cfg = image.get().file("logxy/app/logxy.cfg").asFile
        doLast { cfg.writeText(cfg.readText().replace("[Application]", "[Application]\nwin.norestart=true")) }
    }
}

fun AbstractArchiveTask.nativeArchive(extension: String) {
    from(jpackage)
    from(thirdPartyLicenses) { into("third-party-licenses") }
    archiveFileName = "logxy-$version-$platform.$extension"
    destinationDirectory = layout.buildDirectory.dir("dist")
}

if (platform.startsWith("windows")) {
    tasks.register<Zip>("nativeArchive") { nativeArchive("zip") }
} else {
    // A tarball keeps the launcher executable.
    tasks.register<Tar>("nativeArchive") {
        compression = Compression.GZIP
        nativeArchive("tar.gz")
    }
}

interface Services {
    @get:Inject val archives: ArchiveOperations
    @get:Inject val fs: FileSystemOperations
}
