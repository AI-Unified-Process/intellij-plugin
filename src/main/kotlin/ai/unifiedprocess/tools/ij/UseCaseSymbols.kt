package ai.unifiedprocess.tools.ij

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.presentation.TargetPresentation

/**
 * Symbols that bridge `@UseCase`-annotated tests and Markdown specs.
 *
 * Each site (Java literal, Markdown heading, Markdown UC-ID line) declares
 * one of these symbols at its text range. Find Usages on any site resolves
 * to the symbol; the searcher then enumerates *all* sites declaring it, so
 * the user sees the full set of related test methods and spec locations.
 */
sealed interface UseCaseRelatedSymbol : Symbol, SearchTarget {
    val project: Project
    val useCaseId: String

    override fun createPointer(): Pointer<out UseCaseRelatedSymbol>
}

class UseCaseSymbol(
    override val project: Project,
    override val useCaseId: String,
) : UseCaseRelatedSymbol {
    override fun createPointer(): Pointer<UseCaseSymbol> = Pointer.hardPointer(this)
    override fun presentation(): TargetPresentation =
        TargetPresentation.builder(useCaseId).presentation()

    override val usageHandler: UsageHandler = UsageHandler.createEmptyUsageHandler(useCaseId)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is UseCaseSymbol && other.useCaseId == useCaseId && other.project == project)

    override fun hashCode(): Int = useCaseId.hashCode()
    override fun toString(): String = "UseCaseSymbol($useCaseId)"
}

class BusinessRuleSymbol(
    override val project: Project,
    override val useCaseId: String,
    val brId: String,
) : UseCaseRelatedSymbol {
    private val name = "$brId in $useCaseId"

    override fun createPointer(): Pointer<BusinessRuleSymbol> = Pointer.hardPointer(this)
    override fun presentation(): TargetPresentation = TargetPresentation.builder(name).presentation()
    override val usageHandler: UsageHandler = UsageHandler.createEmptyUsageHandler(name)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is BusinessRuleSymbol &&
                other.useCaseId == useCaseId && other.brId == brId && other.project == project)

    override fun hashCode(): Int = useCaseId.hashCode() * 31 + brId.hashCode()
    override fun toString(): String = "BusinessRuleSymbol($useCaseId/$brId)"
}

/**
 * `scenarioCode == null` means the Main Success Scenario; otherwise it's an
 * alt-flow code such as "A1".
 */
class ScenarioSymbol(
    override val project: Project,
    override val useCaseId: String,
    val scenarioCode: String?,
) : UseCaseRelatedSymbol {
    val displayName: String get() = scenarioCode ?: "Main Success Scenario"
    private val name = "$displayName in $useCaseId"

    override fun createPointer(): Pointer<ScenarioSymbol> = Pointer.hardPointer(this)
    override fun presentation(): TargetPresentation = TargetPresentation.builder(name).presentation()
    override val usageHandler: UsageHandler = UsageHandler.createEmptyUsageHandler(name)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ScenarioSymbol &&
                other.useCaseId == useCaseId && other.scenarioCode == scenarioCode &&
                other.project == project)

    override fun hashCode(): Int = useCaseId.hashCode() * 31 + (scenarioCode?.hashCode() ?: 0)
    override fun toString(): String = "ScenarioSymbol($useCaseId/$displayName)"
}
