package com.moneymanager.ui.screens.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.currency.Currency
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.importengineapi.setDefaultCurrency
import com.moneymanager.importengineapi.setSetupWizardCompleted
import com.moneymanager.importfilesource.DriveFolderBrowser
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.remotestorage.sync.StrategySyncController
import com.moneymanager.strategycatalog.StrategyCatalogController
import com.moneymanager.ui.AppServices
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.screens.apistrategy.ApiConnectionsScreen
import com.moneymanager.ui.screens.importdirectory.ImportDirectoriesScreen
import com.moneymanager.ui.screens.settings.StrategyCatalogScreen
import com.moneymanager.ui.screens.settings.StrategyCloudCard
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * The guided setup for a database: default currency → strategy catalog → strategy cloud sync → import
 * folders → API credentials. It runs automatically until it is finished or skipped (a per-database flag in
 * the `settings` table) and can be re-run from Settings at any time.
 *
 * The database-location step happens before any database exists, so it lives in `FirstRunDatabaseSetupScreen`
 * and only shows up here as an already-completed marker in the indicator ([includeDatabaseStep]).
 *
 * Each step body is the same composable the user would reach through normal navigation, so whatever they do
 * here is applied immediately — the wizard adds ordering and explanation, not a parallel set of writes.
 */
@Composable
@Suppress("LongParameterList")
fun SetupWizardScreen(
    services: AppServices,
    appVersion: AppVersion,
    strategyCatalogController: StrategyCatalogController?,
    strategySyncController: StrategySyncController?,
    importFileSourceFactory: ImportFileSourceFactory?,
    driveFolderBrowser: DriveFolderBrowser?,
    includeDatabaseStep: Boolean,
    onFinished: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberSchemaAwareCoroutineScope()

    val apiStrategies by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        services.imports.apiImportStrategyRepository.getAllStrategies()
    }

    val steps =
        setupWizardSteps(
            includeDatabaseStep = includeDatabaseStep,
            strategyCatalogAvailable = strategyCatalogController != null,
            strategySyncAvailable = strategySyncController != null,
            importDirectoriesAvailable = importFileSourceFactory != null,
            hasApiStrategies = apiStrategies.isNotEmpty(),
        )
    // The step list grows while the wizard is open (installing an API strategy adds the API step), so track
    // the step itself rather than an index into a list that shifts under us.
    val navigableSteps = steps.filter { it != SetupWizardStep.DATABASE }
    var currentStep by remember { mutableStateOf(SetupWizardStep.CURRENCY) }

    var selectedCurrencyId by remember { mutableStateOf<CurrencyId?>(null) }
    // Prefer the currency this database already uses (a re-run should show the current default, not reset it),
    // and fall back to the locale's currency on a fresh database. The locale need not name one we know.
    LaunchedEffect(Unit) {
        if (selectedCurrencyId != null) return@LaunchedEffect
        val existing =
            services.settings.settingsRepository
                .getDefaultCurrencyId()
                .firstOrNull()
        val localeCurrency =
            Currency.getDefaultCurrencyCode()?.let { code ->
                services.accounts.currencyRepository
                    .getCurrencyByCode(code)
                    .firstOrNull()
            }
        selectedCurrencyId = existing ?: localeCurrency?.id
    }

    fun finish() {
        scope.launch {
            importEngine.setSetupWizardCompleted(true)
            onFinished()
        }
    }

    fun advance() {
        val next = navigableSteps.getOrNull(navigableSteps.indexOf(currentStep) + 1)
        if (next == null) finish() else currentStep = next
    }

    fun goBack() {
        navigableSteps.getOrNull(navigableSteps.indexOf(currentStep) - 1)?.let { currentStep = it }
    }

    fun onNext() {
        if (currentStep == SetupWizardStep.CURRENCY) {
            val id = selectedCurrencyId ?: return
            scope.launch { importEngine.setDefaultCurrency(id) }
        }
        advance()
    }

    val isLastStep = currentStep == navigableSteps.lastOrNull()
    SetupWizardScaffold(
        steps = steps,
        currentStep = currentStep,
        canGoBack = navigableSteps.indexOf(currentStep) > 0,
        canSkip = currentStep != SetupWizardStep.CURRENCY && !isLastStep,
        nextEnabled = currentStep != SetupWizardStep.CURRENCY || selectedCurrencyId != null,
        nextLabel = if (isLastStep) "Finish" else "Next",
        onBack = ::goBack,
        onSkip = ::advance,
        onNext = ::onNext,
        onExit = ::finish,
    ) {
        StepBody(
            step = currentStep,
            services = services,
            appVersion = appVersion,
            strategyCatalogController = strategyCatalogController,
            strategySyncController = strategySyncController,
            importFileSourceFactory = importFileSourceFactory,
            driveFolderBrowser = driveFolderBrowser,
            selectedCurrencyId = selectedCurrencyId,
            onCurrencySelected = { selectedCurrencyId = it },
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun ColumnScope.StepBody(
    step: SetupWizardStep,
    services: AppServices,
    appVersion: AppVersion,
    strategyCatalogController: StrategyCatalogController?,
    strategySyncController: StrategySyncController?,
    importFileSourceFactory: ImportFileSourceFactory?,
    driveFolderBrowser: DriveFolderBrowser?,
    selectedCurrencyId: CurrencyId?,
    onCurrencySelected: (CurrencyId) -> Unit,
) {
    when (step) {
        // Chosen before the database existed, so it is never rendered as a body.
        SetupWizardStep.DATABASE -> Unit
        SetupWizardStep.CURRENCY ->
            CurrencyPicker(
                selectedCurrencyId = selectedCurrencyId,
                onCurrencySelected = onCurrencySelected,
                label = "Default Currency",
                currencyRepository = services.accounts.currencyRepository,
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            )
        SetupWizardStep.STRATEGIES ->
            if (strategyCatalogController != null) {
                StrategyCatalogScreen(
                    controller = strategyCatalogController,
                    library = services.imports.strategyLibrary,
                    appVersion = appVersion,
                    accountRepository = services.accounts.accountRepository,
                    categoryRepository = services.accounts.categoryRepository,
                    currencyRepository = services.accounts.currencyRepository,
                    personRepository = services.people.personRepository,
                    showBackAction = false,
                )
            }
        SetupWizardStep.STRATEGY_SYNC ->
            if (strategySyncController != null) {
                // The card grows with the strategy list and the wizard body doesn't scroll on its own,
                // so it would be clipped on a phone once the list is longer than the step.
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    StrategyCloudCard(
                        controller = strategySyncController,
                        library = services.imports.strategyLibrary,
                        appVersion = appVersion,
                        accountRepository = services.accounts.accountRepository,
                        categoryRepository = services.accounts.categoryRepository,
                        currencyRepository = services.accounts.currencyRepository,
                        personRepository = services.people.personRepository,
                    )
                }
            }
        SetupWizardStep.IMPORT_DIRECTORIES ->
            ImportDirectoriesScreen(
                importDirectoryRepository = services.imports.importDirectoryRepository,
                csvImportRepository = services.imports.csvImportRepository,
                qifImportRepository = services.imports.qifImportRepository,
                deviceId = services.deviceId,
                importFileSourceFactory = importFileSourceFactory,
                driveFolderBrowser = driveFolderBrowser,
            )
        SetupWizardStep.API_IMPORTS ->
            ApiConnectionsScreen(
                apiImportStrategyRepository = services.imports.apiImportStrategyRepository,
                apiSessionRepository = services.imports.apiSessionRepository,
                // The wizard prints the step title and owns Back/Next, so the screen shows neither.
                showHeader = false,
            )
    }
}
