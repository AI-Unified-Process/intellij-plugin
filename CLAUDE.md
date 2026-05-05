# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

IntelliJ IDEA plugin (Kotlin, JVM 21) that adds gutter-icon navigation between `@UseCase`-annotated Java test methods
and their Markdown specs in AIUP (AI Unified Process) projects. Built with the JetBrains
`org.jetbrains.intellij.platform` Gradle plugin.

## Common commands

```bash
./gradlew build              # Compile + verify + assemble plugin zip (build/distributions/)
./gradlew runIde             # Launch a sandbox IDE with the plugin loaded
./gradlew buildPlugin        # Just the plugin zip (for "Install from disk")
./gradlew verifyPlugin       # Run JetBrains pluginVerifier against target IDE
./gradlew test               # Run unit tests (Platform test framework is wired in)
```

Target IDE platform is IntelliJ IDEA Community 2026.1 (`sinceBuild=261`, `untilBuild=261.*`). Bumping support requires
updating both the dependency in `build.gradle.kts` and `intellijPlatform.pluginConfiguration.ideaVersion`.

## Architecture

The plugin is three Kotlin files in `src/main/kotlin/ai/unifiedprocess/tools/ij/`, registered as IntelliJ extensions in
`src/main/resources/META-INF/plugin.xml`.

- **`UseCaseIndex`** (object): all PSI/VFS lookups live here. Two design choices that propagate through the rest of the
  code:
    1. The `@UseCase` annotation is resolved by **short name** via `PsiShortNamesCache` (filtered to annotation types),
       not by FQN — so the plugin works in any project that defines an annotation called `UseCase`, regardless of
       package.
    2. Spec files are matched two ways: filename prefix (`UC-002-*.md`) **or** content match against the regex
       `**Use Case ID:** UC-XXX`. Content match reads the file via `contentsToByteArray()` on every call — fine for
       typical AIUP repos but a known scaling concern (see README "Notes").

- **`UseCaseToSpecLineMarkerProvider`** (Java line marker): triggers only on the `PsiIdentifier` leaf of an annotation
  reference (e.g. the `UseCase` token in `@UseCase(...)`), per IntelliJ's contract that markers must anchor to leaves.
  Reads the `id` attribute as a `PsiLiteralExpression` and delegates lookup to `UseCaseIndex`.

- **`SpecToUseCaseLineMarkerProvider`** (Markdown line marker): Markdown PSI is unstable across IDE versions, so this
  provider does **plain-text regex matching on leaf elements** rather than navigating the AST. Two patterns are
  recognised: `**Use Case ID:** UC-XXX` and headings `### BR-XXX`. The `isFirstMatchingLeafOnLine` helper walks
  `PsiTreeUtil.prevLeaf` back to the document line start to suppress duplicate markers when a regex match spans multiple
  PSI leaves on the same line — keep this guard if you add more Markdown patterns.

## Convention contract with consumer projects

The plugin assumes the host project follows this shape (from the `aiup-petclinic` example):

- Java annotation named `UseCase` (annotation type) with attributes `id: String`, `scenario: String`,
  `businessRules: String[]`.
- Markdown specs anywhere in the project content scope, identified by either filename `UC-XXX-*.md` or a body line
  `**Use Case ID:** UC-XXX`.
- Business rules declared as Markdown headings of the form `### BR-XXX`.
- The main flow heading may be either `Main Success Scenario` (English) or `Hauptszenario` (German); the
  `scenario` attribute on `@UseCase` accepts both labels (case-insensitive) as the main flow.

Changing these patterns means updating the regexes in `UseCaseIndex.USE_CASE_ID_PATTERN` and
`SpecToUseCaseLineMarkerProvider.{useCaseIdLine,businessRuleHeading}` together.

## Known inconsistency

`plugin.xml` registers extensions under `ai.unifiedprocess.tools.ij.*`, but the actual Kotlin classes live in package
`ai.unifiedprocess.tools.ij`. The plugin will fail to load until these are reconciled — pick one package and update both
sides.
