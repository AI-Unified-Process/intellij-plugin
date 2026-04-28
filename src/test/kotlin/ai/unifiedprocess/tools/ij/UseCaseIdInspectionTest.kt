package ai.unifiedprocess.tools.ij

class UseCaseIdInspectionTest : AiupTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(UseCaseIdInspection::class.java)
    }

    fun testWarnsWhenSpecMissing() {
        myFixture.configureByText(
            "OrphanTest.java",
            """
            import ai.unifiedprocess.tools.UseCase;

            class OrphanTest {
                @UseCase(id = <warning descr="Use Case ID 'UC-404' has no matching spec file in this project">"UC-404"</warning>)
                void orphan() {}
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting(true, false, true)
    }

    fun testNoWarningWhenSpecPresent() {
        myFixture.addFileToProject("docs/UC-001-greeting.md", "**Use Case ID:** UC-001\n")
        myFixture.configureByText(
            "PetTest.java",
            """
            import ai.unifiedprocess.tools.UseCase;

            class PetTest {
                @UseCase(id = "UC-001")
                void greet() {}
            }
            """.trimIndent(),
        )

        myFixture.checkHighlighting(true, false, true)
    }
}
