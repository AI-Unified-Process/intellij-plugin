package ai.unifiedprocess.tools.ij

import com.intellij.openapi.diagnostic.Logger
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/**
 * Makes `"UC-XXX"` and `"BR-XXX"` literals inside `@UseCase` annotations
 * behave as PSI references. Two effects:
 *  - Ctrl+B / Cmd+B (Go To Declaration) jumps to the matching spec or BR heading.
 *  - IntelliJ's TargetElementUtil considers the literal a navigation target,
 *    so Alt+F7 (Find Usages) reaches our FindUsagesHandlerFactory instead of
 *    bailing out with "Cannot search for usages from this location."
 */
class UseCaseLiteralReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            UseCaseLiteralReferenceProvider,
        )
    }
}

private object UseCaseLiteralReferenceProvider : PsiReferenceProvider() {
    private val LOG = Logger.getInstance(UseCaseLiteralReferenceProvider::class.java)

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
        if (literal.value !is String) return PsiReference.EMPTY_ARRAY

        val pair = PsiTreeUtil.getParentOfType(literal, PsiNameValuePair::class.java)
            ?: return PsiReference.EMPTY_ARRAY
        val ann = PsiTreeUtil.getParentOfType(pair, PsiAnnotation::class.java)
            ?: return PsiReference.EMPTY_ARRAY
        val qn = ann.qualifiedName ?: return PsiReference.EMPTY_ARRAY
        if (qn != "UseCase" && !qn.endsWith(".UseCase")) return PsiReference.EMPTY_ARRAY
        if (pair.name != "id" && pair.name != "businessRules") return PsiReference.EMPTY_ARRAY

        LOG.warn("AIUP UseCaseLiteralReference attached to ${pair.name}=\"${literal.value}\"")
        return arrayOf(UseCaseLiteralReference(literal))
    }
}

private class UseCaseLiteralReference(literal: PsiLiteralExpression) :
    PsiReferenceBase<PsiLiteralExpression>(literal, true) {

    override fun resolve(): PsiElement? {
        val literal = element
        val literalValue = literal.value as? String ?: return null
        val pair = PsiTreeUtil.getParentOfType(literal, PsiNameValuePair::class.java) ?: return null
        val ann = PsiTreeUtil.getParentOfType(pair, PsiAnnotation::class.java) ?: return null
        val ucId = (ann.findAttributeValue("id") as? PsiLiteralExpression)?.value as? String
            ?: return null
        val project = literal.project
        return when (pair.name) {
            "id" -> UseCaseIndex.findSpecFiles(project, ucId).firstOrNull()
                ?.let { PsiManager.getInstance(project).findFile(it) }
            "businessRules" -> UseCaseIndex.findBusinessRuleLeaf(project, ucId, literalValue)
            else -> null
        }
    }
}
