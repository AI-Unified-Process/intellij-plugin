plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.15.0"
}

group = "ai.unifiedprocess.tools"
version = "0.6.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1")

        // Bundled plugins we need
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.intellij.plugins.markdown")

        pluginVerifier()
        zipSigner()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    // MIT-licensed PlantUML build with the embedded Smetana layout engine: the activity
    // diagram tool window renders in-process, no Graphviz and no external service needed.
    implementation("net.sourceforge.plantuml:plantuml-mit:1.2025.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

tasks.test {
    useJUnit()
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            // No upper bound: keeps the plugin installable on 262+ without a re-release per IDE version.
            untilBuild = provider { null }
        }
        changeNotes = """
            <h3>0.6.0</h3>
            <ul>
                <li>New "AIUP Diagram" tool window: shows a live PlantUML activity diagram of the Use Case spec
                    in the selected editor, generated from the Main Success Scenario and the Alternative Flows
                    and rendered in-process (MIT-licensed PlantUML with the Smetana engine — no Graphviz, no
                    external service).</li>
            </ul>
            <h3>0.5.1</h3>
            <ul>
                <li>Removed the <code>until-build</code> upper bound so the plugin remains compatible with IntelliJ IDEA 2026.2 (262) and later releases without requiring a re-release per IDE version.</li>
            </ul>
            <h3>0.5.0</h3>
            <ul>
                <li>Support extended Use Case ID formats: letter-suffixed IDs (e.g. <code>UC-015a</code>) and multi-segment IDs (e.g. <code>UC-LOGIN-001</code>) in gutter navigation, spec-file matching, and Find Usages.</li>
            </ul>
            <h3>0.4.3</h3>
            <ul>
                <li>Recognise <code>Hauptszenario</code> as the German equivalent of <code>Main Success Scenario</code>: gutter navigation, Find Usages, and the <code>scenario</code> attribute on <code>@UseCase</code> now accept either label.</li>
            </ul>
            <h3>0.4.2</h3>
            <ul>
                <li>Fixed two override-only API violations flagged by the JetBrains plugin verifier: <code>UseCaseUsageSearcher</code> now explicitly overrides <code>collectSearchRequest</code> and <code>collectImmediateResults</code>, preventing Kotlin from generating bridge methods that invoke the <code>@ApiStatus.OverrideOnly</code> defaults on <code>Searcher</code>.</li>
            </ul>
            <h3>0.4.1</h3>
            <ul>
                <li>Replaced the deprecated <code>ReadAction.compute</code> calls with <code>ReadAction.computeBlocking</code> for compatibility with the 2026.1 platform API.</li>
            </ul>
            <h3>0.4.0</h3>
            <ul>
                <li>"Create UseCase.java" action now prompts for a Java package (defaulting to a <code>src/test/java</code> source root) instead of a directory, and writes a matching <code>package</code> declaration into the generated file.</li>
            </ul>
            <h3>0.3.0</h3>
            <ul>
                <li>Detect projects that have Use Case specs but no <code>UseCase</code> annotation type and offer a one-time balloon with a "Create UseCase.java" action that scaffolds the annotation into a chosen source root.</li>
            </ul>
            <h3>0.2.0</h3>
            <ul>
                <li>Improved Find usage.</li>
            </ul>
            <h3>0.1.0</h3>
            <ul>
                <li>Initial release: gutter-icon navigation between <code>@UseCase</code>-annotated test methods and their Markdown specs.</li>
                <li>Find Usages support for <code>UC-XXX</code> identifiers via <code>UseCaseDeclarationProvider</code> and <code>UseCaseUsageSearcher</code>.</li>
                <li>Inspection: warns when a <code>@UseCase(id = "UC-XXX")</code> has no matching spec file.</li>
            </ul>
        """.trimIndent()
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // channels = listOf("stable")  // uncomment + change to "eap"/"beta" for pre-releases
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    wrapper {
        gradleVersion = "9.4.1"
    }
}
