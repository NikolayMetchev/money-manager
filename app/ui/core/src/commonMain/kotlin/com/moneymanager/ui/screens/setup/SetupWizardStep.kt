package com.moneymanager.ui.screens.setup

/**
 * The steps of the setup wizard, in order.
 *
 * [DATABASE] is chosen before any database exists (by `FirstRunDatabaseSetupScreen`, outside this wizard),
 * so it is only ever rendered as an already-completed marker in the step indicator — never as a step body.
 */
enum class SetupWizardStep(
    val title: String,
    val subtitle: String,
) {
    DATABASE(
        title = "Database",
        subtitle = "Where your data lives.",
    ),
    STRATEGIES(
        title = "Strategy catalog",
        subtitle =
            "Install the currencies and locations you use, and a strategy for each bank, card or exchange. " +
                "You can add more later.",
    ),
    CURRENCY_AND_LOCATION(
        title = "Currency and location",
        subtitle =
            "The currency new transactions and totals use, and the location whose bank holidays decide which days " +
                "count as business days when imports are matched up. Each account can override the location.",
    ),
    STRATEGY_SYNC(
        title = "Share strategies via Google Drive",
        subtitle = "Optional: keep your strategies in sync across your devices.",
    ),
    IMPORT_DIRECTORIES(
        title = "Import folders",
        subtitle = "Point Money Manager at the folders your statements land in, locally or on Google Drive.",
    ),
    API_IMPORTS(
        title = "API imports",
        subtitle =
            "Connect the API strategies you installed so they can download transactions for you. " +
                "Skip any you don't want to set up now — you can connect them later.",
    ),
}

/**
 * The steps to show, given what this build/database actually supports. A step whose feature is unavailable
 * is dropped rather than shown as a dead end.
 *
 * [hasApiStrategies] is read from the database, so the API step appears mid-wizard as soon as the user
 * installs an API strategy in [SetupWizardStep.STRATEGIES].
 */
fun setupWizardSteps(
    includeDatabaseStep: Boolean,
    strategyCatalogAvailable: Boolean,
    strategySyncAvailable: Boolean,
    importDirectoriesAvailable: Boolean,
    hasApiStrategies: Boolean,
): List<SetupWizardStep> =
    buildList {
        if (includeDatabaseStep) add(SetupWizardStep.DATABASE)
        // The catalog comes first: it is where a new database's currencies and locations come from.
        if (strategyCatalogAvailable) add(SetupWizardStep.STRATEGIES)
        add(SetupWizardStep.CURRENCY_AND_LOCATION)
        if (strategySyncAvailable) add(SetupWizardStep.STRATEGY_SYNC)
        if (importDirectoriesAvailable) add(SetupWizardStep.IMPORT_DIRECTORIES)
        if (hasApiStrategies) add(SetupWizardStep.API_IMPORTS)
    }
