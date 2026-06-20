package com.moneymanager.ui.screens.qif

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.repository.AccountWriteRepository
import com.moneymanager.domain.repository.AttributeTypeWriteRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvAccountMappingWriteRepository
import com.moneymanager.domain.repository.CsvImportStrategyWriteRepository
import com.moneymanager.domain.repository.CsvImportWriteRepository
import com.moneymanager.domain.repository.CurrencyWriteRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.QifImportWriteRepository
import com.moneymanager.qifimporter.QifCsvAdapter
import com.moneymanager.ui.screens.csvstrategy.editor.CsvStrategyEditorScreen

/**
 * QIF strategy editor. QIF strategies are ordinary CSV import strategies whose columns are the
 * fixed QIF fields, so this loads the import's records as sample rows and delegates to
 * [CsvStrategyEditorScreen] with the fixed QIF columns.
 */
@Composable
fun QifStrategyEditorScreen(
    qifImportId: QifImportId,
    strategyId: CsvImportStrategyId?,
    qifImportRepository: QifImportWriteRepository,
    csvImportRepository: CsvImportWriteRepository,
    csvImportStrategyRepository: CsvImportStrategyWriteRepository,
    csvAccountMappingRepository: CsvAccountMappingWriteRepository,
    accountRepository: AccountWriteRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyWriteRepository,
    attributeTypeRepository: AttributeTypeWriteRepository,
    personRepository: PersonReadRepository,
    onBack: () -> Unit,
) {
    var sampleRows by remember { mutableStateOf<List<CsvRow>?>(null) }
    LaunchedEffect(qifImportId) {
        val count = qifImportRepository.countRecords(qifImportId)
        val records = qifImportRepository.getImportRecords(qifImportId, limit = count.coerceAtLeast(1), offset = 0)
        sampleRows = QifCsvAdapter.toRows(records)
    }

    val rows = sampleRows
    if (rows == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    CsvStrategyEditorScreen(
        csvImportId = null,
        strategyId = strategyId,
        csvImportRepository = csvImportRepository,
        columnsOverride = QifCsvAdapter.columns,
        sampleRowsOverride = rows,
        csvImportStrategyRepository = csvImportStrategyRepository,
        csvAccountMappingRepository = csvAccountMappingRepository,
        accountRepository = accountRepository,
        categoryRepository = categoryRepository,
        currencyRepository = currencyRepository,
        attributeTypeRepository = attributeTypeRepository,
        personRepository = personRepository,
        onBack = onBack,
    )
}
