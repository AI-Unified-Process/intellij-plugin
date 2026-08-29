package ai.unifiedprocess.tools.ij

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Flags a `@UseCase(id = ...)` whose ID has no spec file in the project — in Java, Kotlin, or any
 * other language UAST covers.
 */
class UseCaseIdInspection : AbstractBaseUastLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitAnnotation(node: UAnnotation): Boolean {
                    // Matched by short name so the inspection works regardless of the
                    // annotation's package — same convention as UseCaseIndex.
                    if (!UseCaseIndex.isUseCaseAnnotation(node)) return true
                    val idExpression = node.findAttributeValue("id") ?: return true
                    val id = idExpression.evaluate() as? String ?: return true
                    // The warning belongs on the id as written, which is why the source element is
                    // used rather than the UAST node: only the former exists in the editor.
                    val anchor = idExpression.sourcePsi ?: return true
                    if (UseCaseIndex.findSpecFiles(holder.project, id).isEmpty()) {
                        holder.registerProblem(
                            anchor,
                            "Use Case ID '$id' has no matching spec file in this project",
                        )
                    }
                    return true
                }
            },
            arrayOf(UAnnotation::class.java),
        )
}
