# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

IntelliJ IDEA plugin (Kotlin, JVM 21) that adds gutter-icon navigation between `@UseCase`-annotated Java test methods
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

The plugin's Kotlin sources live in `src/main/kotlin/ai/unifiedprocess/tools/ij/`, registered as IntelliJ extensions in
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

- **`UseCaseToSpecLineMarkerProvider`** (Java line marker): triggers only on the `PsiIdentifier` leaf of an annotation
  reference (e.g. the `UseCase` token in `@UseCase(...)`), per IntelliJ's contract that markers must anchor to leaves.
  Reads the `id` attribute as a `PsiLiteralExpression` and delegates lookup to `UseCaseIndex`.

- **`SpecToUseCaseLineMarkerProvider`** (Markdown line marker): Markdown PSI is unstable across IDE versions, so this
  provider does **plain-text regex matching on leaf elements** rather than navigating the AST. Recognised sites:
  `**Use Case ID:** UC-XXX` lines, the H1 title, main-flow headings, alt-flow headings (`### A1: …` / `### 3a. …`),
  and business-rule sites (`### BR-XXX` headings or `- **GR-XXX:** …` bullets). The `isFirstMatchingLeafOnLine` helper
  walks `PsiTreeUtil.prevLeaf` back to the document line start to suppress duplicate markers when a regex match spans
  multiple PSI leaves on the same line — keep this guard if you add more Markdown patterns.

- **BPMN editor** (`BpmnFileEditor.kt`): `BpmnEditorProvider` opens `*.bpmn` files (mapped to the XML file type
  in `plugin.xml`) in a JCEF browser, placed before the text editor; `BpmnEditorSelector` selects that
  Diagram tab whenever a `.bpmn` file is opened (the IDE would otherwise restore the tab used last). The page is the bpmn-js modeler + properties
  panel from the AI Unified Process Studio (`../studio/src/main/frontend/bpmn-editor.ts`), kept in `bpmn-editor/`
  and bundled by esbuild (`bpmn-editor/build.mjs`, Gradle task `buildBpmnEditor`) into one self-contained
  `/bpmn-editor/editor.html` resource. The IDE replaces the `/*AIUP_BRIDGE*/` placeholder with a `JBCefJSQuery`
  function (`window.aiupBpmnSend`); the page sends `ready` / `changed:<xml>`, the IDE calls
  `window.aiupBpmn.setXml/init`. Diagram edits replace the document text in a write command; document edits are
  pushed back debounced. The editor toolbar's SVG/PNG export asks the page (`exportSvg`/`exportPng`), which answers
  `svg:<svg>` / `png:<base64>` / `export-failed:<message>`. Its Zoom In/Out/Fit actions call `window.aiupBpmn.zoom('in'|'out'|'fit')`, since the
  Ctrl/Cmd+wheel and pinch zoom of bpmn-js do not reliably reach the JCEF page. An activity whose name starts with a Use Case ID
  (`UC-004 Find Owners`, pattern `ACTIVITY_USE_CASE_REF`, handed to the page in `init`) gets an "Open use case"
  context pad entry (a click in the read-only viewer); the page sends `open-use-case:<id>` and the IDE opens the spec. Keep the bpmn-js versions in `bpmn-editor/package.json`
  in line with the studio.

- **BPMN Markdown fences** (`BpmnMarkdownPreview.kt`, `bpmn-editor/src/preview.ts`): a
  `MarkdownBrowserPreviewExtension` loads `/bpmn-editor/preview.js` into every Markdown preview. The script hides
  each `pre > code.language-bpmn` and inserts a rendering after it, re-inserting it after every incremental-DOM patch
  from a source-keyed cache. It deliberately does **not** use `CodeFenceGeneratingProvider` — that interface is
  `@ApiStatus.Internal` and fails the plugin verifier.

## Convention contract with consumer projects

The plugin assumes the host project follows this shape (from the `aiup-petclinic` example):

- Java annotation named `UseCase` (annotation type) with attributes `id: String`, `scenario: String`,
  `businessRules: String[]`.
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

The ID/filename/heading patterns live centrally in `UseCaseIndex` (`USE_CASE_ID_LINE`, `USE_CASE_TITLE`, `ACTIVITY_USE_CASE_REF`,
`SPEC_FILE_NAME`, `MAIN_SCENARIO_HEADING`, `ALT_FLOW_HEADING`, `BUSINESS_RULE_SITE`, `SCENARIO_CODE`);
`SpecToUseCaseLineMarkerProvider`, `UseCaseDeclarationProvider`, and `UseCaseUsageSearcher` reference those shared
values rather than defining their own copies — keep it that way when changing the patterns.

## Known inconsistency

`plugin.xml` registers extensions under `ai.unifiedprocess.tools.ij.*`, but the actual Kotlin classes live in package
`ai.unifiedprocess.tools.ij`. The plugin will fail to load until these are reconciled — pick one package and update both
sides.
