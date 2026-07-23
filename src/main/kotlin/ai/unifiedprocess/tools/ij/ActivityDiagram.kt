package ai.unifiedprocess.tools.ij

/**
 * Generates the PlantUML activity diagram of a Use Case spec from its Main Success
 * Scenario and Alternative Flows: the main scenario is the numbered spine, every
 * alternative flow branches at the step its trigger references and rejoins the flow
 * after its own steps.
 *
 * This is a Kotlin port of the AIUP Studio's `ActivityDiagram` generator, fed by a
 * lenient Markdown reader instead of the Studio's strict parser — the preview should
 * render whatever it can read, not reject the file. The diagram is a view only; the
 * Markdown spec stays the single source of truth.
 */
object ActivityDiagram {

    data class AlternativeFlow(
        val title: String,
        val trigger: String,
        val steps: List<String>,
        /** The flow's own code from its heading (`A1`, `3a`), if it has one. */
        val code: String? = null,
        /** The branch step encoded in a step-coded heading like `3a.`, if any. */
        val branchStep: Int? = null,
    )

    data class Scenario(val mainSteps: List<String>, val flows: List<AlternativeFlow>)

    private val MAIN_HEADING =
        Regex(
            """#{2,6}\s+(?:Main\s+Success\s+Scenario|Hauptszenario|Hauptablauf)\s*""",
            RegexOption.IGNORE_CASE,
        )

    private val FLOWS_HEADING =
        Regex(
            """#{2,6}\s+(?:Alternative\s+Flows|Alternativszenarien|Alternative\s+Abläufe|Alternativabläufe)\s*""",
            RegexOption.IGNORE_CASE,
        )

    /** A flow heading, e.g. `### A1: Neues Diagramm`; the label part is optional. */
    private val FLOW_HEADING = Regex("""#{3,6}\s+(.*)""")

    private val FLOW_LABEL = Regex("""(A\d+)\s*:\s*(.*)""")

    /**
     * A step-coded flow heading in the German spec style, e.g.
     * `3a. Keine Treffer gefunden`: the digits name the main-scenario step the
     * flow branches at, digits+letter are the flow's code.
     */
    private val STEP_CODED_FLOW_LABEL = Regex("""(\d+)([a-z])\s*[.:)]?\s*(.*)""")

    private val NUMBERED_ITEM = Regex("""(\d+)\.\s+(.*)""")

    /**
     * A step reference in a trigger, e.g. `(Schritt 5)` or `(step 5)`; a reference into
     * another use case like `UC-012 Schritt 8` names that use case right before the word
     * and is not a branch point of this diagram.
     */
    private val STEP_REFERENCE = Regex("""([SB]?UC-\d+\s+)?(?:Schritt|Step|schritt|step)\s+(\d+)""")

    private const val TRIGGER_FIELD = "**Trigger:**"

    private const val FLOW_FIELD = "**Flow:**"

    /** The width the step and trigger texts are wrapped at, so the diagram stays legible. */
    private const val WRAP_WIDTH = 48

    /**
     * Reads the Main Success Scenario steps and the Alternative Flows from the spec
     * Markdown. Lenient by design: unknown lines are skipped, wrapped lines are joined
     * into the item they belong to.
     */
    fun parse(markdown: String): Scenario {
        val lines = markdown.lines().map { it.trimEnd() }
        val mainSteps = mutableListOf<String>()
        val flows = mutableListOf<AlternativeFlow>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            index = when {
                MAIN_HEADING.matches(line) -> parseNumberedItems(lines, index + 1, mainSteps)
                FLOWS_HEADING.matches(line) -> parseFlows(lines, index + 1, flows)
                else -> index + 1
            }
        }
        return Scenario(mainSteps, flows)
    }

    /** The PlantUML source; empty while the main scenario has no steps yet. */
    fun generate(scenario: Scenario): String {
        if (scenario.mainSteps.isEmpty()) {
            return ""
        }
        val flowsByStep = flowsByStep(scenario)
        val builder = StringBuilder("@startuml\nstart\n")
        for (stepIndex in scenario.mainSteps.indices) {
            builder.append(':').append(label("${stepIndex + 1}. ${scenario.mainSteps[stepIndex]}")).append(";\n")
            for (flow in flowsByStep[stepIndex]) {
                appendBranch(builder, scenario, flow)
            }
        }
        builder.append("stop\n@enduml\n")
        return builder.toString()
    }

    private fun parseNumberedItems(lines: List<String>, start: Int, items: MutableList<String>): Int {
        var index = start
        while (index < lines.size && !lines[index].startsWith("#")) {
            val line = lines[index]
            val match = NUMBERED_ITEM.matchEntire(line.trim())
            if (match != null && !line.startsWith(" ")) {
                val item = StringBuilder(match.groupValues[2].trim())
                index = joinContinuation(lines, index + 1, item)
                items.add(item.toString())
            } else {
                index++
            }
        }
        return index
    }

    private fun parseFlows(lines: List<String>, start: Int, flows: MutableList<AlternativeFlow>): Int {
        var index = start
        while (index < lines.size && !lines[index].startsWith("## ")) {
            val heading = FLOW_HEADING.matchEntire(lines[index])
            if (heading == null) {
                index++
                continue
            }
            val headingText = heading.groupValues[1].trim()
            var code: String? = null
            var branchStep: Int? = null
            var title = headingText
            val label = FLOW_LABEL.matchEntire(headingText)
            val stepCoded = STEP_CODED_FLOW_LABEL.matchEntire(headingText)
            if (label != null) {
                code = label.groupValues[1]
                title = label.groupValues[2].trim()
            } else if (stepCoded != null) {
                branchStep = stepCoded.groupValues[1].toIntOrNull()
                code = stepCoded.groupValues[1] + stepCoded.groupValues[2]
                title = stepCoded.groupValues[3].trim().ifEmpty { headingText }
            }
            index++
            while (index < lines.size && lines[index].isBlank()) {
                index++
            }
            var trigger = ""
            if (index < lines.size && lines[index].startsWith(TRIGGER_FIELD)) {
                val text = StringBuilder(lines[index].substring(TRIGGER_FIELD.length).trim())
                index = joinContinuation(lines, index + 1, text)
                trigger = text.toString()
            }
            if (index < lines.size && lines[index].trim() == FLOW_FIELD) {
                index++
            }
            val steps = mutableListOf<String>()
            index = parseNumberedItems(lines, index, steps)
            if (trigger.isNotEmpty() || steps.isNotEmpty()) {
                flows.add(AlternativeFlow(title, trigger, steps, code, branchStep))
            }
        }
        return index
    }

    /**
     * Wrapped lines are joined into the current item: everything up to the next blank
     * line, structure marker, sub-bullet or heading belongs to the item, indented or
     * not, so hand-formatted files stay readable. Sub-bullets (`- …` / `* …` under a
     * numbered step) are detail, not part of the step label, and are skipped.
     */
    private fun joinContinuation(lines: List<String>, start: Int, item: StringBuilder): Int {
        var index = start
        while (index < lines.size && isContinuation(lines[index])) {
            item.append(' ').append(lines[index].trim())
            index++
        }
        return index
    }

    private fun isContinuation(line: String): Boolean {
        val trimmed = line.trimStart()
        if (line.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("**") ||
            trimmed.startsWith("- ") || trimmed.startsWith("* ")
        ) {
            return false
        }
        return !NUMBERED_ITEM.matches(line.trim()) || line.startsWith(" ")
    }

    /**
     * Every alternative flow branches at the step its heading code (`3a` -> step 3)
     * or its trigger references; a flow that names no step of the scenario branches
     * after the last step.
     */
    private fun flowsByStep(scenario: Scenario): List<List<AlternativeFlow>> {
        val byStep = List(scenario.mainSteps.size) { mutableListOf<AlternativeFlow>() }
        for (flow in scenario.flows) {
            val step = flow.branchStep?.takeIf { it in 1..scenario.mainSteps.size }
                ?: referencedStep(flow.trigger, scenario.mainSteps.size)
            byStep[if (step == null) scenario.mainSteps.size - 1 else step - 1].add(flow)
        }
        return byStep
    }

    private fun referencedStep(trigger: String, stepCount: Int): Int? {
        for (match in STEP_REFERENCE.findAll(trigger)) {
            if (match.groupValues[1].isNotEmpty()) {
                // a step of another use case, not a branch point of this diagram
                continue
            }
            val step = match.groupValues[2].toInt()
            if (step in 1..stepCount) {
                return step
            }
        }
        return null
    }

    private fun appendBranch(builder: StringBuilder, scenario: Scenario, flow: AlternativeFlow) {
        val flowLabel = flow.code ?: "A${scenario.flows.indexOf(flow) + 1}"
        val condition = flow.trigger.ifBlank { flow.title }
        builder.append("if (").append(label("$flowLabel: $condition")).append(") then (").append(flowLabel)
            .append(")\n")
        for (stepIndex in flow.steps.indices) {
            builder.append("  :")
                .append(label("$flowLabel.${stepIndex + 1} ${flow.steps[stepIndex]}"))
                .append(";\n")
        }
        builder.append("endif\n")
    }

    /**
     * An activity label: parentheses and semicolons would end the PlantUML condition or
     * label early, so they are replaced; long texts wrap at word boundaries so the
     * diagram stays legible.
     */
    private fun label(text: String): String {
        val sanitized = text.replace('(', '[').replace(')', ']').replace(';', ',')
            .replace(Regex("\\s+"), " ").trim()
        val wrapped = StringBuilder()
        var lineLength = 0
        for (word in sanitized.split(' ')) {
            if (lineLength > 0 && lineLength + 1 + word.length > WRAP_WIDTH) {
                wrapped.append("\\n")
                lineLength = 0
            } else if (lineLength > 0) {
                wrapped.append(' ')
                lineLength++
            }
            wrapped.append(word)
            lineLength += word.length
        }
        return wrapped.toString()
    }
}
