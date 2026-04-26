package ai.unifiedprocess.tools.ij

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLiteralExpression

class UseCaseIdInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitAnnotation(annotation: PsiAnnotation) {
                // Match by short name so the inspection works regardless of the
                // annotation's package — same convention as UseCaseIndex.
                if (annotation.nameReferenceElement?.referenceName != "UseCase") return
                val idLiteral = annotation.findAttributeValue("id") as? PsiLiteralExpression ?: return
                val id = idLiteral.value as? String ?: return
                if (UseCaseIndex.findSpecFiles(holder.project, id).isEmpty()) {
                    holder.registerProblem(
                        idLiteral,
                        "Use Case ID '$id' has no matching spec file in this project",
                    )
                }
            }
        }
}
