import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a bundled library is missing from THIRD-PARTY-LICENSES.md, which names each by its `group:name` in
 * backticks. Projects of this build and file dependencies have no coordinates and are not checked.
 */
abstract class CheckThirdPartyLicenses : DefaultTask() {
    @get:Input
    abstract val modules: SetProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenses: RegularFileProperty

    /** Adds the libraries [configuration] resolves to, without its BOMs, which bundle nothing. */
    fun bundled(configuration: Configuration) {
        modules.addAll(
            configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
                artifacts.mapNotNull { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.moduleIdentifier }
                    .map { "${it.group}:${it.name}" }
            },
        )
    }

    @TaskAction
    fun check() {
        val file = licenses.get().asFile
        val text = file.readText()
        val missing = modules.get().filter { "`$it`" !in text }.sorted()
        if (missing.isNotEmpty()) {
            throw GradleException("Bundled but missing from ${file.name}: ${missing.joinToString()}")
        }
    }
}
