import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease.Channel
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

// The IntelliJ plugin: a logxy view of a run's output in the Run tool window, from the shared views in `ui`.
plugins {
    id("logxy.plugin-bundled")
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.changelog)
}

// The views use the new-UI icons by their `expui/` paths, which the IDE's own jars do not have; only those are bundled.
val intellijIcons = configurations.detachedConfiguration(dependencies.create(libs.intellij.icons.get()))
val expuiIcons = tasks.register<Jar>("expuiIcons") {
    archiveBaseName = "logxy-expui-icons"
    destinationDirectory = layout.buildDirectory.dir("icons")
    // A local, so the lambda does not capture the build script, which the configuration cache rejects.
    val archives = objects.newInstance<Services>().archives
    from(intellijIcons.elements.map { jars -> jars.map { archives.zipTree(it) } }) { include("expui/**") }
}

tasks.processResources {
    from(rootProject.files("LICENSE", "THIRD-PARTY-LICENSES.md")) { into("META-INF") }
}

val checkThirdPartyLicenses = tasks.register<CheckThirdPartyLicenses>("checkThirdPartyLicenses") {
    bundled(configurations.runtimeClasspath.get())
    bundled(intellijIcons)
    licenses = rootProject.layout.projectDirectory.file("THIRD-PARTY-LICENSES.md")
}

tasks.check { dependsOn(checkThirdPartyLicenses) }

// Tests run on the IDE's runtime, which lacks the Vector API module; core falls back without it, as in the IDE.
tasks.test {
    jvmArgs = jvmArgs.orEmpty() - listOf("--add-modules", VECTOR_MODULE)
}

dependencies {
    implementation(project(":ui"))
    implementation(files(expuiIcons))
    intellijPlatform {
        intellijIdea(libs.versions.intellij.idea)
        pluginVerifier(libs.versions.intellij.plugin.verifier)
    }
    // 2025.3's test framework starts with every JUnit 5 run and sets up a logger built on JUnit 4's rules.
    testRuntimeOnly(libs.junit4)
}

// The IDE provides the Kotlin stdlib, coroutines and SLF4J; second copies in the plugin would clash with them.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.slf4j")
}

// The plugin's own: the app keeps GitHub's generated release notes. Plain lists, without Added / Fixed groups.
changelog {
    groups.empty()
}

/** A release version's minor and patch: `"0.2"` and 1 for 0.2.1; null for a snapshot. */
fun minorAndPatch(version: String): Pair<String, Int>? {
    val match = Regex("""(\d+\.\d+)\.(\d+)""").matchEntire(version) ?: return null
    return match.groupValues[1] to match.groupValues[2].toInt()
}

// A release's notes are every section of its minor up to the release itself, newest first, so a fix release still
// shows what the minor added: 0.2.1's are its own entries, if it has a section, then 0.2.0's. Each section gets its
// version heading once there are several. A snapshot build's notes are the Unreleased section.
val releaseNotes = provider {
    with(changelog) {
        val release = minorAndPatch(version.get())
        val sections = if (release == null) {
            listOf(getUnreleased())
        } else {
            getAll().values.filter { item ->
                val section = minorAndPatch(item.version)
                section != null && section.first == release.first && section.second <= release.second
            }
        }
        val withHeaders = sections.size > 1
        sections.joinToString("\n") { section ->
            renderItem(section.withHeader(withHeaders).withEmptySections(false), Changelog.OutputType.HTML)
        }
    }
}

// The release workflow runs it before building anything: a release must not reach Marketplace with empty notes, as
// an X.Y.0 without entries would, nor leave entries under Unreleased that patchChangelog did not move into a section.
tasks.register("checkChangeNotes") {
    val notes = releaseNotes
    val unreleased = provider { changelog.getUnreleased().sections.values.any { it.isNotEmpty() } }
    doLast {
        if (unreleased.get()) {
            throw GradleException("CHANGELOG.md has entries under Unreleased: run patchChangelog for this version")
        }
        if (notes.get().isBlank()) {
            throw GradleException("CHANGELOG.md has no entries for this version's minor: the release notes are empty")
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        changeNotes = releaseNotes
        ideaVersion {
            // The build of the IDE it compiles against: 2025.3 is 253.
            sinceBuild = libs.versions.intellij.idea.map { version ->
                val (year, release) = version.split('.')
                year.takeLast(2) + release
            }
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
    // From the release workflow's secrets; CONTRIBUTING.md says how to make them. publishPlugin signs the zip first.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    // `untilBuild` is open, so every newer IDE counts as supported: verifyPlugin checks the binary against the oldest
    // and the latest release, or with -PverifyEap against the latest EAP alone. One IDE per run, picked with
    // -PverifyIde (IntelliJ IDEA by default), so CI verifies them in parallel.
    pluginVerification {
        ides {
            val eap = providers.gradleProperty("verifyEap").isPresent
            val ide = providers.gradleProperty("verifyIde").getOrElse("idea")
            val (type, oldest) = when (ide) {
                "idea" -> IntelliJPlatformType.IntellijIdea to null
                "pycharm" -> IntelliJPlatformType.PyCharm to libs.versions.pycharm
                else -> throw GradleException("-PverifyIde=$ide: expected idea or pycharm")
            }
            if (!eap) {
                // IntelliJ IDEA's oldest is the IDE the plugin compiles against.
                if (oldest == null) current() else create(type, oldest)
            }
            latest {
                types = listOf(type)
                channels = listOf(if (eap) Channel.EAP else Channel.RELEASE)
            }
        }
        // Deprecations are only reported: a newer IDE's replacement is often missing from the oldest supported one.
        failureLevel = FailureLevel.ALL - FailureLevel.DEPRECATED_API_USAGES
        // Online, the verifier downloads about 1 GB of Marketplace plugins per IDE that the IDE's own plugins depend
        // on; the plugin depends on the platform alone, so the verdicts are the same without them.
        freeArgs = listOf("-offline")
    }
}

// A copy of the Python demo for runPycharm to open, so PyCharm's saves stay out of the repository. Each run starts
// from the demo's files again; PyCharm's workspace.xml is kept.
val pycharmDemo = tasks.register<Copy>("pycharmDemo") {
    from("demo/python") { exclude("jdk.table.xml", ".idea/workspace.xml") }
    into(layout.buildDirectory.dir("pycharm-demo"))
}

// runIde's counterpart in PyCharm, for what runs differently there, such as its debugpy debugger. The oldest supported
// PyCharm unless -PpycharmVersion names another.
intellijPlatformTesting {
    runIde {
        register("runPycharm") {
            type = IntelliJPlatformType.PyCharm
            version = providers.gradleProperty("pycharmVersion").orElse(libs.versions.pycharm)
            task {
                dependsOn(pycharmDemo)
                val demo = layout.buildDirectory.dir("pycharm-demo")
                argumentProviders.add(CommandLineArgumentProvider { listOf(demo.get().asFile.absolutePath) })
                // The demo's interpreter, "Python 3" at /usr/bin/python3, joins the sandbox's own: without it the demo's
                // run configurations cannot run.
                val sdks = sandboxConfigDirectory.file("options/jdk.table.xml")
                val demoSdks = layout.projectDirectory.file("demo/python/jdk.table.xml")
                doFirst {
                    val table = sdks.get().asFile
                    val demoTable = demoSdks.asFile.readText()
                    if (!table.exists()) {
                        table.parentFile.mkdirs()
                        table.writeText(demoTable)
                    } else if ("<name value=\"Python 3\" />" !in table.readText()) {
                        val sdk = demoTable.substring(demoTable.indexOf("<jdk "), demoTable.lastIndexOf("</jdk>") + 6)
                        table.writeText(table.readText().replace("</component>", "  $sdk\n  </component>"))
                    }
                }
            }
        }
    }
}

interface Services {
    @get:Inject val archives: ArchiveOperations
}
