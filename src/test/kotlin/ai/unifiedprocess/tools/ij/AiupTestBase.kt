package ai.unifiedprocess.tools.ij

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Strips the `<html>...</html>` wrapper that some tooltip producers add so
 * tests can assert against the plain text the user actually sees.
 */
internal fun List<GutterMark>.tooltips(): List<String> = mapNotNull { it.tooltipText }
    .map { it.removePrefix("<html>").removeSuffix("</html>").trim() }

/**
 * Base class for AIUP plugin tests. Adds a project-local `UseCase` annotation
 * to mimic the convention contract from real consumer projects. Individual
 * tests skip [addUseCaseAnnotation] when they want to assert behavior in a
 * project that has *no* annotation (e.g. the setup-activity probe).
 */
abstract class AiupTestBase : BasePlatformTestCase() {

    protected open val withUseCaseAnnotation: Boolean get() = true

    override fun setUp() {
        super.setUp()
        if (withUseCaseAnnotation) {
            addUseCaseAnnotation()
        }
    }

    protected fun addUseCaseAnnotation(packageName: String = "ai.unifiedprocess.tools") {
        val pkgPath = if (packageName.isEmpty()) "" else packageName.replace('.', '/') + "/"
        val pkgDecl = if (packageName.isEmpty()) "" else "package $packageName;\n\n"
        val source = pkgDecl +
            """
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.METHOD, ElementType.TYPE})
            public @interface UseCase {
                String id();
                String scenario() default "";
                String[] businessRules() default {};
            }
            """.trimIndent()
        myFixture.addFileToProject("${pkgPath}UseCase.java", source)
    }
}
