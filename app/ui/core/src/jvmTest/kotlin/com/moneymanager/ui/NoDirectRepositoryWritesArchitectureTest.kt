package com.moneymanager.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Architecture guard: the UI must perform every entity mutation through the [com.moneymanager.importengineapi.ImportEngine]
 * (the single, gated write entry point), never by calling a repository's write method directly. This is
 * what lets editing be locked centrally when a cloud-backed database's remote copy is ahead.
 *
 * It scans the module's `commonMain` Kotlin sources for direct calls to the known entity-write methods.
 * Reads, `attributeTypeRepository.getOrCreate` (a benign lookup-or-create), and the engine's own
 * delegations (which live in another module) are deliberately not matched.
 */
class NoDirectRepositoryWritesArchitectureTest {
    private val forbiddenWrites =
        listOf(
            "createTransfers",
            "updateTransfer",
            "deleteTransaction",
            "createAccount",
            "updateAccountWithAttributes",
            "deleteAccount",
            "mergeAccounts",
            "unmergeAccount",
            "createCategory",
            "updateCategory",
            "deleteCategory",
            "createPerson",
            "updatePersonWithAttributes",
            "deletePerson",
            "createOwnership",
            "deleteOwnership",
        )

    private val forbidden =
        Regex("""\b\w*[Rr]epository\.(${forbiddenWrites.joinToString("|")})\s*\(""")

    @Test
    fun uiNeverCallsRepositoryWriteMethodsDirectly() {
        val sourceRoot =
            listOf(File("src/commonMain/kotlin"), File("app/ui/core/src/commonMain/kotlin"))
                .firstOrNull { it.isDirectory }
                ?: fail("Could not locate app/ui/core commonMain sources from ${File(".").absolutePath}")

        val violations =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().withIndex().mapNotNull { (i, line) ->
                        forbidden.find(line)?.let { "${file.relativeTo(sourceRoot)}:${i + 1}: ${line.trim()}" }
                    }
                }.toList()

        if (violations.isNotEmpty()) {
            fail(
                "UI code must route entity writes through ImportEngine (LocalImportEngine.current), not call " +
                    "repositories directly. Offending call(s):\n" + violations.joinToString("\n"),
            )
        }
    }
}
