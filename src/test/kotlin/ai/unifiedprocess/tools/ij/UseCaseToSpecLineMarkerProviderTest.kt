package ai.unifiedprocess.tools.ij

class UseCaseToSpecLineMarkerProviderTest : AiupTestBase() {

    fun testGutterAppearsOnUseCaseAnnotationWhenSpecExists() {
        myFixture.addFileToProject(
            "docs/UC-001-greeting.md",
            "# Greeting\n\n**Use Case ID:** UC-001\n",
        )
        val testFile = myFixture.addFileToProject(
            "src/test/java/example/PetTest.java",
            """
            package example;

            import ai.unifiedprocess.tools.UseCase;

            class PetTest {
                @UseCase(id = "UC-001")
                void greet() {}
            }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(testFile.virtualFile)

        val tooltips = myFixture.findAllGutters().tooltips()
        assertTrue(
            "expected a 'Go to spec for UC-001' gutter, got $tooltips",
            tooltips.any { it.contains("Go to spec for UC-001") },
        )
    }

    fun testNoGutterWhenSpecMissing() {
        val testFile = myFixture.addFileToProject(
            "src/test/java/example/OrphanTest.java",
            """
            package example;

            import ai.unifiedprocess.tools.UseCase;

            class OrphanTest {
                @UseCase(id = "UC-404")
                void orphan() {}
            }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(testFile.virtualFile)

        val tooltips = myFixture.findAllGutters().tooltips()
        assertFalse(
            "did not expect a UC-404 gutter, got $tooltips",
            tooltips.any { it.contains("UC-404") },
        )
    }

    fun testGutterPointsToBusinessRuleHeading() {
        myFixture.addFileToProject(
            "docs/UC-005-rules.md",
            """
            # UC-005

            **Use Case ID:** UC-005

            ## Main Success Scenario

            ### BR-001 First rule
            """.trimIndent(),
        )
        val testFile = myFixture.addFileToProject(
            "src/test/java/example/RuleTest.java",
            """
            package example;

            import ai.unifiedprocess.tools.UseCase;

            class RuleTest {
                @UseCase(id = "UC-005", businessRules = {"BR-001"})
                void rule() {}
            }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(testFile.virtualFile)

        val tooltips = myFixture.findAllGutters().tooltips()
        assertTrue(
            "expected a UC-005 gutter, got $tooltips",
            tooltips.any { it.contains("Go to spec for UC-005") },
        )
    }
}
