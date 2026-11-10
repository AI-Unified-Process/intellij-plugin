package ai.unifiedprocess.tools.ij

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.navigation.NavigationGutterIconRenderer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class CommentToSpecLineMarkerProviderTest : UnifiedProcessTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "docs/UC-002-vets.md",
            """
            # View Veterinarians

            **Use Case ID:** UC-002

            ## Main Success Scenario

            1. System shows the list.

            ## Alternative Flows

            ### A2: Data unavailable

            ## Business Rules

            ### BR-001: Lazy loading
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "docs/UC-007-pet.md",
            "# Add Pet\n\n**Use Case ID:** UC-007\n\n### BR-001: Unique name\n",
        )
    }

    fun testLineCommentLinksToBusinessRule() {
        configureJava("""// UC-002 BR-001: Rows load lazily.""")
        assertEquals(listOf("### BR-001: Lazy loading"), targetLines("Go to spec for UC-002 BR-001"))
    }

    fun testLineCommentLinksToAlternativeFlow() {
        configureJava("""// UC-002 A2: Show the error view.""")
        assertEquals(listOf("### A2: Data unavailable"), targetLines("Go to spec for UC-002 A2"))
    }

    fun testJavadocLineLinksToBusinessRule() {
        configureJava(
            """
            /**
             * Some class.
             * UC-002 BR-001: Rows load lazily.
             */
            """.trimIndent(),
        )
        assertEquals(listOf("### BR-001: Lazy loading"), targetLines("Go to spec for UC-002 BR-001"))
    }

    fun testPlainUseCaseLinksToSpecFile() {
        configureJava("""// UC-002: View Veterinarians.""")
        assertEquals(listOf("UC-002-vets.md"), targets("Go to spec for UC-002").map { it.containingFile.name })
    }

    fun testUnknownRuleFallsBackToSpecFile() {
        configureJava("""// UC-002 BR-099: Not in the spec.""")
        assertEquals(
            listOf("UC-002-vets.md"),
            targets("Go to spec for UC-002 BR-099").map { (it as PsiFile).name },
        )
    }

    fun testSlashListSharesTheRule() {
        myFixture.addFileToProject(
            "docs/UC-008-pet.md",
            "# Edit Pet\n\n**Use Case ID:** UC-008\n\n### BR-001: Same name rule\n",
        )
        configureJava("""// UC-007 / UC-008 BR-001: Unique per owner.""")
        assertEquals(
            listOf("### BR-001: Unique name", "### BR-001: Same name rule"),
            targetLines("Go to spec for UC-007 BR-001, UC-008 BR-001"),
        )
    }

    fun testWorksInOtherLanguages() {
        val file = myFixture.addFileToProject("src/main/resources/view.xml", "<!-- UC-002 BR-001: Lazy. -->\n<a/>\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertEquals(listOf("### BR-001: Lazy loading"), targetLines("Go to spec for UC-002 BR-001"))
    }

    fun testNoGutterWithoutSpec() {
        configureJava("""// UC-404 BR-001: Nothing to link to.""")
        assertNoSpecGutter()
    }

    fun testNoGutterOutsideComments() {
        configureJava("""static final String ID = "UC-002";""", inClass = true)
        assertNoSpecGutter()
    }

    fun testUseCaseIdInsideLongerIdIsNoReference() {
        assertEquals(emptyList<UseCaseIndex.UseCaseReference>(), UseCaseIndex.findUseCaseReferences("foo-UC-002 XUC-002"))
        assertEquals(
            listOf(UseCaseIndex.UseCaseReference("SUC-002", "BR-008")),
            UseCaseIndex.findUseCaseReferences("SUC-002 BR-008: Regel"),
        )
    }

    fun testWordAfterUseCaseIdIsNoQualifier() {
        assertEquals(
            listOf(UseCaseIndex.UseCaseReference("UC-002"), UseCaseIndex.UseCaseReference("UC-001", "3a")),
            UseCaseIndex.findUseCaseReferences("UC-002 View Veterinarians, see UC-001 3a."),
        )
    }

    private fun configureJava(snippet: String, inClass: Boolean = false) {
        val source = if (inClass) "class Impl {\n$snippet\n}\n" else "$snippet\nclass Impl {}\n"
        val file = myFixture.addFileToProject("src/main/java/Impl.java", source)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }

    private fun assertNoSpecGutter() {
        val tooltips = myFixture.findAllGutters().tooltips()
        assertFalse("did not expect a spec gutter, got $tooltips", tooltips.any { it.contains("Go to spec") })
    }

    private fun gutter(tooltip: String): GutterMark {
        val gutters = myFixture.findAllGutters()
        return gutters.firstOrNull { it.tooltipText?.contains(tooltip) == true }
            ?: throw AssertionError("expected gutter '$tooltip', got ${gutters.tooltips()}")
    }

    private fun targets(tooltip: String): List<PsiElement> {
        val renderer = gutter(tooltip) as LineMarkerInfo.LineMarkerGutterIconRenderer<*>
        val navigation = renderer.lineMarkerInfo.navigationHandler
        return (navigation as NavigationGutterIconRenderer).targetElements
    }

    private fun targetLines(tooltip: String): List<String> = targets(tooltip).map { target ->
        val document = PsiDocumentManager.getInstance(project).getDocument(target.containingFile)!!
        val line = document.getLineNumber(target.textRange.startOffset)
        document.text.lines()[line]
    }
}
