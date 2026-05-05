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
 *  - Spec files are Markdown files containing the line `**Use Case ID:** UC-XXX`
 *    (we also accept `UC-XXX` in the file name as a fallback).
 *  - Test methods are annotated with `@UseCase(id = "UC-XXX", ...)`.
 */
object UseCaseIndex {

    private const val USE_CASE_ID_PATTERN = "\\*\\*Use Case ID:\\*\\*\\s*(UC-[A-Za-z0-9_-]+)"
    private val ID_REGEX = Regex(USE_CASE_ID_PATTERN)

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
     * a Use Case spec — either named `UC-XXX(-...).md` or containing the body line
     * `**Use Case ID:** UC-XXX`. Short-circuits on the first match.
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

    private fun looksLikeUseCaseSpec(file: VirtualFile): Boolean {
        val name = file.nameWithoutExtension
        if (name.matches(Regex("UC-[A-Za-z0-9_-]+"))) return true
        return try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            ID_REGEX.containsMatchIn(content)
        } catch (e: Exception) {
            false
        }
    }

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
        // Quick check: file name often contains the ID like "UC-002-view-veterinarians.md"
        if (file.nameWithoutExtension.startsWith("$useCaseId-") ||
            file.nameWithoutExtension == useCaseId) {
            return true
        }

        // Content check
        return try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            val match = ID_REGEX.find(content)
            match?.groupValues?.get(1) == useCaseId
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
            ID_REGEX.find(content)?.groupValues?.get(1)
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
     * "A1: Missing Description" -> "A1". Returns null if the value doesn't
     * follow the `<Letter><Digits>[:…]` form.
     */
    private fun scenarioPrefix(scenario: String): String? {
        val colon = scenario.indexOf(':')
        val prefix = (if (colon >= 0) scenario.substring(0, colon) else scenario).trim()
        return prefix.takeIf { it.matches(Regex("[A-Z]\\d+")) }
    }

    private fun findScenarioLeaf(project: Project, useCaseId: String, scenarioCode: String?): PsiElement? {
        val pattern = if (scenarioCode == null) {
            Regex("""^#{1,6}\s+(?:Main\s+Success\s+Scenario|Hauptszenario)\s*$""")
        } else {
            Regex("""^#{1,6}\s+${Regex.escape(scenarioCode)}\b""")
        }
        return findHeadingLeaf(project, useCaseId, pattern)
    }

    private fun isMainScenarioLabel(value: String): Boolean =
        value.equals("Main Success Scenario", ignoreCase = true) ||
            value.equals("Hauptszenario", ignoreCase = true)

    fun findBusinessRuleLeaf(project: Project, useCaseId: String, brId: String): PsiElement? {
        return findHeadingLeaf(project, useCaseId, Regex("""^#{1,6}\s+${Regex.escape(brId)}\b"""))
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
