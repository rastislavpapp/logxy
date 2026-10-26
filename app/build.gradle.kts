import org.codehaus.plexus.util.Os

plugins {
    id("logxy.kotlin")
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":ui"))
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
    applicationDefaultJvmArgs = listOf(nativeAccess, "--add-modules", VECTOR_MODULE)
}

// As a resource, so it reaches the fat jar ahead of the libraries' META-INF/LICENSE files: the first copy wins there.
tasks.processResources {
    from(rootProject.files("LICENSE", "THIRD-PARTY-LICENSES.md")) { into("META-INF") }
}

val checkThirdPartyLicenses = tasks.register<CheckThirdPartyLicenses>("checkThirdPartyLicenses") {
    bundled(configurations.runtimeClasspath.get())
    licenses = rootProject.layout.projectDirectory.file("THIRD-PARTY-LICENSES.md")
}

tasks.check { dependsOn(checkThirdPartyLicenses) }

tasks.jar {
    manifest {
        attributes("Implementation-Title" to "logxy", "Implementation-Version" to version)
    }
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
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
    // Duplicates keep the first copy, except files the transformers merge.
    mergeServiceFiles()
    filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

val windows = Os.isFamily(Os.FAMILY_WINDOWS)

// Shipped twice: as the defaults, and as the file the launcher hands the JVM, which the app overwrites with the
// user's options from the settings directory (see VmOptions). The defaults double the JVM's heap limit, so few
// users need to set one in Preferences. The JVM rejects comments in the file, so they are here.
val vmOptions = file("src/packaging/logxy.default.vmoptions")

val jpackageInput = tasks.register<Sync>("jpackageInput") {
    // jpackage bundles its whole input directory into the app directory, so give it only what belongs there.
    from(tasks.shadowJar)
    from(vmOptions)
    from(vmOptions) { rename { "logxy.vmoptions" } }
    // The JVM picks splash@150pct.gif and the like itself on a scaled screen; `tools splash` renders them.
    from("src/packaging") { include("splash*.gif") }
    into(layout.buildDirectory.dir("jpackage-input"))
}

val jpackage = tasks.register<Exec>("jpackage") {
    val image = layout.buildDirectory.dir("jpackage")
    val icon = file(if (windows) "src/packaging/logxy.ico" else "src/packaging/logxy.png")
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
        "--add-modules", "java.base,java.desktop,java.logging,java.prefs,jdk.unsupported,$VECTOR_MODULE",
        // The JVM does not start without the file; the property tells the app where it is.
        "--java-options", "-XX:VMOptionsFile=\$APPDIR/logxy.vmoptions",
        "--java-options", "-Dlogxy.vmoptions=\$APPDIR/logxy.vmoptions",
        "--java-options", nativeAccess,
        "--java-options", "--add-modules=$VECTOR_MODULE",
        // Drawn natively before the JVM loads any class, so something shows at once; it closes with the first window.
        "--java-options", "-splash:\$APPDIR/splash.gif",
        "--dest", image.get().asFile.path,
    )
    // jpackage refuses to overwrite an existing image.
    val fs = objects.newInstance<Services>().fs
    doFirst { fs.delete { delete(image) } }
    if (windows) {
        // By default the launcher restarts itself in a child process to put the app dir on PATH; logxy needs no DLLs from there.
        val cfg = image.get().file("logxy/app/logxy.cfg").asFile
        doLast { cfg.writeText(cfg.readText().replace("[Application]", "[Application]\nwin.norestart=true")) }
    }
}

fun AbstractArchiveTask.nativeArchive(extension: String) {
    from(jpackage)
    from(rootProject.files("LICENSE", "THIRD-PARTY-LICENSES.md")) { into("logxy") }
    val platform = if (windows) "windows-x64" else "linux-x64"
    archiveFileName = "logxy-$version-$platform.$extension"
    destinationDirectory = layout.buildDirectory.dir("dist")
}

if (windows) {
    tasks.register<Zip>("nativeArchive") { nativeArchive("zip") }
} else {
    // A tarball keeps the launcher executable, and jspawnhelper, which the JVM starts processes with, as the image
    // has them; Gradle's reproducible default would make every file 644.
    tasks.register<Tar>("nativeArchive") {
        useFileSystemPermissions()
        compression = Compression.GZIP
        nativeArchive("tar.gz")
    }
}

interface Services {
    @get:Inject val archives: ArchiveOperations
    @get:Inject val fs: FileSystemOperations
}
