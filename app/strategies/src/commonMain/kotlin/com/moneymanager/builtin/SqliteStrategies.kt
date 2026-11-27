package com.moneymanager.builtin

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.ReconciliationConfig
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object SqliteStrategies {
    fun gnucash(now: Instant): CsvImportStrategy =
        ledgerStrategy(now, "GnuCash", "00000000-0000-0000-0000-000000000030", "gnucash_id", GNUCASH_QUERY)

    fun moneyManager(now: Instant): CsvImportStrategy =
        ledgerStrategy(now, "Money Manager", "00000000-0000-0000-0000-000000000031", "mm_id", MONEY_MANAGER_QUERY)

    private fun ledgerStrategy(
        now: Instant,
        source: String,
        id: String,
        idColumn: String,
        query: String,
    ): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.parse(id)),
            name = source,
            createdAt = now,
            updatedAt = now,
            config =
                CsvStrategyConfig(
                    sqliteQuery = query.trimIndent(),
                    identificationColumns =
                        setOf(
                            idColumn,
                            "posted",
                            "description",
                            "source",
                            "target",
                            "amount",
                            "asset",
                            "received",
                            "to_asset",
                            "excluded",
                            "source_rows",
                        ),
                    reconciliation = ReconciliationConfig(source, "$source · "),
                    fieldMappings =
                        mapOf(
                            accountFromRules(TransferField.SOURCE_ACCOUNT),
                            accountFromRules(TransferField.TARGET_ACCOUNT),
                            TransferField.TIMESTAMP to
                                DateTimeParsingMapping(
                                    TransferField.TIMESTAMP,
                                    "posted",
                                    "yyyy-MM-dd",
                                    dateTimeFormat = "yyyy-MM-dd HH:mm:ss.SSS",
                                ),
                            TransferField.TIMEZONE to HardCodedTimezoneMapping(TransferField.TIMEZONE, "UTC"),
                            TransferField.DESCRIPTION to
                                DirectColumnMapping(TransferField.DESCRIPTION, ValueExpr.of("description", idColumn)),
                            TransferField.AMOUNT to AmountParsingMapping(TransferField.AMOUNT, AmountMode.SINGLE_COLUMN, "amount"),
                            TransferField.CURRENCY to CurrencyLookupMapping(TransferField.CURRENCY, ValueExpr.of("asset")),
                            TransferField.TO_AMOUNT to AmountParsingMapping(TransferField.TO_AMOUNT, AmountMode.SINGLE_COLUMN, "received"),
                            TransferField.TO_CURRENCY to CurrencyLookupMapping(TransferField.TO_CURRENCY, ValueExpr.of("to_asset")),
                        ),
                    rules =
                        listOf(
                            accounts(AccountSide.SOURCE, naming("source")),
                            accounts(AccountSide.TARGET, naming("target")),
                            attribute(idColumn, idColumn, unique = true),
                            excludeWhen("excluded", "^1$", "Excluded in source ledger"),
                        ),
                ),
        )

    // Opposite postings form one movement when their quantities can be represented by a transfer or trade.
    // Otherwise retain each posting against a non-linkable shadow clearing account, including zero postings.
    private const val GNUCASH_QUERY = """
        WITH RECURSIVE paths(guid, path) AS (
            SELECT guid, '' FROM accounts WHERE parent_guid IS NULL
            UNION ALL
            SELECT a.guid, CASE WHEN p.path = '' THEN a.name ELSE p.path || ':' || a.name END
            FROM accounts a JOIN paths p ON p.guid = a.parent_guid
        ), legs AS (
            SELECT s.guid, s.tx_guid, 'GnuCash · ' || p.path AS wallet, c.mnemonic AS asset,
                   decimal_ratio(s.quantity_num, s.quantity_denom) AS quantity, s.quantity_num AS signed_num,
                   count(*) OVER (PARTITION BY s.tx_guid) AS leg_count
            FROM splits s JOIN paths p ON p.guid = s.account_guid
            JOIN accounts a ON a.guid = s.account_guid JOIN commodities c ON c.guid = a.commodity_guid
        ), pairs AS (
            SELECT d.tx_guid, d.guid AS debit_guid, r.guid AS credit_guid,
                   d.wallet AS source, r.wallet AS target, substr(d.quantity, 2) AS amount,
                   d.asset, CASE WHEN r.asset <> d.asset THEN r.quantity END AS received,
                   CASE WHEN r.asset <> d.asset THEN r.asset END AS to_asset
            FROM legs d JOIN legs r ON r.tx_guid = d.tx_guid AND r.signed_num > 0
            WHERE d.leg_count = 2 AND d.signed_num < 0 AND
                  (d.asset <> r.asset OR (d.wallet <> r.wallet AND substr(d.quantity, 2) = r.quantity))
        ), movements AS (
            SELECT tx_guid AS gnucash_id, tx_guid, source, target, amount, asset, received, to_asset,
                   json_array(json_object('table','transactions','key',json_object('guid',tx_guid)),
                              json_object('table','splits','key',json_object('guid',debit_guid)),
                              json_object('table','splits','key',json_object('guid',credit_guid))) AS source_rows
            FROM pairs
            UNION ALL
            SELECT l.guid, l.tx_guid,
                   CASE WHEN l.signed_num < 0 THEN l.wallet ELSE 'GnuCash: Split clearing' END,
                   CASE WHEN l.signed_num < 0 THEN 'GnuCash: Split clearing' ELSE l.wallet END,
                   ltrim(l.quantity, '-'), l.asset, NULL, NULL,
                   json_array(json_object('table','splits','key',json_object('guid',l.guid)),
                              json_object('table','transactions','key',json_object('guid',l.tx_guid)))
            FROM legs l WHERE NOT EXISTS (SELECT 1 FROM pairs p WHERE p.tx_guid = l.tx_guid)
        )
        SELECT m.gnucash_id, strftime('%Y-%m-%d %H:%M:%f', t.post_date) AS posted, t.description,
               m.source, m.target, m.amount, m.asset, m.received, m.to_asset, 0 AS excluded, m.source_rows
        FROM movements m JOIN transactions t ON t.guid = m.tx_guid ORDER BY posted, m.gnucash_id
    """

    private const val MONEY_MANAGER_QUERY = """
        WITH movements AS (
            SELECT id, timestamp, description, source_account_id, target_account_id, amount, asset_id,
                   NULL AS received, NULL AS to_asset_id,
                   EXISTS (SELECT 1 FROM transfer_attribute a WHERE a.transaction_id = t.id AND a.attribute_type_id = -1) AS excluded
            FROM transfer t
            UNION ALL
            SELECT id, timestamp, description, from_account_id, to_account_id, from_amount, from_asset_id,
                   to_amount, to_asset_id,
                   EXISTS (SELECT 1 FROM trade_attribute a WHERE a.trade_id = t.id AND a.attribute_type_id = -1)
            FROM trade t
        )
        SELECT m.id AS mm_id, strftime('%Y-%m-%d %H:%M:%f', m.timestamp / 1000.0, 'unixepoch') AS posted,
               m.description, 'Money Manager · ' || s.name AS source, 'Money Manager · ' || t.name AS target,
               decimal_ratio(m.amount, a.scale_factor) AS amount, a.code AS asset,
               CASE WHEN b.id IS NOT NULL THEN decimal_ratio(m.received, b.scale_factor) END AS received,
               b.code AS to_asset, m.excluded,
               json_array(json_object('table', CASE WHEN b.id IS NULL THEN 'transfer' ELSE 'trade' END,
                                      'key', json_object('id', cast(m.id AS text)))) AS source_rows
        FROM movements m JOIN account s ON s.id = m.source_account_id JOIN account t ON t.id = m.target_account_id
        JOIN asset_details a ON a.id = m.asset_id LEFT JOIN asset_details b ON b.id = m.to_asset_id
        ORDER BY m.timestamp, m.id
    """
}
