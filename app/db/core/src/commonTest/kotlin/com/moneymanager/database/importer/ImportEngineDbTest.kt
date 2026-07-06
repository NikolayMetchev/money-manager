@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.importer
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountMergeRequest
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportCategoryIntent
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalCategoryKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importer.ImportEngineImpl
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ImportEngineDbTest : DbTest() {
    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun engine() =
        ImportEngineImpl(
            transactionRepository = repositories.transactionRepository,
            accountRepository = repositories.accountRepository,
            accountAttributeRepository = repositories.accountAttributeRepository,
            personRepository = repositories.personRepository,
            personAttributeRepository = repositories.personAttributeRepository,
            ownershipRepository = repositories.personAccountOwnershipRepository,
            categoryRepository = repositories.categoryRepository,
            currencyRepository = repositories.currencyRepository,
            attributeTypeRepository = repositories.attributeTypeRepository,
            relationshipTypeRepository = repositories.relationshipTypeRepository,
            csvImportStrategyRepository = repositories.csvImportStrategyRepository,
            apiImportStrategyRepository = repositories.apiImportStrategyRepository,
            accountMappingRepository = repositories.accountMappingRepository,
            csvImportRepository = repositories.csvImportRepository,
            qifImportRepository = repositories.qifImportRepository,
            apiSessionRepository = repositories.apiSessionRepository,
            settingsRepository = repositories.settingsRepository,
            importDirectoryRepository = repositories.importDirectoryRepository,
            passThroughAccountRepository = repositories.passThroughAccountRepository,
        )

    private suspend fun gbp(): Currency =
        repositories.currencyRepository
            .getAllCurrencies()
            .first()
            .first { it.code == "GBP" }

    private suspend fun createSourceAccount(): AccountId {
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Checking", openingDate = baseTime))
        return repositories.accountRepository
            .getAllAccounts()
            .first()
            .first { it.name == "Checking" }
            .id
    }

    private fun batchWithCounterparty(
        sourceId: AccountId,
        currency: Currency,
        description: String,
        source: Source,
        counterpartyKey: String = "coffee-shop",
        counterpartyName: String = "Coffee Shop",
    ): ImportBatch {
        val counterparty = LocalAccountKey(counterpartyKey)
        return ImportBatch(
            transfers =
                listOf(
                    ImportTransfer(
                        rowKey = ImportRowKey.CsvRow(0),
                        fromAccount = AccountRef.Existing(sourceId),
                        toAccount = AccountRef.Local(counterparty),
                        source = source,
                        timestamp = baseTime,
                        description = description,
                        amount = Money(500, currency),
                    ),
                ),
            dedupePolicy = DedupePolicy.FuzzyAllFields(),
            accountsToCreate =
                listOf(
                    ImportAccountIntent(
                        key = counterparty,
                        match = AccountMatchKey.ByName(counterpartyName),
                        name = counterpartyName,
                        openingDate = baseTime,
                        source = source,
                    ),
                ),
        )
    }

    @Test
    fun createsAccountAndTransfer_thenDedupesOnReimport() =
        runTest {
            val sourceId = createSourceAccount()
            val currency = gbp()
            val source = Source.SampleGenerator

            val first = engine().import(batchWithCounterparty(sourceId, currency, "Coffee", source))
            assertEquals(1, first.accountsCreated)
            assertEquals(1, first.transfersImported)
            assertEquals(0, first.duplicates)

            // Re-import the identical batch: counterparty reused by name, transfer deduped.
            val second = engine().import(batchWithCounterparty(sourceId, currency, "Coffee", source))
            assertEquals(0, second.accountsCreated)
            assertEquals(0, second.transfersImported)
            assertEquals(1, second.duplicates)

            val coffeeShopId =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Coffee Shop" }
                    .id
            val transfers = repositories.transactionRepository.getTransactionsByAccount(coffeeShopId).first()
            assertEquals(1, transfers.size)
        }

    private fun batchWithAttributes(
        sourceId: AccountId,
        currency: Currency,
        source: Source,
        attributes: List<NewAttribute>,
        description: String = "Coffee",
    ): ImportBatch =
        batchWithCounterparty(sourceId, currency, description, source).let { base ->
            base.copy(transfers = base.transfers.map { it.copy(attributes = attributes) })
        }

    /**
     * Re-importing a row whose existing transfer already carries an attribute of the same type must not
     * violate the UNIQUE(transaction_id, attribute_type_id) constraint: the update path upserts (updates a
     * changed value, keeps an unchanged one, inserts a genuinely new type) instead of blindly inserting.
     * Regression for a Crypto.com CSV re-import crash (existing transfer had an extra reconciliation
     * attribute, so the re-import was classified UPDATED and re-supplied its already-present attributes).
     */
    @Test
    fun reimportUpdatingAttributes_upsertsWithoutUniqueConstraintCrash() =
        runTest {
            val sourceId = createSourceAccount()
            val currency = gbp()
            val source = Source.SampleGenerator
            val extId = engine().getOrCreateAttributeType("external-id")
            val note = engine().getOrCreateAttributeType("note")

            // First import creates the transfer with two attributes.
            engine().import(
                batchWithAttributes(
                    sourceId,
                    currency,
                    source,
                    listOf(NewAttribute(extId, "abc"), NewAttribute(note, "keep")),
                ),
            )
            val transferId =
                repositories.transactionRepository
                    .getTransactionsByAccount(sourceId)
                    .first()
                    .single()
                    .id

            // Re-import the same row (same core fields) but drop one attribute and change the other's value.
            // Core fields match, attributes differ -> UPDATED; the re-supplied external-id already exists.
            val result =
                engine().import(
                    batchWithAttributes(sourceId, currency, source, listOf(NewAttribute(extId, "xyz"))),
                )
            assertEquals(0, result.transfersImported)
            assertEquals(1, result.updated)

            // external-id was updated in place; the untouched note attribute is preserved (nothing deleted).
            val attrs =
                repositories.transferAttributeRepository
                    .getByTransaction(transferId)
                    .first()
                    .associate { it.attributeType.name to it.value }
            assertEquals(mapOf("external-id" to "xyz", "note" to "keep"), attrs)
        }

    @Test
    fun dissimilarTransfer_isImportedNotDeduped() =
        runTest {
            val sourceId = createSourceAccount()
            val currency = gbp()
            val source = Source.SampleGenerator

            engine().import(
                batchWithCounterparty(sourceId, currency, "Tesco groceries weekly shop", source, "tesco", "Tesco"),
            )

            // A genuinely different description (low similarity) must NOT be treated as a fuzzy duplicate.
            val changedBatch =
                batchWithCounterparty(sourceId, currency, "British Gas direct debit", source, "tesco", "Tesco")
            val result = engine().import(changedBatch)
            assertEquals(1, result.transfersImported)
            assertEquals(0, result.duplicates)
            assertEquals(0, result.accountsCreated) // counterparty reused by name

            val tescoId =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Tesco" }
                    .id
            val transfers = repositories.transactionRepository.getTransactionsByAccount(tescoId).first()
            assertEquals(2, transfers.size)
        }

    @Test
    fun createsPeopleAndOwnership() =
        runTest {
            val sourceId = createSourceAccount()
            val currency = gbp()
            val source = Source.SampleGenerator
            val aliceKey = LocalPersonKey("alice")
            val bobKey = LocalPersonKey("bob")

            val batch =
                batchWithCounterparty(sourceId, currency, "Coffee", source).copy(
                    peopleToCreate =
                        listOf(
                            ImportPersonIntent(
                                key = aliceKey,
                                match = PersonMatchKey.ByNameKey("alice smith"),
                                firstName = "Alice",
                                lastName = "Smith",
                                source = source,
                            ),
                            ImportPersonIntent(
                                key = bobKey,
                                match = PersonMatchKey.ByNameKey("bob jones"),
                                firstName = "Bob",
                                lastName = "Jones",
                                source = source,
                            ),
                        ),
                    ownerships =
                        listOf(
                            ImportOwnershipIntent(personKey = aliceKey, account = AccountRef.Existing(sourceId), source = source),
                            ImportOwnershipIntent(personKey = bobKey, account = AccountRef.Existing(sourceId), source = source),
                        ),
                )

            val result = engine().import(batch)
            assertEquals(2, result.peopleCreated)
            assertEquals(2, result.ownershipsCreated)

            val people = repositories.personRepository.getAllPeople().first()
            assertEquals(2, people.size)
            assertEquals(setOf("Alice Smith", "Bob Jones"), people.map { it.fullName }.toSet())
            val ownerships = repositories.personAccountOwnershipRepository.getOwnershipsByAccount(sourceId).first()
            assertEquals(2, ownerships.size)
        }

    @Test
    fun categoryCreateUpdateDeleteViaImport() =
        runTest {
            val key = LocalCategoryKey("food")
            val created =
                engine().import(
                    ImportBatch.manualEdits(
                        categories = listOf(ImportCategoryIntent(key = key, source = Source.Manual, name = "Food")),
                    ),
                )
            val id = created.createdCategoryIds.getValue(key)
            assertTrue(
                repositories.categoryRepository
                    .getAllCategories()
                    .first()
                    .any { it.id == id && it.name == "Food" },
            )

            engine().import(
                ImportBatch.manualEdits(
                    categories =
                        listOf(
                            ImportCategoryIntent(
                                key = key,
                                source = Source.Manual,
                                operation = ImportOperation.UPDATE,
                                existingId = id,
                                category = Category(id = id, name = "Groceries"),
                            ),
                        ),
                ),
            )
            assertEquals(
                "Groceries",
                repositories.categoryRepository
                    .getCategoryById(id)
                    .first()
                    ?.name,
            )

            engine().import(
                ImportBatch.manualEdits(
                    categories =
                        listOf(
                            ImportCategoryIntent(
                                key = key,
                                source = Source.Manual,
                                operation = ImportOperation.DELETE,
                                existingId = id,
                            ),
                        ),
                ),
            )
            assertNull(repositories.categoryRepository.getCategoryById(id).first())
        }

    @Test
    fun createAccountThenOwnByLocalKey() =
        runTest {
            val accountKey = LocalAccountKey("savings")
            val personKey = LocalPersonKey("owner")
            val result =
                engine().import(
                    ImportBatch.manualEdits(
                        accounts =
                            listOf(
                                ImportAccountIntent(
                                    key = accountKey,
                                    source = Source.Manual,
                                    name = "Savings",
                                    openingDate = baseTime,
                                ),
                            ),
                        people =
                            listOf(
                                ImportPersonIntent(key = personKey, source = Source.Manual, firstName = "Dana"),
                            ),
                        ownerships =
                            listOf(
                                ImportOwnershipIntent(
                                    source = Source.Manual,
                                    personKey = personKey,
                                    account = AccountRef.Local(accountKey),
                                ),
                            ),
                    ),
                )
            val accountId = result.createdAccountIds.getValue(accountKey)
            val personId = result.createdPersonIds.getValue(personKey)
            val ownerships = repositories.personAccountOwnershipRepository.getOwnershipsByAccount(accountId).first()
            assertEquals(listOf(personId), ownerships.map { it.personId })
        }

    @Test
    fun accountUpdateAndDeleteViaImport() =
        runTest {
            val key = LocalAccountKey("acct")
            val accountId =
                engine()
                    .import(
                        ImportBatch.manualEdits(
                            accounts =
                                listOf(
                                    ImportAccountIntent(key = key, source = Source.Manual, name = "Old", openingDate = baseTime),
                                ),
                        ),
                    ).createdAccountIds
                    .getValue(key)

            engine().import(
                ImportBatch.manualEdits(
                    accounts =
                        listOf(
                            ImportAccountIntent(
                                key = key,
                                source = Source.Manual,
                                operation = ImportOperation.UPDATE,
                                existingId = accountId,
                                account =
                                    repositories.accountRepository
                                        .getAccountById(accountId)
                                        .first()!!
                                        .copy(name = "New"),
                            ),
                        ),
                ),
            )
            assertEquals(
                "New",
                repositories.accountRepository
                    .getAccountById(accountId)
                    .first()
                    ?.name,
            )

            engine().import(
                ImportBatch.manualEdits(
                    accounts =
                        listOf(
                            ImportAccountIntent(
                                key = key,
                                source = Source.Manual,
                                operation = ImportOperation.DELETE,
                                existingId = accountId,
                            ),
                        ),
                ),
            )
            assertNull(repositories.accountRepository.getAccountById(accountId).first())
        }

    @Test
    fun personDeleteViaImport() =
        runTest {
            val key = LocalPersonKey("p")
            val personId: PersonId =
                engine()
                    .import(
                        ImportBatch.manualEdits(
                            people = listOf(ImportPersonIntent(key = key, source = Source.Manual, firstName = "Temp")),
                        ),
                    ).createdPersonIds
                    .getValue(key)
            assertTrue(
                repositories.personRepository
                    .getAllPeople()
                    .first()
                    .any { it.id == personId },
            )

            engine().import(
                ImportBatch.manualEdits(
                    people =
                        listOf(
                            ImportPersonIntent(
                                key = key,
                                source = Source.Manual,
                                operation = ImportOperation.DELETE,
                                existingId = personId,
                            ),
                        ),
                ),
            )
            assertTrue(
                repositories.personRepository
                    .getAllPeople()
                    .first()
                    .none { it.id == personId },
            )
        }

    @Test
    fun accountMergeAndUnmergeViaImport() =
        runTest {
            val currency = gbp()
            val keepKey = LocalAccountKey("keep")
            val dropKey = LocalAccountKey("drop")
            val funded = LocalAccountKey("funded")
            // Create two accounts plus one transfer into the dropped account, so the merge reassigns it.
            val created =
                engine().import(
                    ImportBatch.manualEdits(
                        accounts =
                            listOf(
                                ImportAccountIntent(key = keepKey, source = Source.Manual, name = "Keep", openingDate = baseTime),
                                ImportAccountIntent(key = dropKey, source = Source.Manual, name = "Drop", openingDate = baseTime),
                                ImportAccountIntent(key = funded, source = Source.Manual, name = "Funder", openingDate = baseTime),
                            ),
                        transfers =
                            listOf(
                                ImportTransfer(
                                    source = Source.Manual,
                                    fromAccount = AccountRef.Local(funded),
                                    toAccount = AccountRef.Local(dropKey),
                                    timestamp = baseTime,
                                    description = "seed",
                                    amount = Money.fromDisplayValue("5", currency),
                                ),
                            ),
                    ),
                )
            val keepId = created.createdAccountIds.getValue(keepKey)
            val dropId = created.createdAccountIds.getValue(dropKey)

            engine().import(
                ImportBatch.manualEdits(accountMerges = listOf(AccountMergeRequest(deletedId = dropId, survivingId = keepId))),
            )
            assertNull(repositories.accountRepository.getAccountById(dropId).first())

            val merge =
                repositories.accountRepository
                    .getReversibleMerges()
                    .first()
                    .first()
            engine().import(ImportBatch.manualEdits(accountUnmerges = listOf(merge.id)))
            assertTrue(repositories.accountRepository.getAccountById(dropId).first() != null)
        }

    @Test
    fun passThrough_expandsIntoTwoLinkedLegs_conduitNetsToZero() =
        runTest {
            val cardId = createSourceAccount()
            val currency = gbp()
            val source = Source.SampleGenerator
            val curve = LocalAccountKey("curve")
            val merchant = LocalAccountKey("merchant")
            val amount = Money(1010, currency)

            val result =
                engine().import(
                    ImportBatch(
                        transfers =
                            listOf(
                                // The funding leg: card -> Curve. The engine synthesises the spend leg
                                // (Curve -> merchant) from [passThrough] and links the two.
                                ImportTransfer(
                                    rowKey = ImportRowKey.CsvRow(0),
                                    fromAccount = AccountRef.Existing(cardId),
                                    toAccount = AccountRef.Local(curve),
                                    source = source,
                                    timestamp = baseTime,
                                    description = "Curve",
                                    amount = amount,
                                    passThrough =
                                        ImportPassThrough(
                                            conduits = listOf(AccountRef.Local(curve)),
                                            merchantTarget = AccountRef.Local(merchant),
                                            amount = amount,
                                            spendDescriptions = listOf("National Lottery"),
                                            relationshipTypeId =
                                                RelationshipTypeId(WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID),
                                        ),
                                ),
                            ),
                        dedupePolicy = DedupePolicy.FuzzyAllFields(),
                        accountsToCreate =
                            listOf(
                                ImportAccountIntent(
                                    key = curve,
                                    match = AccountMatchKey.ByName("Curve"),
                                    name = "Curve",
                                    openingDate = baseTime,
                                    source = source,
                                ),
                                ImportAccountIntent(
                                    key = merchant,
                                    match = AccountMatchKey.ByName("National Lottery"),
                                    name = "National Lottery",
                                    openingDate = baseTime,
                                    source = source,
                                ),
                            ),
                    ),
                )

            // Only the funding leg counts as an imported transfer (the spend leg is interleaved like a fee).
            assertEquals(2, result.accountsCreated)
            assertEquals(1, result.transfersImported)

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val curveId = accounts.first { it.name == "Curve" }.id
            val merchantId = accounts.first { it.name == "National Lottery" }.id

            // The conduit nets to zero: one leg in (card -> Curve), one out (Curve -> merchant).
            val curveRows = repositories.transactionRepository.getTransactionsByAccount(curveId).first()
            assertEquals(2, curveRows.size)
            val curveNet =
                curveRows.sumOf { t ->
                    when (curveId) {
                        t.targetAccountId -> t.amount.amount
                        t.sourceAccountId -> -t.amount.amount
                        else -> 0L
                    }
                }
            assertEquals(0L, curveNet)

            // The merchant receives the spend exactly once.
            val merchantRows = repositories.transactionRepository.getTransactionsByAccount(merchantId).first()
            assertEquals(1, merchantRows.size)

            // The funding leg (id1) links to the spend leg (id2) via the pass-through relationship.
            val fundingId = result.createdTransferIds.getValue(ImportRowKey.CsvRow(0))
            val relationship =
                repositories.transferRelationshipRepository
                    .getByTransfer(fundingId)
                    .first()
                    .single()
            assertEquals(fundingId, relationship.id1)
            assertEquals("pass-through", relationship.relationshipType.name)
        }

    @Test
    fun passThrough_chainExpandsIntoLinkedLegs_everyConduitNetsToZero() =
        runTest {
            val cardId = createSourceAccount()
            val currency = gbp()
            val source = Source.SampleGenerator
            val curve = LocalAccountKey("curve")
            val paypal = LocalAccountKey("paypal")
            val merchant = LocalAccountKey("merchant")
            val amount = Money(2700, currency)

            val result =
                engine().import(
                    ImportBatch(
                        transfers =
                            listOf(
                                // Funding leg card -> Curve; the engine synthesises Curve -> PayPal and
                                // PayPal -> merchant, chained via pass-through relationships.
                                ImportTransfer(
                                    rowKey = ImportRowKey.CsvRow(0),
                                    fromAccount = AccountRef.Existing(cardId),
                                    toAccount = AccountRef.Local(curve),
                                    source = source,
                                    timestamp = baseTime,
                                    description = "CRV*PAYPAL *THEPIHUT 0",
                                    amount = amount,
                                    passThrough =
                                        ImportPassThrough(
                                            conduits = listOf(AccountRef.Local(curve), AccountRef.Local(paypal)),
                                            merchantTarget = AccountRef.Local(merchant),
                                            amount = amount,
                                            spendDescriptions = listOf("PAYPAL *THEPIHUT 0", "THEPIHUT 0"),
                                            relationshipTypeId =
                                                RelationshipTypeId(WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID),
                                        ),
                                ),
                            ),
                        dedupePolicy = DedupePolicy.FuzzyAllFields(),
                        accountsToCreate =
                            listOf(
                                ImportAccountIntent(
                                    key = curve,
                                    match = AccountMatchKey.ByName("Curve"),
                                    name = "Curve",
                                    openingDate = baseTime,
                                    source = source,
                                ),
                                ImportAccountIntent(
                                    key = paypal,
                                    match = AccountMatchKey.ByName("PayPal"),
                                    name = "PayPal",
                                    openingDate = baseTime,
                                    source = source,
                                ),
                                ImportAccountIntent(
                                    key = merchant,
                                    match = AccountMatchKey.ByName("THEPIHUT 0"),
                                    name = "THEPIHUT 0",
                                    openingDate = baseTime,
                                    source = source,
                                ),
                            ),
                    ),
                )

            assertEquals(3, result.accountsCreated)
            assertEquals(1, result.transfersImported)

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val curveId = accounts.first { it.name == "Curve" }.id
            val paypalId = accounts.first { it.name == "PayPal" }.id
            val merchantId = accounts.first { it.name == "THEPIHUT 0" }.id

            // Every intermediate conduit nets to zero; the merchant receives the spend exactly once.
            for (conduitId in listOf(curveId, paypalId)) {
                val rows = repositories.transactionRepository.getTransactionsByAccount(conduitId).first()
                assertEquals(2, rows.size)
                val net =
                    rows.sumOf { t ->
                        when (conduitId) {
                            t.targetAccountId -> t.amount.amount
                            t.sourceAccountId -> -t.amount.amount
                            else -> 0L
                        }
                    }
                assertEquals(0L, net)
            }
            val merchantRows = repositories.transactionRepository.getTransactionsByAccount(merchantId).first()
            assertEquals(1, merchantRows.size)

            // Relationships chain main -> leg1 -> leg2 (each id1 links to the next leg as id2).
            val fundingId2 = result.createdTransferIds.getValue(ImportRowKey.CsvRow(0))
            val leg1 = spendLegOf(fundingId2)
            val leg2 = spendLegOf(leg1)
            val leg1Transfer = repositories.transactionRepository.getTransactionById(leg1.id).first()
            val leg2Transfer = repositories.transactionRepository.getTransactionById(leg2.id).first()
            assertEquals("PAYPAL *THEPIHUT 0", leg1Transfer?.description)
            assertEquals(curveId, leg1Transfer?.sourceAccountId)
            assertEquals(paypalId, leg1Transfer?.targetAccountId)
            assertEquals("THEPIHUT 0", leg2Transfer?.description)
            assertEquals(paypalId, leg2Transfer?.sourceAccountId)
            assertEquals(merchantId, leg2Transfer?.targetAccountId)
        }

    @Test
    fun passThrough_chainCancellation_linksEachLegToItsOriginal() =
        runTest {
            val cardId = createSourceAccount()
            val currency = gbp()
            val source = Source.SampleGenerator
            val curve = LocalAccountKey("curve")
            val paypal = LocalAccountKey("paypal")
            val merchant = LocalAccountKey("merchant")
            val amount = Money(2700, currency)

            fun chainRow(
                rowIndex: Long,
                timestamp: Instant,
                description: String,
                incoming: Boolean,
            ) = ImportTransfer(
                rowKey = ImportRowKey.CsvRow(rowIndex),
                fromAccount = if (incoming) AccountRef.Local(curve) else AccountRef.Existing(cardId),
                toAccount = if (incoming) AccountRef.Existing(cardId) else AccountRef.Local(curve),
                source = source,
                timestamp = timestamp,
                description = description,
                amount = amount,
                passThrough =
                    ImportPassThrough(
                        conduits = listOf(AccountRef.Local(curve), AccountRef.Local(paypal)),
                        merchantTarget = AccountRef.Local(merchant),
                        amount = amount,
                        spendDescriptions = listOf("PAYPAL *THEPIHUT 0", "THEPIHUT 0"),
                        relationshipTypeId = RelationshipTypeId(WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID),
                        incoming = incoming,
                    ),
            )

            val result =
                engine().import(
                    ImportBatch(
                        transfers =
                            listOf(
                                chainRow(0, baseTime, "CRV*PAYPAL *THEPIHUT 0", incoming = false),
                                chainRow(1, baseTime.plus(1.hours), "Cancellation: Crv*Paypal *Thepihut 0", incoming = true),
                            ),
                        dedupePolicy = DedupePolicy.None,
                        accountsToCreate =
                            listOf(
                                ImportAccountIntent(
                                    key = curve,
                                    match = AccountMatchKey.ByName("Curve"),
                                    name = "Curve",
                                    openingDate = baseTime,
                                    source = source,
                                ),
                                ImportAccountIntent(
                                    key = paypal,
                                    match = AccountMatchKey.ByName("PayPal"),
                                    name = "PayPal",
                                    openingDate = baseTime,
                                    source = source,
                                ),
                                ImportAccountIntent(
                                    key = merchant,
                                    match = AccountMatchKey.ByName("THEPIHUT 0"),
                                    name = "THEPIHUT 0",
                                    openingDate = baseTime,
                                    source = source,
                                ),
                            ),
                    ),
                )
            assertEquals(2, result.transfersImported)

            val chargeLeg1 = spendLegOf(result.createdTransferIds.getValue(ImportRowKey.CsvRow(0)))
            val chargeLeg2 = spendLegOf(chargeLeg1)
            val cancelLeg1 = spendLegOf(result.createdTransferIds.getValue(ImportRowKey.CsvRow(1)))
            val cancelLeg2 = spendLegOf(cancelLeg1)

            // Each cancellation leg reverses the corresponding original leg (per-hop pairing), and
            // every chain account nets to zero across charge + cancellation.
            val cancelLeg1Reversal = reversalLinksOf(cancelLeg1).single()
            assertEquals(cancelLeg1, cancelLeg1Reversal.id1)
            assertEquals(chargeLeg1, cancelLeg1Reversal.id2)
            val cancelLeg2Reversal = reversalLinksOf(cancelLeg2).single()
            assertEquals(cancelLeg2, cancelLeg2Reversal.id1)
            assertEquals(chargeLeg2, cancelLeg2Reversal.id2)

            val accounts = repositories.accountRepository.getAllAccounts().first()
            for (name in listOf("Curve", "PayPal", "THEPIHUT 0")) {
                val accountId = accounts.first { it.name == name }.id
                val net =
                    repositories.transactionRepository
                        .getTransactionsByAccount(accountId)
                        .first()
                        .sumOf { t ->
                            when (accountId) {
                                t.targetAccountId -> t.amount.amount
                                t.sourceAccountId -> -t.amount.amount
                                else -> 0L
                            }
                        }
                assertEquals(0L, net, "account $name should net to zero")
            }

            // The originals are consumed: a second identical cancellation finds nothing to reverse.
            // The account intents resolve to the already-created accounts by name (nothing new is made).
            val second =
                engine().import(
                    ImportBatch(
                        transfers =
                            listOf(chainRow(2, baseTime.plus(2.hours), "Cancellation: Crv*Paypal *Thepihut 0", incoming = true)),
                        dedupePolicy = DedupePolicy.None,
                        accountsToCreate =
                            listOf(
                                ImportAccountIntent(
                                    key = curve,
                                    match = AccountMatchKey.ByName("Curve"),
                                    name = "Curve",
                                    openingDate = baseTime,
                                    source = source,
                                ),
                                ImportAccountIntent(
                                    key = paypal,
                                    match = AccountMatchKey.ByName("PayPal"),
                                    name = "PayPal",
                                    openingDate = baseTime,
                                    source = source,
                                ),
                                ImportAccountIntent(
                                    key = merchant,
                                    match = AccountMatchKey.ByName("THEPIHUT 0"),
                                    name = "THEPIHUT 0",
                                    openingDate = baseTime,
                                    source = source,
                                ),
                            ),
                    ),
                )
            val secondCancelLeg1 = spendLegOf(second.createdTransferIds.getValue(ImportRowKey.CsvRow(2)))
            val secondCancelLeg2 = spendLegOf(secondCancelLeg1)
            assertEquals(emptyList(), reversalLinksOf(secondCancelLeg1))
            assertEquals(emptyList(), reversalLinksOf(secondCancelLeg2))
        }

    /** A pass-through row: the funding leg card <-> Curve plus the [ImportPassThrough] spend leg. */
    private fun passThroughRow(
        rowIndex: Long,
        cardId: AccountId,
        curve: LocalAccountKey,
        merchant: LocalAccountKey,
        amount: Money,
        timestamp: Instant,
        description: String,
        incoming: Boolean,
        merchantName: String = "Navan",
    ) = ImportTransfer(
        rowKey = ImportRowKey.CsvRow(rowIndex),
        fromAccount = if (incoming) AccountRef.Local(curve) else AccountRef.Existing(cardId),
        toAccount = if (incoming) AccountRef.Existing(cardId) else AccountRef.Local(curve),
        source = Source.SampleGenerator,
        timestamp = timestamp,
        description = description,
        amount = amount,
        passThrough =
            ImportPassThrough(
                conduits = listOf(AccountRef.Local(curve)),
                merchantTarget = AccountRef.Local(merchant),
                amount = amount,
                spendDescriptions = listOf(merchantName),
                relationshipTypeId = RelationshipTypeId(WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID),
                incoming = incoming,
            ),
    )

    private fun passThroughAccountIntents(
        curve: LocalAccountKey,
        merchant: LocalAccountKey,
        merchantName: String = "Navan",
    ) = listOf(
        ImportAccountIntent(
            key = curve,
            match = AccountMatchKey.ByName("Curve"),
            name = "Curve",
            openingDate = baseTime,
            source = Source.SampleGenerator,
        ),
        ImportAccountIntent(
            key = merchant,
            match = AccountMatchKey.ByName(merchantName),
            name = merchantName,
            openingDate = baseTime,
            source = Source.SampleGenerator,
        ),
    )

    /** The next leg linked FROM [fundingId] via its outgoing pass-through relationship (chains walk leg by leg). */
    private suspend fun spendLegOf(fundingId: TransferId): TransferId =
        repositories.transferRelationshipRepository
            .getByTransfer(fundingId)
            .first()
            .single { it.relationshipType.name == "pass-through" && it.id1 == fundingId }
            .id2

    private suspend fun reversalLinksOf(transferId: TransferId) =
        repositories.transferRelationshipRepository
            .getByTransfer(transferId)
            .first()
            .filter { it.relationshipType.name == WellKnownIds.REVERSAL_RELATIONSHIP_TYPE_NAME }

    @Test
    fun passThrough_incomingRefund_reversesLegsAndLinksToChargeInSameBatch() =
        runTest {
            val cardId = createSourceAccount()
            val currency = gbp()
            val curve = LocalAccountKey("curve")
            val merchant = LocalAccountKey("merchant")
            val amount = Money(36358, currency)

            val result =
                engine().import(
                    ImportBatch(
                        transfers =
                            listOf(
                                passThroughRow(0, cardId, curve, merchant, amount, baseTime, "Crv*Navan", incoming = false),
                                passThroughRow(
                                    1,
                                    cardId,
                                    curve,
                                    merchant,
                                    amount,
                                    baseTime.plus(1.hours),
                                    "Refund: Crv*Navan",
                                    incoming = true,
                                ),
                            ),
                        dedupePolicy = DedupePolicy.None,
                        accountsToCreate = passThroughAccountIntents(curve, merchant),
                    ),
                )
            assertEquals(2, result.transfersImported)

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val curveId = accounts.first { it.name == "Curve" }.id
            val merchantId = accounts.first { it.name == "Navan" }.id

            // The refund's funding leg runs conduit -> card and its spend leg merchant -> conduit.
            val refundFundingId = result.createdTransferIds.getValue(ImportRowKey.CsvRow(1))
            val refundFunding =
                repositories.transactionRepository
                    .getTransactionById(refundFundingId.id)
                    .first()!!
            assertEquals(curveId, refundFunding.sourceAccountId)
            assertEquals(cardId, refundFunding.targetAccountId)
            val refundSpendId = spendLegOf(refundFundingId)
            val refundSpend = repositories.transactionRepository.getTransactionById(refundSpendId.id).first()!!
            assertEquals(merchantId, refundSpend.sourceAccountId)
            assertEquals(curveId, refundSpend.targetAccountId)

            // Charge + refund cancel out on both the conduit and the merchant.
            listOf(curveId, merchantId).forEach { accountId ->
                val net =
                    repositories.transactionRepository
                        .getTransactionsByAccount(accountId)
                        .first()
                        .sumOf { t ->
                            when (accountId) {
                                t.targetAccountId -> t.amount.amount
                                t.sourceAccountId -> -t.amount.amount
                                else -> 0L
                            }
                        }
                assertEquals(0L, net, "account $accountId should net to zero")
            }

            // The refund's spend leg (id1) links to the original charge's spend leg (id2) via the
            // seeded reversal relationship type.
            val chargeSpendId = spendLegOf(result.createdTransferIds.getValue(ImportRowKey.CsvRow(0)))
            val reversal = reversalLinksOf(refundSpendId).single()
            assertEquals(refundSpendId, reversal.id1)
            assertEquals(chargeSpendId, reversal.id2)
            // The seeded reversal relationship type carries id 4 (see StaticSeed.sq).
            assertEquals(RelationshipTypeId(4), reversal.relationshipType.id)
        }

    @Test
    fun passThrough_refundInLaterImport_linksToPersistedCharge() =
        runTest {
            val cardId = createSourceAccount()
            val currency = gbp()
            val curve = LocalAccountKey("curve")
            val merchant = LocalAccountKey("merchant")
            val amount = Money(36358, currency)

            val first =
                engine().import(
                    ImportBatch(
                        transfers =
                            listOf(passThroughRow(0, cardId, curve, merchant, amount, baseTime, "Crv*Navan", incoming = false)),
                        dedupePolicy = DedupePolicy.None,
                        accountsToCreate = passThroughAccountIntents(curve, merchant),
                    ),
                )
            val second =
                engine().import(
                    ImportBatch(
                        transfers =
                            listOf(
                                passThroughRow(
                                    0,
                                    cardId,
                                    curve,
                                    merchant,
                                    amount,
                                    baseTime.plus(1.hours),
                                    "Refund: Crv*Navan",
                                    incoming = true,
                                ),
                            ),
                        dedupePolicy = DedupePolicy.None,
                        accountsToCreate = passThroughAccountIntents(curve, merchant),
                    ),
                )

            val chargeSpendId = spendLegOf(first.createdTransferIds.getValue(ImportRowKey.CsvRow(0)))
            val refundSpendId = spendLegOf(second.createdTransferIds.getValue(ImportRowKey.CsvRow(0)))
            val reversal = reversalLinksOf(refundSpendId).single()
            assertEquals(refundSpendId, reversal.id1)
            assertEquals(chargeSpendId, reversal.id2)
        }

    @Test
    fun passThrough_chainOfRefundsAndReversals_linksPairwiseConsumingEachLegOnce() =
        runTest {
            val cardId = createSourceAccount()
            val currency = gbp()
            val curve = LocalAccountKey("curve")
            val merchant = LocalAccountKey("merchant")
            val amount = Money(36358, currency)

            // The observed Crypto.com sequence: charge, refund, refund reversal, refund again.
            val result =
                engine().import(
                    ImportBatch(
                        transfers =
                            listOf(
                                passThroughRow(0, cardId, curve, merchant, amount, baseTime, "Crv*Navan", incoming = false),
                                passThroughRow(
                                    1,
                                    cardId,
                                    curve,
                                    merchant,
                                    amount,
                                    baseTime.plus(1.hours),
                                    "Refund: Crv*Navan",
                                    incoming = true,
                                ),
                                passThroughRow(
                                    2,
                                    cardId,
                                    curve,
                                    merchant,
                                    amount,
                                    baseTime.plus(2.hours),
                                    "Refund reversal: Crv*Navan",
                                    incoming = false,
                                ),
                                passThroughRow(
                                    3,
                                    cardId,
                                    curve,
                                    merchant,
                                    amount,
                                    baseTime.plus(3.hours),
                                    "Refund: Crv*Navan",
                                    incoming = true,
                                ),
                            ),
                        dedupePolicy = DedupePolicy.None,
                        accountsToCreate = passThroughAccountIntents(curve, merchant),
                    ),
                )

            val spendIds =
                (0L..3L).map { spendLegOf(result.createdTransferIds.getValue(ImportRowKey.CsvRow(it))) }
            // Pairwise: refund -> charge, reversal -> refund, second refund -> reversal.
            (1..3).forEach { i ->
                val reversal = reversalLinksOf(spendIds[i]).single { it.id1 == spendIds[i] }
                assertEquals(spendIds[i - 1], reversal.id2, "row $i should reverse row ${i - 1}")
            }
            // Each leg is consumed at most once: exactly 3 links, all id2s distinct.
            val allLinks = spendIds.flatMap { reversalLinksOf(it) }.distinct()
            assertEquals(3, allLinks.size)
            assertEquals(3, allLinks.map { it.id2 }.toSet().size)
        }

    @Test
    fun passThrough_refundInLaterChunk_linksAcrossChunkBoundary() =
        runTest {
            val cardId = createSourceAccount()
            val currency = gbp()
            val curve = LocalAccountKey("curve")
            val merchant = LocalAccountKey("merchant")
            val amount = Money(36358, currency)

            // batchSize = 1 puts every row in its own chunk (own transaction), so the refund's in-batch
            // reversal target lives in an earlier chunk and must resolve to the already-created real id.
            val result =
                engine().import(
                    ImportBatch(
                        transfers =
                            listOf(
                                passThroughRow(0, cardId, curve, merchant, amount, baseTime, "Crv*Navan", incoming = false),
                                passThroughRow(
                                    1,
                                    cardId,
                                    curve,
                                    merchant,
                                    amount,
                                    baseTime.plus(1.hours),
                                    "Refund: Crv*Navan",
                                    incoming = true,
                                ),
                            ),
                        dedupePolicy = DedupePolicy.None,
                        accountsToCreate = passThroughAccountIntents(curve, merchant),
                    ),
                    batchSize = 1,
                )
            assertEquals(2, result.transfersImported)

            val chargeSpendId = spendLegOf(result.createdTransferIds.getValue(ImportRowKey.CsvRow(0)))
            val refundSpendId = spendLegOf(result.createdTransferIds.getValue(ImportRowKey.CsvRow(1)))
            val reversal = reversalLinksOf(refundSpendId).single()
            assertEquals(refundSpendId, reversal.id1)
            assertEquals(chargeSpendId, reversal.id2)
        }

    @Test
    fun passThrough_refundWithDifferentAmount_getsNoReversalLink() =
        runTest {
            val cardId = createSourceAccount()
            val currency = gbp()
            val curve = LocalAccountKey("curve")
            val merchant = LocalAccountKey("merchant")

            val result =
                engine().import(
                    ImportBatch(
                        transfers =
                            listOf(
                                passThroughRow(0, cardId, curve, merchant, Money(36358, currency), baseTime, "Crv*Navan", incoming = false),
                                passThroughRow(
                                    1,
                                    cardId,
                                    curve,
                                    merchant,
                                    Money(12345, currency),
                                    baseTime.plus(1.hours),
                                    "Refund: Crv*Navan",
                                    incoming = true,
                                ),
                            ),
                        dedupePolicy = DedupePolicy.None,
                        accountsToCreate = passThroughAccountIntents(curve, merchant),
                    ),
                )

            val refundSpendId = spendLegOf(result.createdTransferIds.getValue(ImportRowKey.CsvRow(1)))
            assertTrue(reversalLinksOf(refundSpendId).isEmpty())
        }

    @Test
    fun passThrough_repeatChargesSameDirection_getNoReversalLink() =
        runTest {
            val cardId = createSourceAccount()
            val currency = gbp()
            val curve = LocalAccountKey("curve")
            val merchant = LocalAccountKey("merchant")
            val amount = Money(1400, currency)

            val result =
                engine().import(
                    ImportBatch(
                        transfers =
                            listOf(
                                passThroughRow(0, cardId, curve, merchant, amount, baseTime, "Crv*Zipcar Trip Oct05", incoming = false),
                                passThroughRow(
                                    1,
                                    cardId,
                                    curve,
                                    merchant,
                                    amount,
                                    baseTime.plus(1.hours),
                                    "Crv*Zipcar Trip Oct05",
                                    incoming = false,
                                ),
                            ),
                        dedupePolicy = DedupePolicy.None,
                        accountsToCreate = passThroughAccountIntents(curve, merchant),
                    ),
                )

            (0L..1L).forEach { row ->
                val spendId = spendLegOf(result.createdTransferIds.getValue(ImportRowKey.CsvRow(row)))
                assertTrue(reversalLinksOf(spendId).isEmpty(), "row $row must not link")
            }
        }

    @Test
    fun updateWithoutExistingIdFailsValidation() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                engine().import(
                    ImportBatch.manualEdits(
                        categories =
                            listOf(
                                ImportCategoryIntent(
                                    key = LocalCategoryKey("x"),
                                    source = Source.Manual,
                                    operation = ImportOperation.UPDATE,
                                    category = Category(id = 1, name = "X"),
                                ),
                            ),
                    ),
                )
            }
        }
}
