package ai.unifiedprocess.tools.ij

import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import net.sourceforge.plantuml.error.PSystemError
import net.sourceforge.plantuml.preproc.Defines
import java.io.ByteArrayOutputStream

/**
 * Renders PlantUML sources to PNG for the activity diagram tool window. The rendering
 * happens in-process with the MIT-licensed PlantUML library and the pure-Java Smetana
 * layout engine, so no external render service is called, the diagram source never
 * leaves the IDE and no local Graphviz installation is required.
 */
object ActivityDiagramRenderer {

    init {
        // the preprocessor must not reach local files or URLs from spec content
        System.setProperty("PLANTUML_SECURITY_PROFILE", "SANDBOX")
    }

    /**
     * The layout runs with the embedded Smetana engine instead of a local Graphviz
     * installation; the pragma is injected as configuration so the generated source
     * stays a plain diagram.
     */
    private val CONFIG = listOf("!pragma layout smetana")

    sealed interface Rendering {

        /** A blank source: the spec has no scenario steps yet, the preview stays empty. */
        object Empty : Rendering

        class Image(val png: ByteArray) : Rendering

        class Error(val message: String) : Rendering
    }

    fun render(source: String): Rendering {
        if (source.isBlank()) {
            return Rendering.Empty
        }
        return try {
            val reader = SourceStringReader(Defines.createEmpty(), source, Charsets.UTF_8.name(), CONFIG)
            val blocks = reader.blocks
            if (blocks.isEmpty()) {
                return Rendering.Error("No @startuml/@enduml found")
            }
            val diagram = blocks[0].diagram
            if (diagram is PSystemError) {
                return Rendering.Error(diagram.firstError?.error ?: "Syntax error")
            }
            val output = ByteArrayOutputStream()
            val description = reader.outputImage(output, FileFormatOption(FileFormat.PNG))
            if (description == null) {
                Rendering.Error("No diagram found")
            } else {
                Rendering.Image(output.toByteArray())
            }
        } catch (e: Exception) {
            Rendering.Error(e.message ?: e.javaClass.simpleName)
        }
    }
}
