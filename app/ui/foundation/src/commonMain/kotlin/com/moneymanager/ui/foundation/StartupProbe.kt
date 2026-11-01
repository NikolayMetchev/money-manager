package com.moneymanager.ui.foundation

import androidx.compose.runtime.staticCompositionLocalOf
import com.moneymanager.domain.model.Account

/** How far the app has got since launch, in the order a startup normally reaches them. */
enum class StartupMilestone {
    /** The window has drawn its first frame. */
    FIRST_FRAME,

    /** The Accounts screen has loaded its accounts. */
    ACCOUNTS_READY,

    /** An account's Transactions screen has loaded its first page. */
    TRANSACTIONS_READY,
}

/**
 * Reports startup milestones, for startup measurements and the AOT cache's training run (#1254). Only an
 * entry point asked to measure provides one (the desktop app, from `MM_STARTUP_PROBE`); screens report
 * through [LocalStartupProbe], which is null otherwise.
 */
interface StartupProbe {
    fun reached(milestone: StartupMilestone)

    /** Once the accounts have loaded: the account whose transactions to open next, or null to stay. */
    fun accountToOpen(accounts: List<Account>): Account?
}

val LocalStartupProbe = staticCompositionLocalOf<StartupProbe?> { null }
