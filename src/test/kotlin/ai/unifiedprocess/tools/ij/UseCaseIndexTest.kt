package ai.unifiedprocess.tools.ij

class UseCaseIndexTest : AiupTestBase() {

    fun testFindSpecFilesMatchesByFilenamePrefix() {
        val spec = myFixture.addFileToProject(
            "docs/UC-001-greeting.md",
            "# Greeting\n\nNo body marker here.\n",
        )

        val results = UseCaseIndex.findSpecFiles(project, "UC-001")

        assertEquals(1, results.size)
        assertEquals(spec.virtualFile, results[0])
    }

    fun testFindSpecFilesMatchesByBodyMarker() {
        val spec = myFixture.addFileToProject(
            "docs/random-name.md",
            "# Whatever\n\n**Use Case ID:** UC-042\n",
        )

        val results = UseCaseIndex.findSpecFiles(project, "UC-042")

        assertEquals(1, results.size)
        assertEquals(spec.virtualFile, results[0])
    }

    fun testFindSpecFilesReturnsEmptyWhenNoMatch() {
        myFixture.addFileToProject("docs/UC-001-greeting.md", "# Greeting\n")

        assertTrue(UseCaseIndex.findSpecFiles(project, "UC-999").isEmpty())
    }

    fun testExtractUseCaseIdReadsBodyMarker() {
        val spec = myFixture.addFileToProject(
            "docs/random.md",
            "# Title\n\n**Use Case ID:** UC-007\n",
        )

        assertEquals("UC-007", UseCaseIndex.extractUseCaseId(spec.virtualFile))
    }

    fun testExtractUseCaseIdReturnsNullWithoutMarker() {
        val spec = myFixture.addFileToProject("docs/plain.md", "# Plain doc\n")
        assertNull(UseCaseIndex.extractUseCaseId(spec.virtualFile))
    }

    fun testHasUseCaseAnnotationTrueWhenPresent() {
        assertTrue(UseCaseIndex.hasUseCaseAnnotation(project))
    }

    fun testHasAnyUseCaseSpecMatchesByFilenameOrContent() {
        assertFalse(UseCaseIndex.hasAnyUseCaseSpec(project))
        myFixture.addFileToProject("docs/UC-100-foo.md", "# Foo\n")
        assertTrue(UseCaseIndex.hasAnyUseCaseSpec(project))
    }

    fun testFindTestMethodsResolvesByAnnotationId() {
        myFixture.addFileToProject(
            "src/test/java/example/PetTest.java",
            """
            package example;

            import ai.unifiedprocess.tools.UseCase;

            class PetTest {
                @UseCase(id = "UC-001")
                void main() {}

                @UseCase(id = "UC-002")
                void other() {}
            }
            """.trimIndent(),
        )

        val methods = UseCaseIndex.findTestMethods(project, "UC-001")
        assertEquals(1, methods.size)
        assertEquals("main", methods[0].name)
    }

    fun testFindTestMethodsForBusinessRule() {
        myFixture.addFileToProject(
            "src/test/java/example/RuleTest.java",
            """
            package example;

            import ai.unifiedprocess.tools.UseCase;

            class RuleTest {
                @UseCase(id = "UC-001", businessRules = {"BR-001", "BR-002"})
                void withRules() {}

                @UseCase(id = "UC-001", businessRules = {"BR-003"})
                void other() {}

                @UseCase(id = "UC-002", businessRules = {"BR-001"})
                void differentUc() {}
            }
            """.trimIndent(),
        )

        val matches = UseCaseIndex.findTestMethodsForBusinessRule(project, "UC-001", "BR-001")
        assertEquals(1, matches.size)
        assertEquals("withRules", matches[0].name)
    }

    fun testFindTestMethodsForMainScenario() {
        myFixture.addFileToProject(
            "src/test/java/example/MainScenarioTest.java",
            """
            package example;

            import ai.unifiedprocess.tools.UseCase;

            class MainScenarioTest {
                @UseCase(id = "UC-001")
                void noScenario() {}

                @UseCase(id = "UC-001", scenario = "")
                void blankScenario() {}

                @UseCase(id = "UC-001", scenario = "Main Success Scenario")
                void explicitMain() {}

                @UseCase(id = "UC-001", scenario = "Hauptszenario")
                void explicitMainGerman() {}

                @UseCase(id = "UC-001", scenario = "A1: missing description")
                void altFlow() {}
            }
            """.trimIndent(),
        )

        val names = UseCaseIndex.findTestMethodsForMainScenario(project, "UC-001").map { it.name }.toSet()
        assertEquals(setOf("noScenario", "blankScenario", "explicitMain", "explicitMainGerman"), names)
    }

    fun testFindTestMethodsForScenarioMatchesByPrefix() {
        myFixture.addFileToProject(
            "src/test/java/example/AltFlowTest.java",
            """
            package example;

            import ai.unifiedprocess.tools.UseCase;

            class AltFlowTest {
                @UseCase(id = "UC-001", scenario = "A1: missing description")
                void a1() {}

                @UseCase(id = "UC-001", scenario = "A1")
                void a1NoColon() {}

                @UseCase(id = "UC-001", scenario = "A2: bad input")
                void a2() {}
            }
            """.trimIndent(),
        )

        val a1 = UseCaseIndex.findTestMethodsForScenario(project, "UC-001", "A1").map { it.name }.toSet()
        assertEquals(setOf("a1", "a1NoColon"), a1)

        val a2 = UseCaseIndex.findTestMethodsForScenario(project, "UC-001", "A2").map { it.name }.toSet()
        assertEquals(setOf("a2"), a2)
    }

    fun testFindTestClassesIsDistinct() {
        myFixture.addFileToProject(
            "src/test/java/example/ClassATest.java",
            """
            package example;

            import ai.unifiedprocess.tools.UseCase;

            class ClassATest {
                @UseCase(id = "UC-001") void a() {}
                @UseCase(id = "UC-001") void b() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/test/java/example/ClassBTest.java",
            """
            package example;

            import ai.unifiedprocess.tools.UseCase;

            class ClassBTest {
                @UseCase(id = "UC-001") void c() {}
            }
            """.trimIndent(),
        )

        val classNames = UseCaseIndex.findTestClasses(project, "UC-001").map { it.name }.toSet()
        assertEquals(setOf("ClassATest", "ClassBTest"), classNames)
    }

    fun testFindBusinessRuleLeafLocatesHeading() {
        myFixture.addFileToProject(
            "docs/UC-001-rules.md",
            """
            # UC-001

            **Use Case ID:** UC-001

            ## Business Rules

            ### BR-001 First rule
            Body.

            ### BR-002 Second rule
            Body.
            """.trimIndent(),
        )

        val leaf = UseCaseIndex.findBusinessRuleLeaf(project, "UC-001", "BR-002")
        assertNotNull(leaf)
        // The leaf is the first PSI element on the heading line — its line text
        // should contain the BR heading text.
        val containing = leaf!!.containingFile
        assertTrue(containing.text.contains("### BR-002"))
    }
}
