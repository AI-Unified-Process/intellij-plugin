package ai.unifiedprocess.tools.ij

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.navigation.NavigationGutterIconRenderer
import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.UsageSearchParameters
import com.intellij.find.usages.api.SearchTarget
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.testFramework.PsiTestUtil

/** Spec to implementation: the gutter icon in Markdown and Find Usages over code comments. */
class SpecToImplementationTest : UnifiedProcessTestBase() {

    private val spec = """
        # View Veterinarians

        **Use Case ID:** UC-002

        ## Main Success Scenario

        1. System shows the list.

        ### A2: Data unavailable

        ### BR-001: Lazy loading

        ### BR-002: Unmarked rule
    """.trimIndent()

    fun testRuleHeadingLinksToItsMarkersOnly() {
        addCode("VetView.java", "// UC-002 BR-001: Rows load lazily.\nclass VetView {}\n")
        addCode("Other.java", "// UC-003 BR-001: Same rule id, other Use Case.\n// UC-002 BR-009: Other rule.\nclass Other {}\n")
        openSpec()

        assertEquals(
            listOf("// UC-002 BR-001: Rows load lazily."),
            targets("Go to implementation of UC-002 BR-001").map { it.text },
        )
    }

    fun testRuleWithoutMarkerHasNoIcon() {
        addCode("VetView.java", "// UC-002 BR-001: Rows load lazily.\nclass VetView {}\n")
        openSpec()

        val tooltips = myFixture.findAllGutters().tooltips()
        assertFalse("got $tooltips", tooltips.any { it.contains("BR-002") })
    }

    fun testAlternativeFlowHeadingLinksToItsMarkers() {
        addCode("ErrorView.java", "/** UC-002 A2: generic error view. */\nclass ErrorView {}\n")
        openSpec()

        assertEquals(1, targets("Go to implementation of UC-002 A2").size)
    }

    fun testIdLineLinksToEveryMarkerOfTheUseCase() {
        addCode("VetView.java", "// UC-002 BR-001: Rows load lazily.\n// UC-002: The view.\nclass VetView {}\n")
        addCode("SUC.java", "// SUC-002: Not this Use Case.\nclass SUC {}\n")
        openSpec()

        assertEquals(2, targets("Go to implementation of UC-002").size)
    }

    fun testGermanTitleCarriesTheIconWithoutIdLine() {
        addCode("Kunde.java", "// UC-001 GR-008: Regel.\nclass Kunde {}\n")
        val german = myFixture.addFileToProject(
            "docs/UC-001_Kunde_suchen.md",
            "# UC-001: Kunde suchen\n\n## Geschäftsregeln\n\n- **GR-008:** Eine Regel\n",
        )
        myFixture.configureFromExistingVirtualFile(german.virtualFile)

        assertEquals(1, targets("Go to implementation of UC-001 GR-008").size)
        val useCaseIcons = myFixture.findAllGutters().tooltips().filter { it == "Go to implementation of UC-001" }
        assertEquals(1, useCaseIcons.size)
    }

    fun testSlashListCountsForEveryUseCase() {
        addCode("V1.java", "// UC-007 / UC-002 BR-001: Shared rule.\nclass V1 {}\n")
        openSpec()

        assertEquals(1, targets("Go to implementation of UC-002 BR-001").size)
    }

    fun testXmlCommentIsFound() {
        myFixture.addFileToProject("src/main/resources/view.xml", "<!-- UC-002 BR-001: Lazy. -->\n<a/>\n")
        openSpec()

        assertEquals(1, targets("Go to implementation of UC-002 BR-001").size)
    }

    fun testTestSourcesAreExcluded() {
        val file = myFixture.addFileToProject("testsrc/VetTest.java", "// UC-002 BR-001: In a test.\nclass VetTest {}\n")
        val root = file.virtualFile.parent
        PsiTestUtil.addSourceRoot(module, root, true)
        try {
            assertEmpty(UseCaseIndex.findImplementationMarkers(project, "UC-002", "BR-001"))
        } finally {
            PsiTestUtil.removeSourceRoot(module, root)
        }
    }

    fun testFindUsagesOfRuleIncludesCommentMarker() {
        addCode("VetView.java", "// UC-002 BR-001: Rows load lazily.\nclass VetView {}\n")
        myFixture.addFileToProject("docs/UC-002-vets.md", spec)

        val usages = usagesOf(BusinessRuleSymbol(project, "UC-002", "BR-001"))
        assertTrue("got $usages", "VetView.java:UC-002 BR-001" in usages)
    }

    fun testMainScenarioHasNoCommentUsages() {
        addCode("VetView.java", "// UC-002: The view.\nclass VetView {}\n")
        myFixture.addFileToProject("docs/UC-002-vets.md", spec)

        val usages = usagesOf(ScenarioSymbol(project, "UC-002", null))
        assertFalse("got $usages", usages.any { it.startsWith("VetView.java") })
    }

    fun testFindUsagesStartsOnTheMarker() {
        val code = addCode("VetView.java", "// see UC-002 BR-001: Rows.\nclass VetView {}\n")
        val offset = code.text.indexOf("BR-001")
        val leaf = code.findElementAt(offset)!!

        val declarations = UseCaseDeclarationProvider()
            .getDeclarations(leaf, offset - leaf.textRange.startOffset)
        assertEquals(listOf(BusinessRuleSymbol(project, "UC-002", "BR-001")), declarations.map(PsiSymbolDeclaration::getSymbol))

        assertEmpty(UseCaseDeclarationProvider().getDeclarations(leaf, 0))
    }

    private fun addCode(name: String, text: String) = myFixture.addFileToProject("src/main/java/$name", text)

    private fun openSpec() {
        val file = myFixture.addFileToProject("docs/UC-002-vets.md", spec)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }

    private fun targets(tooltip: String): List<PsiElement> {
        val gutters = myFixture.findAllGutters()
        val gutter = gutters.firstOrNull { it.tooltipText?.removePrefix("<html>")?.removeSuffix("</html>") == tooltip }
            ?: throw AssertionError("expected gutter '$tooltip', got ${gutters.tooltips()}")
        val renderer = gutter as LineMarkerInfo.LineMarkerGutterIconRenderer<*>
        return (renderer.lineMarkerInfo.navigationHandler as NavigationGutterIconRenderer).targetElements
    }

    private fun usagesOf(symbol: UseCaseRelatedSymbol): List<String> {
        val testProject = project
        val parameters = object : UsageSearchParameters {
            override val target: SearchTarget = symbol
            override val searchScope: SearchScope = GlobalSearchScope.projectScope(testProject)
            override fun areValid(): Boolean = true
            override fun getProject(): Project = testProject
        }
        return UseCaseUsageSearcher().collectSearchRequests(parameters)
            .flatMap { it.findAll() }
            .map { usage ->
                val psiUsage = usage as PsiUsage
                "${psiUsage.file.name}:${psiUsage.file.text.substring(psiUsage.range.startOffset, psiUsage.range.endOffset)}"
            }
    }
}
