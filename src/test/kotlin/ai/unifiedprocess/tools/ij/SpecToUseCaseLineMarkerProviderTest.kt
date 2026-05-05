package ai.unifiedprocess.tools.ij

class SpecToUseCaseLineMarkerProviderTest : AiupTestBase() {

    fun testGutterOnUseCaseIdLineLinksToTests() {
        myFixture.addFileToProject(
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
        val spec = myFixture.addFileToProject(
            "docs/UC-001-greeting.md",
            """
            # UC-001 Greeting

            **Use Case ID:** UC-001

            ## Main Success Scenario
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(spec.virtualFile)

        val tooltips = myFixture.findAllGutters().tooltips()
        assertTrue(
            "expected UC-001 test-method gutter, got $tooltips",
            tooltips.any { it.contains("Go to test methods for UC-001") },
        )
        assertTrue(
            "expected UC-001 test-class gutter, got $tooltips",
            tooltips.any { it.contains("Go to test class for UC-001") },
        )
        assertTrue(
            "expected Main Success Scenario gutter, got $tooltips",
            tooltips.any { it.contains("Go to test methods for the Main Success Scenario of UC-001") },
        )
    }

    fun testGutterOnHauptszenarioHeadingLinksToTests() {
        myFixture.addFileToProject(
            "src/test/java/example/PetGermanTest.java",
            """
            package example;

            import ai.unifiedprocess.tools.UseCase;

            class PetGermanTest {
                @UseCase(id = "UC-010")
                void greet() {}
            }
            """.trimIndent(),
        )
        val spec = myFixture.addFileToProject(
            "docs/UC-010-begruessung.md",
            """
            # UC-010 Begrüßung

            **Use Case ID:** UC-010

            ## Hauptszenario
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(spec.virtualFile)

        val tooltips = myFixture.findAllGutters().tooltips()
        assertTrue(
            "expected Hauptszenario gutter, got $tooltips",
            tooltips.any { it.contains("Go to test methods for the Main Success Scenario of UC-010") },
        )
    }

    fun testGutterOnBusinessRuleHeading() {
        myFixture.addFileToProject(
            "src/test/java/example/RuleTest.java",
            """
            package example;

            import ai.unifiedprocess.tools.UseCase;

            class RuleTest {
                @UseCase(id = "UC-002", businessRules = {"BR-001"})
                void rule() {}
            }
            """.trimIndent(),
        )
        val spec = myFixture.addFileToProject(
            "docs/UC-002-rules.md",
            """
            # UC-002

            **Use Case ID:** UC-002

            ### BR-001 First rule
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(spec.virtualFile)

        val tooltips = myFixture.findAllGutters().tooltips()
        assertTrue(
            "expected BR-001 gutter, got $tooltips",
            tooltips.any { it.contains("Go to test methods for BR-001") },
        )
    }

    fun testGutterOnAlternativeFlowHeading() {
        myFixture.addFileToProject(
            "src/test/java/example/AltTest.java",
            """
            package example;

            import ai.unifiedprocess.tools.UseCase;

            class AltTest {
                @UseCase(id = "UC-003", scenario = "A1: missing description")
                void a1() {}
            }
            """.trimIndent(),
        )
        val spec = myFixture.addFileToProject(
            "docs/UC-003-altflow.md",
            """
            # UC-003

            **Use Case ID:** UC-003

            ### A1: missing description
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(spec.virtualFile)

        val tooltips = myFixture.findAllGutters().tooltips()
        assertTrue(
            "expected A1 gutter, got $tooltips",
            tooltips.any { it.contains("Go to test methods for A1") },
        )
    }

    fun testNoGuttersWhenNoMatchingTests() {
        val spec = myFixture.addFileToProject(
            "docs/UC-404-orphan.md",
            """
            # Orphan

            **Use Case ID:** UC-404

            ### BR-001 Orphan rule
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(spec.virtualFile)

        val tooltips = myFixture.findAllGutters().tooltips()
        assertFalse(
            "did not expect any UC-404 gutters, got $tooltips",
            tooltips.any { it.contains("UC-404") || it.contains("BR-001") },
        )
    }
}
