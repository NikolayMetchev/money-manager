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
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvAccountMappingRepository
import com.moneymanager.domain.repository.CsvImportRepository
import com.moneymanager.domain.repository.CsvImportStrategyRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.QifImportRepository
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
    qifImportRepository: QifImportRepository,
    csvImportRepository: CsvImportRepository,
    csvImportStrategyRepository: CsvImportStrategyRepository,
    csvAccountMappingRepository: CsvAccountMappingRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    attributeTypeRepository: AttributeTypeRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
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
        personAccountOwnershipRepository = personAccountOwnershipRepository,
        onBack = onBack,
    )
}
