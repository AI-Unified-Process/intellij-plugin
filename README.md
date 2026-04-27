# AI Unified Process Navigator

IntelliJ plugin to navigate between `@UseCase`-annotated Java test methods and their Markdown specs in AIUP projects.

## Features

### Gutter icons

In Java:

* `@UseCase(id = "UC-XXX")` jumps to the matching `UC-XXX-*.md` spec, landing on the scenario heading and any business
  rule headings referenced via `businessRules = {...}`.

In Markdown specs:

* `**Use Case ID:** UC-XXX` jumps to all test methods annotated with that ID.
* `# Title` (H1) jumps to the test class(es) containing those methods.
* `## Main Success Scenario` jumps to test methods with no `scenario` attribute (or `scenario = "Main Success
  Scenario"`).
* `### A1: ...` (alternative-flow headings of the form `<Letter><Digits>`) jumps to test methods whose `scenario`
  starts with that code.
* `### BR-XXX` business rule headings jump to test methods that reference that rule via `businessRules = {"BR-XXX"}`,
  scoped to the Use Case declared by the spec file (BR ids are unique only within a UC).

### Find Usages (Alt+F7)

Find Usages is wired in both directions, mirroring the gutter icons:

* On a `@UseCase` annotation or its `id` literal — finds spec leaves (scenario heading + BR headings).
* On a string inside `businessRules = {...}` — finds the matching `### BR-XXX` heading in the spec.
* On `**Use Case ID:** UC-XXX`, the H1 title, `## Main Success Scenario`, `### A1: ...`, and `### BR-XXX` lines —
  finds the corresponding test methods or test classes.

### Inspection

* **Use Case ID has no matching spec** — flags `@UseCase(id = "UC-XXX")` whose ID has no spec file in the project.

## Conventions used

The plugin works with the conventions from the AIUP PetClinic example:

```java

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UseCase {
    String id();

    String scenario() default "Main Success Scenario";

    String[] businessRules() default {};
}
```

```markdown
# View Veterinarians

**Use Case ID:** UC-002

## Main Success Scenario

### A1: No Veterinarians Found

### BR-001: Lazy Loading
```

The plugin finds the `UseCase` annotation in your project automatically by short name, so you do not need to configure
a fully qualified name.

## Build

```bash
./gradlew build
```

The plugin zip will be in `build/distributions/`.

## Try it locally

```bash
./gradlew runIde
```

This launches a sandbox IntelliJ with the plugin installed. Open your `aiup-petclinic` project in it.

## Install in your IDE

* `./gradlew buildPlugin`
* In IntelliJ: `Settings` -> `Plugins` -> gear icon -> `Install Plugin from Disk...`
* Pick the zip file from `build/distributions/`.

## Compatibility

Targets IntelliJ IDEA 2026.1 (build 261+). Adjust `sinceBuild` / `untilBuild` and the platform dependency in
`build.gradle.kts` if you need a different range. Requires the bundled Markdown plugin.

## Notes

* The spec lookup scans Markdown files in the project. For very large projects you may want to add an index later. For
  typical AIUP repos the scan is fast enough because the spec folder is small.
* If you rename the annotation, the lookup still works as long as it is called `UseCase` and is an annotation type.
