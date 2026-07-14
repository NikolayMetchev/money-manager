package com.moneymanager.strategycatalog

import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.strategy.CsvResolution
import com.moneymanager.domain.strategy.CsvUnresolvedReference
import com.moneymanager.domain.strategy.LocalStrategyEntry
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.domain.strategy.StrategyLibrary
import com.moneymanager.domain.strategy.StrategyParseResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrategyCatalogControllerTest {
    private val version = AppVersion("test")

    // The library fake hashes by json content ("hash:<json>"), so catalog-vs-local comparison is
    // driven purely by what applyIncoming recorded.
    private class FakeLibrary : StrategyLibrary {
        val installed = mutableMapOf<StrategyKey, String>()
        var failOnApply = false
        var failOnKey: StrategyKey? = null

        override suspend fun listLocal(appVersion: AppVersion): List<LocalStrategyEntry> =
            installed.map { (key, json) -> LocalStrategyEntry(key, json, canonicalHash(key, json)) }

        override fun canonicalHash(
            key: StrategyKey,
            json: String,
        ): String = "hash:$json"

        override suspend fun parseIncoming(
            key: StrategyKey,
            json: String,
        ): StrategyParseResult = StrategyParseResult(key, emptyList())

        override suspend fun applyIncoming(
            key: StrategyKey,
            json: String,
            resolutions: Map<CsvUnresolvedReference, CsvResolution>,
        ) {
            check(!failOnApply && key != failOnKey) { "apply failed" }
            installed[key] = json
        }
    }

    private val monzoKey = StrategyKey(StrategyKind.CSV, "Monzo CSV")
    private val curveKey = StrategyKey(StrategyKind.PASS_THROUGH, "Curve")

    private fun manifest(): String =
        CatalogManifestCodec.encode(
            CatalogManifest(
                listOf(
                    CatalogEntry("Monzo CSV", StrategyKind.CSV, "Monzo CSV.csv.json", "hash:monzo-json"),
                    CatalogEntry("Curve", StrategyKind.PASS_THROUGH, "Curve.passthrough.json", "hash:curve-json"),
                ),
            ),
        )

    private fun controller(failWith: HttpStatusCode? = null): StrategyCatalogController {
        val engine =
            MockEngine { request ->
                if (failWith != null) return@MockEngine respondError(failWith)
                when (
                    request.url.encodedPath
                        .decodeURLPart()
                        .substringAfterLast('/')
                ) {
                    "index.json" -> respond(manifest())
                    "Monzo CSV.csv.json" -> respond("monzo-json")
                    "Curve.passthrough.json" -> respond("curve-json")
                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        return StrategyCatalogController(StrategyCatalogClient(HttpClient(engine), "https://example.test/site"))
    }

    @Test
    fun refreshClassifiesInstalledUpdateAvailableAndNotInstalled() =
        runTest {
            val library = FakeLibrary()
            library.installed[monzoKey] = "monzo-json" // same hash as the catalog
            library.installed[curveKey] = "edited-curve-json" // differs from the catalog

            val controller = controller()
            controller.refresh(library, version)

            val state = controller.state.value
            assertNull(state.error)
            assertEquals(
                mapOf(
                    curveKey to CatalogItemStatus.UPDATE_AVAILABLE,
                    monzoKey to CatalogItemStatus.INSTALLED,
                ),
                state.items.associate { it.entry.key to it.status },
            )
        }

    @Test
    fun fetchFailureSurfacesErrorAndKeepsLastGoodItems() =
        runTest {
            val library = FakeLibrary()
            val controller = controller()
            controller.refresh(library, version)
            assertEquals(2, controller.state.value.items.size)

            val failing = controller(failWith = HttpStatusCode.ServiceUnavailable)
            failing.refresh(library, version)
            assertNotNull(failing.state.value.error)
            assertTrue(
                failing.state.value.items
                    .isEmpty(),
                "no prior success on this controller",
            )

            // A failure after a success keeps the previously loaded items.
            var flag = false
            val flaky =
                StrategyCatalogController(
                    StrategyCatalogClient(
                        HttpClient(
                            MockEngine { request ->
                                if (flag) {
                                    respondError(HttpStatusCode.ServiceUnavailable)
                                } else {
                                    when (
                                        request.url.encodedPath
                                            .decodeURLPart()
                                            .substringAfterLast('/')
                                    ) {
                                        "index.json" -> respond(manifest())
                                        else -> respondError(HttpStatusCode.NotFound)
                                    }
                                }
                            },
                        ),
                        "https://example.test/site",
                    ),
                )
            flaky.refresh(library, version)
            assertEquals(2, flaky.state.value.items.size)
            flag = true
            flaky.refresh(library, version)
            assertNotNull(flaky.state.value.error)
            assertEquals(2, flaky.state.value.items.size, "items from the last success are kept")
        }

    @Test
    fun installAppliesArtifactsAndRefreshesToInstalled() =
        runTest {
            val library = FakeLibrary()
            val controller = controller()
            controller.refresh(library, version)

            val installed = controller.install(library, version, setOf(monzoKey, curveKey))

            assertEquals(2, installed)
            assertEquals("monzo-json", library.installed[monzoKey])
            assertEquals("curve-json", library.installed[curveKey])
            assertTrue(
                controller.state.value.items
                    .all { it.status == CatalogItemStatus.INSTALLED },
            )
        }

    @Test
    fun installFailurePublishesErrorAndRethrows() =
        runTest {
            val library = FakeLibrary()
            val controller = controller()
            controller.refresh(library, version)

            library.failOnApply = true
            controller.beginBusy()
            assertFailsWith<IllegalStateException> {
                controller.install(library, version, setOf(monzoKey))
            }
            assertNotNull(controller.state.value.error)
            assertEquals(false, controller.state.value.busy)
        }

    @Test
    fun partialInstallFailureStillRefreshesAppliedEntries() =
        runTest {
            val library = FakeLibrary()
            val controller = controller()
            controller.refresh(library, version)

            // Curve applies (sorts first); Monzo fails. The refresh must still surface Curve as
            // installed, with the failure republished on top of the fresh state.
            library.failOnKey = monzoKey
            assertFailsWith<IllegalStateException> {
                controller.install(library, version, setOf(monzoKey, curveKey))
            }
            val state = controller.state.value
            assertNotNull(state.error)
            assertEquals(
                CatalogItemStatus.INSTALLED,
                state.items.first { it.entry.key == curveKey }.status,
                "the entry applied before the failure shows as installed",
            )
            assertEquals(CatalogItemStatus.NOT_INSTALLED, state.items.first { it.entry.key == monzoKey }.status)
        }

    @Test
    fun previewInstallReportsParseResultsPerKey() =
        runTest {
            val library = FakeLibrary()
            val controller = controller()
            controller.refresh(library, version)

            val previews = controller.previewInstall(library, setOf(monzoKey))
            assertEquals(listOf(monzoKey), previews.map { it.key })
        }
}
