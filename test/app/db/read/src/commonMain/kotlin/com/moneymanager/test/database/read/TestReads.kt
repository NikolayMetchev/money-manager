package com.moneymanager.test.database.read

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.database.rawsql.RawSql
import com.moneymanager.database.sql.testread.MoneyManagerDatabase
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper

/** The test-only read queries (`test/app/db/read`), over the same driver as the production ones. */
val MoneyManagerDatabaseWrapper.testReads: MoneyManagerDatabase
    get() = MoneyManagerDatabase(driver)

/** Prefix for the dynamically created CSV import tables. */
private const val CSV_TABLE_PREFIX = "csv_import_"

/**
 * Tables to exclude from audit trail.
 * Includes audit tables themselves, materialized views, system tables, and CSV import tables.
 */
private val EXCLUDED_FROM_AUDIT =
    setOf(
        "audit_type",
        "account_audit",
        "currency_audit",
        "crypto_audit",
        "trade_audit",
        "exchange_order_audit",
        "exchange_order_trade",
        "asset",
        "category_audit",
        "transfer_audit",
        "person_audit",
        "person_attribute_audit",
        "person_account_ownership_audit",
        "transfer_attribute_audit",
        "trade_attribute_audit",
        "account_attribute_audit",
        "api_import_strategy_audit",
        "api_import_strategy_source",
        "csv_import_strategy_audit",
        "csv_import_strategy_source",
        "import_directory_provider",
        "import_directory",
        "import_directory_audit",
        "import_directory_source",
        "import_directory_file",
        "import_directory_file_csv_import",
        "android_metadata",
        "account_balance_materialized_view",
        "running_balance_materialized_view",
        "pending_materialized_view_changes",
        "sqlite_sequence",
        "account_mapping",
        "reconciliation_account_link",
        "trade_attribute",
        "csv_column_metadata",
        "csv_import_error",
        "csv_import_metadata",
        "csv_import_strategy",
        "xlsx_import_blob",
        "pdf_import_blob",
        "pdf_import_line",
        "xlsx_import_strategy",
        "transaction_id",
        "source_type",
        "entity_source",
        "csv_entity_source",
        "entity_type",
        "api_entity_source",
        "platform",
        "os",
        "machine",
        "device_make",
        "device_model",
        "device",
        "attribute_type",
        "transfer_attribute",
        "relationship_type",
        "pass_through_account",
        "pass_through_rule",
        "pass_through_account_audit",
        "holiday_calendar",
        "holiday_calendar_country",
        "holiday_calendar_weekend_day",
        "holiday_calendar_day",
        "holiday_calendar_audit",
        "asset_alias",
        "asset_alias_audit",
        "relationship",
        "relationship_member",
        "account_attribute",
        "person_attribute",
        "_import_batch",
        "_creation_mode",
        // Account-merge reversal records; they are the audit/undo data, not audited entities.
        "account_merge",
        "account_merge_transfer",
        "account_merge_attribute",
        "account_merge_statement_source",
        "settings",
        "api_credential",
        "api_session",
        "api_import",
        "api_request",
        "api_request_header",
        "api_response",
        "api_response_transaction",
        "api_download_coverage",
    )

/** Whether a table is outside the audit trail. */
fun isExcludedFromAudit(tableName: String): Boolean = tableName in EXCLUDED_FROM_AUDIT || tableName.startsWith(CSV_TABLE_PREFIX)

/**
 * Gets all auditable tables from the database: every table but those in the excluded set, sorted by name.
 */
suspend fun MoneyManagerDatabaseWrapper.getAuditableTables(): List<String> = selectTableNames().filterNot(::isExcludedFromAudit)

/** Gets all column names for a table, in the order they appear in it. */
suspend fun MoneyManagerDatabaseWrapper.getTableColumns(tableName: String): List<String> =
    // pragma_table_info takes the table as an argument, which SQLDelight's grammar has no table-valued function for.
    RawSql(driver).query("PRAGMA table_info($tableName)") { checkNotNull(it.getString(1)) }.awaitAsList()

// sqlite_master isn't a table SQLDelight knows, so the schema is read through RawSql.

/** Every table, sorted by name. */
suspend fun MoneyManagerDatabaseWrapper.selectTableNames(): List<String> =
    selectNames("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")

/** Every table and view. */
suspend fun MoneyManagerDatabaseWrapper.selectTableAndViewNames(): List<String> =
    selectNames("SELECT name FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' ORDER BY type, name")

/** Every audit table. */
suspend fun MoneyManagerDatabaseWrapper.selectAuditTableNames(): List<String> =
    selectNames("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE '%\\_audit' ESCAPE '\\'")

private suspend fun MoneyManagerDatabaseWrapper.selectNames(sql: String): List<String> =
    RawSql(driver).query(sql) { checkNotNull(it.getString(0)) }.awaitAsList()
