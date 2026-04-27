package ai.unifiedprocess.tools.ij

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

/**
 * Provides Find Usages (Alt+F7) for AIUP cross-references between
 * `@UseCase`-annotated tests and Markdown specs. Mirrors the gutter
 * line markers so the standard tool window surfaces the same
 * relationships in either direction.
 */
class UseCaseFindUsagesHandlerFactory(private val project: Project) : FindUsagesHandlerFactory() {

    init {
        LOG.warn("AIUP UseCaseFindUsagesHandlerFactory instantiated for project ${project.name}")
    }

    override fun canFindUsages(element: PsiElement): Boolean {
        val case = classify(element)
        LOG.warn("AIUP canFindUsages element=${element.javaClass.simpleName} text='${element.text?.take(80)}' -> case=$case")
        notify("canFindUsages: ${element.javaClass.simpleName} -> $case")
        return case != null
    }

    private fun notify(text: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AIUP")
            .createNotification("AIUP debug", text, NotificationType.INFORMATION)
            .notify(project)
    }

    override fun createFindUsagesHandler(
        element: PsiElement,
        forHighlightUsages: Boolean,
    ): FindUsagesHandler? {
        val case = classify(element)
        LOG.warn("AIUP createFindUsagesHandler element=${element.javaClass.simpleName} forHighlightUsages=$forHighlightUsages -> case=$case")
        if (case == null) return null
        return UseCaseFindUsagesHandler(element, case)
    }

    private class UseCaseFindUsagesHandler(
        element: PsiElement,
        private val case: Case,
    ) : FindUsagesHandler(element) {

        override fun processElementUsages(
            element: PsiElement,
            processor: Processor<in UsageInfo>,
            options: FindUsagesOptions,
        ): Boolean {
            LOG.warn("AIUP processElementUsages case=$case")
            val infos = @Suppress("DEPRECATION") runReadAction {
                computeTargets(project, case).map { UsageInfo(it) }
            }
            LOG.warn("AIUP processElementUsages produced ${infos.size} UsageInfo(s)")
            for (info in infos) {
                if (!processor.process(info)) return false
            }
            return true
        }
    }

    private sealed interface Case {
        data class JavaUseCase(val annotation: PsiAnnotation, val useCaseId: String) : Case
        data class JavaBrLiteral(val useCaseId: String, val brId: String) : Case
        data class MarkdownUcLine(val useCaseId: String) : Case
        data class MarkdownTitle(val useCaseId: String) : Case
        data class MarkdownBrHeading(val useCaseId: String, val brId: String) : Case
        data class MarkdownMainScenario(val useCaseId: String) : Case
        data class MarkdownAltFlow(val useCaseId: String, val scenarioCode: String) : Case
    }

    companion object {
        private val LOG = Logger.getInstance(UseCaseFindUsagesHandlerFactory::class.java)

        private val USE_CASE_ID_LINE = Regex("""\*\*Use Case ID:\*\*\s*(UC-[A-Za-z0-9_-]+)""")
        private val TITLE_HEADING = Regex("""^# \S""")
        private val BR_HEADING = Regex("""^#{1,6}\s+(BR-[A-Za-z0-9_-]+)\b""")
        private val MAIN_SCENARIO_HEADING = Regex("""^#{1,6}\s+Main\s+Success\s+Scenario\s*$""")
        private val ALT_FLOW_HEADING = Regex("""^#{1,6}\s+([A-Z]\d+)\b""")

        private fun classify(element: PsiElement): Case? =
            classifyJava(element) ?: classifyMarkdown(element)

        private fun classifyJava(element: PsiElement): Case? {
            if (element is PsiIdentifier) {
                val ref = element.parent as? PsiJavaCodeReferenceElement
                val ann = ref?.parent as? PsiAnnotation
                if (ann != null && isUseCaseAnnotation(ann)) {
                    val ucId = stringValue(ann.findAttributeValue("id")) ?: return null
                    return Case.JavaUseCase(ann, ucId)
                }
                return null
            }
            if (element is PsiLiteralExpression) {
                val pair = PsiTreeUtil.getParentOfType(element, PsiNameValuePair::class.java)
                    ?: return null
                val ann = PsiTreeUtil.getParentOfType(pair, PsiAnnotation::class.java)
                    ?: return null
                if (!isUseCaseAnnotation(ann)) return null
                val ucId = stringValue(ann.findAttributeValue("id")) ?: return null
                return when (pair.name) {
                    "id" -> Case.JavaUseCase(ann, ucId)
                    "businessRules" -> {
                        val br = element.value as? String ?: return null
                        Case.JavaBrLiteral(ucId, br)
                    }
                    else -> null
                }
            }
            return null
        }

        private fun classifyMarkdown(element: PsiElement): Case? {
            val containingFile = element.containingFile ?: return null
            val vfile = containingFile.virtualFile ?: return null
            if (vfile.extension != "md") return null

            val project = element.project
            val document = PsiDocumentManager.getInstance(project)
                .getDocument(containingFile) ?: return null

            // IntelliJ's Markdown plugin resolves heading targets up to the
            // containing MarkdownHeader (a PsiNamedElement). Don't gate on
            // leaf — match on the document line of the element's start offset.
            // Reject elements that span multiple lines (e.g. the whole file).
            val range = element.textRange ?: return null
            val lineNumber = document.getLineNumber(range.startOffset)
            val lineEnd = document.getLineEndOffset(lineNumber)
            if (range.endOffset > lineEnd + 1) return null

            val lineStart = document.getLineStartOffset(lineNumber)
            val lineText = document.getText(TextRange(lineStart, lineEnd))

            USE_CASE_ID_LINE.find(lineText)?.let {
                return Case.MarkdownUcLine(it.groupValues[1])
            }
            BR_HEADING.find(lineText)?.let { match ->
                val useCaseId = UseCaseIndex.extractUseCaseId(vfile) ?: return null
                return Case.MarkdownBrHeading(useCaseId, match.groupValues[1])
            }
            if (MAIN_SCENARIO_HEADING.containsMatchIn(lineText)) {
                val useCaseId = UseCaseIndex.extractUseCaseId(vfile) ?: return null
                return Case.MarkdownMainScenario(useCaseId)
            }
            ALT_FLOW_HEADING.find(lineText)?.let { match ->
                val useCaseId = UseCaseIndex.extractUseCaseId(vfile) ?: return null
                return Case.MarkdownAltFlow(useCaseId, match.groupValues[1])
            }
            if (TITLE_HEADING.containsMatchIn(lineText)) {
                val useCaseId = UseCaseIndex.extractUseCaseId(vfile) ?: return null
                return Case.MarkdownTitle(useCaseId)
            }
            return null
        }

        private fun computeTargets(project: Project, case: Case): List<PsiElement> = when (case) {
            is Case.JavaUseCase -> {
                UseCaseIndex.findSpecLeavesForAnnotation(project, case.annotation)
                    .ifEmpty {
                        UseCaseIndex.findSpecFiles(project, case.useCaseId)
                            .mapNotNull { PsiManager.getInstance(project).findFile(it) }
                    }
            }
            is Case.JavaBrLiteral -> listOfNotNull(
                UseCaseIndex.findBusinessRuleLeaf(project, case.useCaseId, case.brId),
            )
            is Case.MarkdownUcLine -> UseCaseIndex
                .findTestMethods(project, case.useCaseId)
                .map { it.nameIdentifier ?: it as PsiElement }
            is Case.MarkdownTitle -> UseCaseIndex
                .findTestClasses(project, case.useCaseId)
                .map { it.nameIdentifier ?: it as PsiElement }
            is Case.MarkdownBrHeading -> UseCaseIndex
                .findTestMethodsForBusinessRule(project, case.useCaseId, case.brId)
                .map { it.nameIdentifier ?: it as PsiElement }
            is Case.MarkdownMainScenario -> UseCaseIndex
                .findTestMethodsForMainScenario(project, case.useCaseId)
                .map { it.nameIdentifier ?: it as PsiElement }
            is Case.MarkdownAltFlow -> UseCaseIndex
                .findTestMethodsForScenario(project, case.useCaseId, case.scenarioCode)
                .map { it.nameIdentifier ?: it as PsiElement }
        }

        private fun isUseCaseAnnotation(ann: PsiAnnotation): Boolean {
            val qn = ann.qualifiedName ?: return false
            return qn == "UseCase" || qn.endsWith(".UseCase")
        }

        private fun stringValue(value: PsiElement?): String? =
            (value as? PsiLiteralExpression)?.value as? String
    }
}
