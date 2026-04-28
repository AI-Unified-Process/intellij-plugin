plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.15.0"
}

group = "ai.unifiedprocess.tools"
version = "0.4.2"

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
            untilBuild = "261.*"
        }
        changeNotes = """
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
