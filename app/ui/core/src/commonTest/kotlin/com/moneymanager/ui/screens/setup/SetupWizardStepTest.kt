package com.moneymanager.ui.screens.setup

import kotlin.test.Test
import kotlin.test.assertEquals

class SetupWizardStepTest {
    @Test
    fun `first run shows every step once an api strategy is installed`() {
        assertEquals(
            listOf(
                SetupWizardStep.DATABASE,
                SetupWizardStep.STRATEGIES,
                SetupWizardStep.CURRENCY_AND_LOCATION,
                SetupWizardStep.STRATEGY_SYNC,
                SetupWizardStep.IMPORT_DIRECTORIES,
                SetupWizardStep.API_IMPORTS,
            ),
            setupWizardSteps(
                includeDatabaseStep = true,
                strategyCatalogAvailable = true,
                strategySyncAvailable = true,
                importDirectoriesAvailable = true,
                hasApiStrategies = true,
            ),
        )
    }

    @Test
    fun `api step is absent until an api strategy is installed`() {
        val steps =
            setupWizardSteps(
                includeDatabaseStep = true,
                strategyCatalogAvailable = true,
                strategySyncAvailable = true,
                importDirectoriesAvailable = true,
                hasApiStrategies = false,
            )
        assertEquals(listOf(SetupWizardStep.API_IMPORTS), SetupWizardStep.entries - steps.toSet())
    }

    @Test
    fun `a manual re-run without cloud or file-source support is the catalog then currency only`() {
        assertEquals(
            listOf(SetupWizardStep.STRATEGIES, SetupWizardStep.CURRENCY_AND_LOCATION),
            setupWizardSteps(
                includeDatabaseStep = false,
                strategyCatalogAvailable = true,
                strategySyncAvailable = false,
                importDirectoriesAvailable = false,
                hasApiStrategies = false,
            ),
        )
    }
}
