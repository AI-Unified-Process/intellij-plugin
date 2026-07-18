package ai.unifiedprocess.tools.ij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDiagramTest {

    private val spec = """
        # Use Case: Vision bearbeiten

        ## Overview

        **Use Case ID:** UC-005
        **Status:** Done

        ## Preconditions

        - Der Benutzer ist angemeldet.

        ## Main Success Scenario

        1. Benutzer öffnet die Vision.
        2. System lädt den Dateiinhalt vom Git-Anbieter
           und zeigt ihn im Editor an.
        3. Benutzer speichert die Datei (Ctrl+S).

        ## Alternative Flows

        ### A1: Datei nicht lesbar

        **Trigger:** Das Markdown ist nicht lesbar (Schritt 2)
        **Flow:**

        1. System zeigt eine Fehlermeldung.
        2. Use Case endet.

        ### A2: Ohne Schrittreferenz

        **Trigger:** Etwas anderes passiert (UC-012 Schritt 8)
        **Flow:**

        1. System reagiert.

        ## Postconditions

        ### Success Postconditions

        - Die Datei ist gespeichert.
    """.trimIndent()

    @Test
    fun parseReadsMainScenarioWithJoinedContinuationLines() {
        val scenario = ActivityDiagram.parse(spec)

        assertEquals(3, scenario.mainSteps.size)
        assertEquals(
            "System lädt den Dateiinhalt vom Git-Anbieter und zeigt ihn im Editor an.",
            scenario.mainSteps[1],
        )
    }

    @Test
    fun parseReadsAlternativeFlowsWithTriggerAndSteps() {
        val scenario = ActivityDiagram.parse(spec)

        assertEquals(2, scenario.flows.size)
        assertEquals("Datei nicht lesbar", scenario.flows[0].title)
        assertEquals("Das Markdown ist nicht lesbar (Schritt 2)", scenario.flows[0].trigger)
        assertEquals(listOf("System zeigt eine Fehlermeldung.", "Use Case endet."), scenario.flows[0].steps)
    }

    @Test
    fun parseSupportsGermanMainScenarioHeading() {
        val scenario = ActivityDiagram.parse("## Hauptszenario\n\n1. Erster Schritt.\n")

        assertEquals(listOf("Erster Schritt."), scenario.mainSteps)
    }

    @Test
    fun generateBranchesFlowAtReferencedStep() {
        val source = ActivityDiagram.generate(ActivityDiagram.parse(spec))

        val step2 = source.indexOf(":2. System lädt")
        val branch = source.indexOf("if (A1:")
        val step3 = source.indexOf(":3. Benutzer speichert")
        assertTrue("A1 must branch after step 2 and before step 3", step2 < branch && branch < step3)
    }

    @Test
    fun generatePutsFlowWithoutStepReferenceAfterLastStep() {
        val source = ActivityDiagram.generate(ActivityDiagram.parse(spec))

        // the UC-012 reference is not a step of this use case, so A2 branches at the end
        val step3 = source.indexOf(":3. Benutzer speichert")
        val branch = source.indexOf("if (A2:")
        val stop = source.indexOf("stop")
        assertTrue("A2 must branch after the last step", step3 < branch && branch < stop)
    }

    @Test
    fun generateSanitizesLabels() {
        val source = ActivityDiagram.generate(ActivityDiagram.parse(spec))

        assertTrue(source.contains(":3. Benutzer speichert die Datei [Ctrl+S].;"))
        assertTrue(source.contains("if (A1: Das Markdown ist nicht lesbar [Schritt 2]) then (A1)"))
    }

    @Test
    fun generateReturnsEmptySourceWithoutMainScenarioSteps() {
        assertEquals("", ActivityDiagram.generate(ActivityDiagram.parse("# Use Case: Leer\n")))
    }

    @Test
    fun renderProducesPngForGeneratedSource() {
        val source = ActivityDiagram.generate(ActivityDiagram.parse(spec))

        val rendering = ActivityDiagramRenderer.render(source)

        assertTrue("expected an image, got $rendering", rendering is ActivityDiagramRenderer.Rendering.Image)
        val png = (rendering as ActivityDiagramRenderer.Rendering.Image).png
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
    }

    @Test
    fun renderReportsSyntaxErrors() {
        val rendering = ActivityDiagramRenderer.render("@startuml\nthis is not plantuml\n@enduml\n")

        assertTrue(rendering is ActivityDiagramRenderer.Rendering.Error)
    }

    @Test
    fun renderStaysEmptyForBlankSource() {
        assertTrue(ActivityDiagramRenderer.render("") is ActivityDiagramRenderer.Rendering.Empty)
    }
}
