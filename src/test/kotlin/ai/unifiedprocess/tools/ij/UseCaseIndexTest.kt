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

    fun testFindSpecFilesMatchesLetterSuffixedId() {
        val spec = myFixture.addFileToProject(
            "docs/UC-013b-variant.md",
            "# Variant\n\n**Use Case ID:** UC-013b\n",
        )
        val other = myFixture.addFileToProject(
            "docs/UC-013-base.md",
            "# Base\n\n**Use Case ID:** UC-013\n",
        )

        val matches = UseCaseIndex.findSpecFiles(project, "UC-013b")
        assertEquals(1, matches.size)
        assertEquals(spec.virtualFile, matches[0])

        val baseMatches = UseCaseIndex.findSpecFiles(project, "UC-013")
        assertEquals(1, baseMatches.size)
        assertEquals(other.virtualFile, baseMatches[0])
    }

    fun testFindSpecFilesMatchesMultiSegmentId() {
        val byName = myFixture.addFileToProject(
            "docs/UC-LOGIN-001-sign-in.md",
            "# Sign in\n\nNo body marker here.\n",
        )
        val byBody = myFixture.addFileToProject(
            "docs/checkout-flow.md",
            "# Checkout\n\n**Use Case ID:** UC-CHECKOUT-002\n",
        )

        val nameMatches = UseCaseIndex.findSpecFiles(project, "UC-LOGIN-001")
        assertEquals(1, nameMatches.size)
        assertEquals(byName.virtualFile, nameMatches[0])

        val bodyMatches = UseCaseIndex.findSpecFiles(project, "UC-CHECKOUT-002")
        assertEquals(1, bodyMatches.size)
        assertEquals(byBody.virtualFile, bodyMatches[0])
    }

    fun testFindSpecFilesMatchesSystemAndBusinessUseCaseIds() {
        val suc = myFixture.addFileToProject(
            "docs/SUC-001-view-owners.md",
            "# View Owners\n\nNo body marker here.\n",
        )
        val buc = myFixture.addFileToProject(
            "docs/BUC-001-onboard-customer.md",
            "# Onboard Customer\n\nNo body marker here.\n",
        )

        val sucMatches = UseCaseIndex.findSpecFiles(project, "SUC-001")
        assertEquals(1, sucMatches.size)
        assertEquals(suc.virtualFile, sucMatches[0])

        val bucMatches = UseCaseIndex.findSpecFiles(project, "BUC-001")
        assertEquals(1, bucMatches.size)
        assertEquals(buc.virtualFile, bucMatches[0])
    }

    fun testFindSpecFilesMatchesPrefixedFileNames() {
        val prefixed = myFixture.addFileToProject(
            "docs/petclinic-UC-002-view-veterinarians.md",
            "# View Veterinarians\n\nNo body marker here.\n",
        )
        val prefixedSuc = myFixture.addFileToProject(
            "docs/petclinic-SUC-003-edit-owner.md",
            "# Edit Owner\n\nNo body marker here.\n",
        )

        val ucMatches = UseCaseIndex.findSpecFiles(project, "UC-002")
        assertEquals(1, ucMatches.size)
        assertEquals(prefixed.virtualFile, ucMatches[0])

        val sucMatches = UseCaseIndex.findSpecFiles(project, "SUC-003")
        assertEquals(1, sucMatches.size)
        assertEquals(prefixedSuc.virtualFile, sucMatches[0])
    }

    fun testFindSpecFilesDoesNotConfuseUcWithSucOrBuc() {
        myFixture.addFileToProject("docs/SUC-005-system.md", "# System\n")
        myFixture.addFileToProject("docs/BUC-005-business.md", "# Business\n")

        assertTrue(UseCaseIndex.findSpecFiles(project, "UC-005").isEmpty())
    }

    fun testFindSpecFilesMatchesSucBodyMarker() {
        val spec = myFixture.addFileToProject(
            "docs/some-spec.md",
            "# Whatever\n\n**Use Case ID:** SUC-042\n",
        )

        val results = UseCaseIndex.findSpecFiles(project, "SUC-042")

        assertEquals(1, results.size)
        assertEquals(spec.virtualFile, results[0])
        assertEquals("SUC-042", UseCaseIndex.extractUseCaseId(spec.virtualFile))
    }

    fun testIsSpecFileNameAcceptsAllVariants() {
        assertTrue(UseCaseIndex.isSpecFileName("UC-002-view-veterinarians"))
        assertTrue(UseCaseIndex.isSpecFileName("SUC-001-view-owners"))
        assertTrue(UseCaseIndex.isSpecFileName("BUC-001-onboard-customer"))
        assertTrue(UseCaseIndex.isSpecFileName("petclinic-UC-002-view"))
        assertTrue(UseCaseIndex.isSpecFileName("petclinic-SUC-001-view"))
        assertTrue(UseCaseIndex.isSpecFileName("petclinic-BUC-001-onboard"))

        assertFalse(UseCaseIndex.isSpecFileName("README"))
        assertFalse(UseCaseIndex.isSpecFileName("RUC-001-not-a-spec"))
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
