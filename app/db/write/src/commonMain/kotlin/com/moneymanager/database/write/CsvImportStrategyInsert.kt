package com.moneymanager.database.write

import com.moneymanager.database.json.CsvStrategyJsonCodec
import com.moneymanager.database.sql.csvImportStrategy.CsvImportStrategyWriteQueries
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy

/**
 * Inserts a [CsvImportStrategy] row, serialising its config to JSON, plus its
 * `xlsx_import_strategy` satellite row when it targets an Excel worksheet. Shared by the runtime
 * repository (user-created strategies) and the seeder (built-in strategies). created_at/updated_at are filled by the table's DEFAULT (current time).
 */
fun CsvImportStrategyWriteQueries.insertStrategy(strategy: CsvImportStrategy) {
    insert(
        id = strategy.id.id.toString(),
        name = strategy.name,
        config_json = CsvStrategyJsonCodec.encode(strategy.config),
    )
    strategy.worksheetName?.let { worksheetName ->
        insertOrReplaceXlsxWorksheet(
            csv_import_strategy_id = strategy.id.id.toString(),
            worksheet_name = worksheetName,
        )
    }
}
