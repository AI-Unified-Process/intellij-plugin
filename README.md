# AIUP Use Case Navigator

IntelliJ Plugin to navigate between `@UseCase` annotated test methods and
their Markdown specs in AIUP projects.

## Features

* Gutter icon on `@UseCase(id = "UC-XXX")` jumps to the matching
  `UC-XXX-*.md` file under your spec folder.
* Gutter icon on `**Use Case ID:** UC-XXX` lines in Markdown jumps to all
  test methods annotated with that ID.
* Gutter icon on `### BR-XXX` business rule headings jumps to all test
  methods that reference that rule via `businessRules = {"BR-XXX"}`.

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
**Use Case ID:** UC-002

### BR-001: Lazy Loading
```

The plugin finds the `UseCase` annotation in your project automatically by
short name, so you do not need to configure a fully qualified name.

## Build

```bash
./gradlew build
```

The plugin zip will be in `build/distributions/`.

## Try it locally

```bash
./gradlew runIde
```

This launches a sandbox IntelliJ with the plugin installed. Open your
`aiup-petclinic` project in it.

## Install in your IDE

* `./gradlew buildPlugin`
* In IntelliJ: `Settings` -> `Plugins` -> gear icon -> `Install Plugin from Disk...`
* Pick the zip file from `build/distributions/`.

## Compatibility

Targets IntelliJ IDEA 2024.3 and later (build 243+). Adjust the `sinceBuild`
and `untilBuild` in `build.gradle.kts` if you need a different range.

## Notes

* The spec lookup scans Markdown files in the project. For very large
  projects you may want to add an index later. For typical AIUP repos the
  scan is fast enough because the spec folder is small.
* If you rename the annotation, the lookup still works as long as it is
  called `UseCase` and is an annotation type.
