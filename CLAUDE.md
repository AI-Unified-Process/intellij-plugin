# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

IntelliJ IDEA plugin (Kotlin, JVM 21) that adds gutter-icon navigation between `@UseCase`-annotated test methods
and their Markdown specs in AI Unified Process projects. Built with the JetBrains
`org.jetbrains.intellij.platform` Gradle plugin.

## Common commands

```bash
./gradlew build              # Compile + verify + assemble plugin zip (build/distributions/)
./gradlew runIde             # Launch a sandbox IDE with the plugin loaded
./gradlew buildPlugin        # Just the plugin zip (for "Install from disk")
./gradlew verifyPlugin       # Run JetBrains pluginVerifier against target IDE
./gradlew test               # Run unit tests (Platform test framework is wired in)
```

Target IDE platform is IntelliJ IDEA Community 2026.1 (`sinceBuild=261`, no `untilBuild` upper bound — the plugin
stays installable on newer IDE releases). Raising the minimum supported version requires updating both the dependency
in `build.gradle.kts` and `intellijPlatform.pluginConfiguration.ideaVersion`.

## Architecture

The plugin is three Kotlin files in `src/main/kotlin/ai/unifiedprocess/tools/ij/`, registered as IntelliJ extensions in
`src/main/resources/META-INF/plugin.xml`.

- **`UseCaseIndex`** (object): all PSI/VFS lookups live here. Two design choices that propagate through the rest of the
  code:
    1. The `@UseCase` annotation is resolved by **short name** via `PsiShortNamesCache` (filtered to annotation types),
       not by FQN — so the plugin works in any project that defines an annotation called `UseCase`, regardless of
       package.
    2. Spec files are matched three ways: filename (`UC-002-*.md` or `UC-032_*.md`, `SUC-*/BUC-*` variants, each
       optionally behind a project prefix like `petclinic-UC-002-*.md`), content match against the regex
       `**Use Case ID:** UC-XXX`, **or** — as a fallback — the H1 title (`# UC-001: Kunde suchen`, the German spec
       style). Content match reads the file via `contentsToByteArray()` on every call — fine for typical AI Unified Process repos
       but a known scaling concern (see README "Notes").

- **`UseCaseToSpecLineMarkerProvider`** (UAST line marker): registered for the `UAST` meta-language, so one provider
  serves Java, Kotlin and every other language with a UAST implementation. It triggers only on the leaf whose text is
  the annotation's short name (e.g. the `UseCase` token in `@UseCase(...)`), per IntelliJ's contract that markers must
  anchor to leaves — and that leaf must have a reference parent, which is what tells the name token apart from a Kotlin
  string value reading `"UseCase"`. Attributes are read as `UAnnotation` values via `UseCaseIndex`, never as
  `PsiLiteralExpression`: the Java model does not cover other languages.

- **`SpecToUseCaseLineMarkerProvider`** (Markdown line marker): Markdown PSI is unstable across IDE versions, so this
  provider does **plain-text regex matching on leaf elements** rather than navigating the AST. Recognised sites:
  `**Use Case ID:** UC-XXX` lines, the H1 title, main-flow headings, alt-flow headings (`### A1: …` / `### 3a. …`),
  and business-rule sites (`### BR-XXX` headings or `- **GR-XXX:** …` bullets). The `isFirstMatchingLeafOnLine` helper
  walks `PsiTreeUtil.prevLeaf` back to the document line start to suppress duplicate markers when a regex match spans
  multiple PSI leaves on the same line — keep this guard if you add more Markdown patterns.

## Convention contract with consumer projects

The plugin assumes the host project follows this shape (from the `aiup-petclinic` example):

- Annotation type named `UseCase` — Java or Kotlin — with attributes `id: String`, `scenario: String`,
  `businessRules: String[]`. Tests carrying it may be written in any language the IDE exposes through UAST.
- Use Case IDs are `UC-XXX` or the `SUC-XXX` / `BUC-XXX` variants (System / Business Use Case).
- Markdown specs anywhere in the project content scope, identified by filename (`UC-XXX-*.md` or `UC-XXX_*.md`,
  `SUC-*`/`BUC-*` variants, optionally behind a project prefix such as `petclinic-UC-XXX-*.md`), a body line
  `**Use Case ID:** UC-XXX`, or an H1 title starting with the ID (`# UC-001: Kunde suchen`).
- Business rules declared as Markdown headings of the form `### BR-XXX`, or — in the German spec style — as bold
  bullet items `- **GR-XXX:** …`.
- The main flow heading may be `Main Success Scenario` (English), `Hauptszenario` or `Hauptablauf` (German); the
  `scenario` attribute on `@UseCase` accepts all three labels (case-insensitive) as the main flow.
- Alternative flows are coded `A1`/`A2` (`### A1: …`, branching at the step the `**Trigger:**` references) or in the
  German step-coded style `3a`/`5a` (`### 3a. Keine Treffer gefunden`, branching directly at that step); the
  `scenario` attribute accepts either code as prefix (e.g. `scenario = "3a: Keine Treffer gefunden"`).

The ID/filename/heading patterns live centrally in `UseCaseIndex` (`USE_CASE_ID_LINE`, `USE_CASE_TITLE`,
`SPEC_FILE_NAME`, `MAIN_SCENARIO_HEADING`, `ALT_FLOW_HEADING`, `BUSINESS_RULE_SITE`, `SCENARIO_CODE`);
`SpecToUseCaseLineMarkerProvider`, `UseCaseDeclarationProvider`, and `UseCaseUsageSearcher` reference those shared
values rather than defining their own copies — keep it that way when changing the patterns.

## Known inconsistency

`plugin.xml` registers extensions under `ai.unifiedprocess.tools.ij.*`, but the actual Kotlin classes live in package
`ai.unifiedprocess.tools.ij`. The plugin will fail to load until these are reconciled — pick one package and update both
sides.
