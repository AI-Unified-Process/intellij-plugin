package ai.unifiedprocess.tools.ij

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

/**
 * Helpers to find Use Case specs and tests for a given ID.
 *
 * Convention:
 *  - Use Case IDs are `UC-XXX`, or the `SUC-XXX` / `BUC-XXX` variants
 *    (System / Business Use Case).
 *  - Spec files are Markdown files declaring the ID via the body line
 *    `**Use Case ID:** UC-XXX` or via the H1 title (`# UC-001: Kunde suchen`,
 *    the German AI Unified Process spec style). We also accept the ID in the file name as a
 *    fallback, optionally preceded by a project prefix and separated by `-`
 *    or `_` (e.g. `petclinic-UC-002-view.md`, `UC-032_Kunden_bearbeiten.md`).
 *  - Test methods are annotated with `@UseCase(id = "UC-XXX", ...)`.
 */
object UseCaseIndex {

    /**
     * Matches the `**Use Case ID:** UC-XXX` declaration line; shared by every
     * component that parses spec bodies so the accepted ID shapes stay in sync.
     */
    val USE_CASE_ID_LINE = Regex("""\*\*Use Case ID:\*\*\s*([SB]?UC-[A-Za-z0-9_-]+)""")

    /**
     * H1 title that declares the Use Case ID directly, e.g. `# UC-001: Kunde
     * suchen` — the German AI Unified Process spec style, which has no `**Use Case ID:**`
     * body line. Only consulted as a fallback when no body line exists.
     */
    val USE_CASE_TITLE = Regex("""^#[ \t]+([SB]?UC-[A-Za-z0-9_-]+)""", RegexOption.MULTILINE)

    /**
     * Heading of the main flow section: `Main Success Scenario` (English),
     * `Hauptszenario` or `Hauptablauf` (German).
     */
    val MAIN_SCENARIO_HEADING =
        Regex("""^#{1,6}\s+(?:Main\s+Success\s+Scenario|Hauptszenario|Hauptablauf)\s*$""")

    /** The labels accepted as the main flow in `@UseCase(scenario = ...)`. */
    val MAIN_SCENARIO_LABELS = listOf("Main Success Scenario", "Hauptszenario", "Hauptablauf")

    /**
     * Alternative-flow heading: `### A1: …` (letter-digit codes) or the
     * step-coded German style `### 3a. Keine Treffer gefunden`.
     */
    val ALT_FLOW_HEADING = Regex("""^#{1,6}\s+([A-Z]\d+|\d+[a-z])\b""")

    /**
     * A business-rule declaration site: a heading (`### BR-001 …`) or a bold
     * bullet item (`- **GR-008:** …`, the German spec style). Accepts the
     * `BR-` and `GR-` (Geschäftsregel) prefixes.
     */
    val BUSINESS_RULE_SITE = Regex("""^(?:#{1,6}\s+|\s*[-*]\s+\*\*)((?:BR|GR)-[A-Za-z0-9_-]+)\b""")

    /**
     * The code prefix of a scenario label: `A1` in "A1: Missing Description"
     * or the step-coded `3a` in "3a: Keine Treffer gefunden".
     */
    val SCENARIO_CODE = Regex("""[A-Z]\d+|\d+[a-z]""", RegexOption.IGNORE_CASE)

    /**
     * File names that identify a spec without reading it: `UC-XXX(-...)`,
     * `SUC-...`, `BUC-...`, each optionally preceded by an arbitrary
     * `<prefix>-` (e.g. `petclinic-UC-002-view-veterinarians`). The tail
     * accepts any letters (incl. umlauts), digits, `-` and `_`.
     */
    private val SPEC_FILE_NAME = Regex("""(?:.*-)?[SB]?UC-[\p{L}\p{N}_-]+""")

    /**
     * Finds all Markdown files in the project that declare the given Use Case ID.
     * Matches either the file name (e.g. UC-002-...md) or the
     * `**Use Case ID:** UC-XXX` line in the file body.
     */
    /**
     * Returns true if the project defines a `UseCase` annotation type.
     * Used by the startup probe to decide whether to suggest scaffolding it.
     */
    fun hasUseCaseAnnotation(project: Project): Boolean =
        findUseCaseAnnotationClass(project) != null

    /**
     * Returns true if the project contains at least one Markdown file that looks like
     * a Use Case spec — either with a name matching [SPEC_FILE_NAME] or declaring
     * a Use Case ID in its body. Short-circuits on the first match.
     */
    fun hasAnyUseCaseSpec(project: Project): Boolean {
        var found = false
        com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && file.extension == "md" && looksLikeUseCaseSpec(file)) {
                found = true
                false
            } else {
                true
            }
        }
        return found
    }

    /** True if the (extension-less) file name alone marks the file as a Use Case spec. */
    fun isSpecFileName(nameWithoutExtension: String): Boolean =
        SPEC_FILE_NAME.matches(nameWithoutExtension)

    private fun looksLikeUseCaseSpec(file: VirtualFile): Boolean {
        if (isSpecFileName(file.nameWithoutExtension)) return true
        return try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            findDeclaredUseCaseId(content) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The Use Case ID a spec body declares: the `**Use Case ID:** UC-XXX` line
     * takes precedence, the H1 title (`# UC-001: …`) is the fallback.
     */
    fun findDeclaredUseCaseId(content: String): String? =
        USE_CASE_ID_LINE.find(content)?.groupValues?.get(1)
            ?: USE_CASE_TITLE.find(content)?.groupValues?.get(1)

    fun findSpecFiles(project: Project, useCaseId: String): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && file.extension == "md" && matchesUseCase(file, useCaseId)) {
                result.add(file)
            }
            true
        }
        return result
    }

    private fun matchesUseCase(file: VirtualFile, useCaseId: String): Boolean {
        // Quick check: file name contains the ID at `-`/`_` boundaries, like
        // "UC-002-view-veterinarians.md" or "UC-032_Kunden_bearbeiten.md". The
        // leading boundary must be a literal `-` (or the name start) so that
        // e.g. UC-002 does not match a SUC-002 spec.
        val name = file.nameWithoutExtension
        if (Regex("""(?:.*-)?${Regex.escape(useCaseId)}(?:[-_].*)?""").matches(name)) {
            return true
        }

        // Content check
        return try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            findDeclaredUseCaseId(content) == useCaseId
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reads a Markdown file and returns the Use Case ID declared inside,
     * or null if no such declaration exists.
     */
    fun extractUseCaseId(file: VirtualFile): String? {
        return try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            findDeclaredUseCaseId(content)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Finds all test methods in the project annotated with `@UseCase`
     * whose `id` matches the given Use Case ID.
     */
    fun findTestMethods(project: Project, useCaseId: String): List<PsiMethod> {
        val annotationClass = findUseCaseAnnotationClass(project) ?: return emptyList()
        val scope = GlobalSearchScope.projectScope(project)

        val annotated = AnnotatedElementsSearch
            .searchPsiMethods(annotationClass, scope)
            .findAll()

        return annotated.filter { method ->
            val annotation = method.getAnnotation(annotationClass.qualifiedName!!) ?: return@filter false
            getStringAttribute(annotation, "id") == useCaseId
        }
    }

    /**
     * Finds test methods scoped to a specific Use Case that reference the given
     * Business Rule ID via the `businessRules` attribute. BR ids are only unique
     * within a Use Case (e.g. BR-001 exists in many UCs), so callers must
     * disambiguate by UC.
     */
    fun findTestMethodsForBusinessRule(
        project: Project,
        useCaseId: String,
        businessRuleId: String,
    ): List<PsiMethod> {
        return testMethodsMatching(project, useCaseId) { ann ->
            getStringArrayAttribute(ann, "businessRules").contains(businessRuleId)
        }
    }

    /**
     * Distinct test classes that contain at least one `@UseCase(id = useCaseId)` method.
     */
    fun findTestClasses(project: Project, useCaseId: String): List<PsiClass> {
        return findTestMethods(project, useCaseId)
            .mapNotNull { it.containingClass }
            .distinct()
    }

    /**
     * Test methods for the Main Success Scenario of a UC: those whose `scenario`
     * attribute is missing, blank, or literally "Main Success Scenario" /
     * "Hauptszenario" (German equivalent).
     */
    fun findTestMethodsForMainScenario(project: Project, useCaseId: String): List<PsiMethod> {
        return testMethodsMatching(project, useCaseId) { ann ->
            val scenario = getStringAttribute(ann, "scenario")
            scenario.isNullOrBlank() ||
                isMainScenarioLabel(scenario) ||
                scenarioPrefix(scenario) == null
        }
    }

    /**
     * Test methods for an alternative-flow code (e.g. "A1", "A2"): those whose
     * `scenario` attribute starts with that code (`A1: …` or just `A1`).
     */
    fun findTestMethodsForScenario(project: Project, useCaseId: String, scenarioCode: String): List<PsiMethod> {
        return testMethodsMatching(project, useCaseId) { ann ->
            val scenario = getStringAttribute(ann, "scenario") ?: return@testMethodsMatching false
            scenarioPrefix(scenario)?.equals(scenarioCode, ignoreCase = true) == true
        }
    }

    /**
     * Resolve the PSI leaves in the spec(s) that an `@UseCase` annotation
     * "points at": the scenario heading (Main Success Scenario or `### A1:`),
     * plus one leaf per business rule heading.
     */
    fun findSpecLeavesForAnnotation(project: Project, annotation: PsiAnnotation): List<PsiElement> {
        val useCaseId = getStringAttribute(annotation, "id") ?: return emptyList()
        val scenario = getStringAttribute(annotation, "scenario")
        val brIds = getStringArrayAttribute(annotation, "businessRules")

        val scenarioCode = scenario
            ?.takeIf { it.isNotBlank() && !isMainScenarioLabel(it) }
            ?.let { scenarioPrefix(it) }

        val result = mutableListOf<PsiElement>()
        findScenarioLeaf(project, useCaseId, scenarioCode)?.let(result::add)
        for (br in brIds) {
            findBusinessRuleLeaf(project, useCaseId, br)?.let(result::add)
        }
        return result.distinct()
    }

    private fun testMethodsMatching(
        project: Project,
        useCaseId: String,
        extra: (PsiAnnotation) -> Boolean,
    ): List<PsiMethod> {
        val annotationClass = findUseCaseAnnotationClass(project) ?: return emptyList()
        val fqn = annotationClass.qualifiedName ?: return emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        val annotated = AnnotatedElementsSearch.searchPsiMethods(annotationClass, scope).findAll()
        return annotated.filter { method ->
            val ann = method.getAnnotation(fqn) ?: return@filter false
            getStringAttribute(ann, "id") == useCaseId && extra(ann)
        }
    }

    /**
     * Extracts the alt-flow code prefix from a scenario value, e.g.
     * "A1: Missing Description" -> "A1" or "3a: Keine Treffer" -> "3a".
     * Returns null if the value doesn't follow either code form.
     */
    fun scenarioPrefix(scenario: String): String? {
        val colon = scenario.indexOf(':')
        val prefix = (if (colon >= 0) scenario.substring(0, colon) else scenario).trim()
        return prefix.takeIf { it.matches(SCENARIO_CODE) }
    }

    private fun findScenarioLeaf(project: Project, useCaseId: String, scenarioCode: String?): PsiElement? {
        val pattern = if (scenarioCode == null) {
            MAIN_SCENARIO_HEADING
        } else {
            Regex("""^#{1,6}\s+${Regex.escape(scenarioCode)}\b""", RegexOption.IGNORE_CASE)
        }
        return findHeadingLeaf(project, useCaseId, pattern)
    }

    fun isMainScenarioLabel(value: String): Boolean =
        MAIN_SCENARIO_LABELS.any { value.equals(it, ignoreCase = true) }

    fun findBusinessRuleLeaf(project: Project, useCaseId: String, brId: String): PsiElement? {
        // Both declaration styles: heading (`### BR-001 …`) and bold bullet
        // item (`- **GR-008:** …`).
        val pattern = Regex("""^(?:#{1,6}\s+|\s*[-*]\s+\*\*)${Regex.escape(brId)}\b""")
        return findHeadingLeaf(project, useCaseId, pattern)
    }

    private fun findHeadingLeaf(project: Project, useCaseId: String, pattern: Regex): PsiElement? {
        val specs = findSpecFiles(project, useCaseId)
        for (spec in specs) {
            val psiFile = PsiManager.getInstance(project).findFile(spec) ?: continue
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: continue
            for (i in 0 until document.lineCount) {
                val start = document.getLineStartOffset(i)
                val end = document.getLineEndOffset(i)
                val lineText = document.getText(TextRange(start, end))
                if (pattern.containsMatchIn(lineText)) {
                    return psiFile.findElementAt(start) ?: psiFile
                }
            }
        }
        return null
    }

    private fun findUseCaseAnnotationClass(project: Project): com.intellij.psi.PsiClass? {
        val scope = GlobalSearchScope.allScope(project)
        // Annotation lives in a project-specific package, so we look it up
        // by its short name. We require it to be an annotation type.
        val classes = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
            .getClassesByName("UseCase", scope)
        return classes.firstOrNull { it.isAnnotationType }
    }

    private fun getStringAttribute(annotation: PsiAnnotation, name: String): String? {
        val value = annotation.findAttributeValue(name) ?: return null
        if (value is PsiLiteralExpression) {
            return value.value as? String
        }
        // For computed expressions, fall back to text without quotes
        return value.text?.trim('"')
    }

    private fun getStringArrayAttribute(annotation: PsiAnnotation, name: String): List<String> {
        val value = annotation.findAttributeValue(name) ?: return emptyList()
        val arrayInit = value as? com.intellij.psi.PsiArrayInitializerMemberValue
        if (arrayInit != null) {
            return arrayInit.initializers.mapNotNull { initializer ->
                (initializer as? PsiLiteralExpression)?.value as? String
                    ?: initializer.text?.trim('"')
            }
        }
        // Single value case: @UseCase(businessRules = "BR-001")
        if (value is PsiLiteralExpression) {
            return listOfNotNull(value.value as? String)
        }
        return emptyList()
    }
}
