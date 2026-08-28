package ai.unifiedprocess.tools.ij

class UseCaseIdInspectionTest : UnifiedProcessTestBase() {

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

    // The Kotlin cases assert on the descriptions rather than through checkHighlighting: the light
    // test project has no JDK, so Kotlin cannot resolve `java.lang.String` in the Java-declared
    // annotation and reports a type mismatch on every argument. That error says nothing about this
    // inspection, and a full-highlighting comparison would fail on it.
    fun testWarnsWhenSpecMissingInKotlin() {
        myFixture.configureByText(
            "OrphanTest.kt",
            """
            import ai.unifiedprocess.tools.UseCase

            class OrphanTest {
                @UseCase(id = "UC-404")
                fun orphan() {}
            }
            """.trimIndent(),
        )

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "expected the missing-spec warning for UC-404, got $descriptions",
            descriptions.any { it == "Use Case ID 'UC-404' has no matching spec file in this project" },
        )
    }

    fun testNoWarningWhenSpecPresentInKotlin() {
        myFixture.addFileToProject("docs/UC-001-greeting.md", "**Use Case ID:** UC-001\n")
        myFixture.configureByText(
            "PetTest.kt",
            """
            import ai.unifiedprocess.tools.UseCase

            class PetTest {
                @UseCase(id = "UC-001")
                fun greet() {}
            }
            """.trimIndent(),
        )

        val descriptions = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "did not expect a missing-spec warning, got $descriptions",
            descriptions.any { it.contains("has no matching spec file") },
        )
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
