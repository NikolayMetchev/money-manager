package com.moneymanager.presenter.accounts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AssetId
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.presenter.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.query.AccountQueryCriteria
import com.moneymanager.query.AccountSnapshot
import com.moneymanager.query.AccountWithBalances
import com.moneymanager.query.AssetRef
import com.moneymanager.query.NameMatch
import com.moneymanager.query.OwnerRef
import kotlinx.coroutines.flow.Flow

/** The filters the person has set on the accounts list. An empty owner or asset set means "any". */
data class AccountsFilter(
    val nameText: String = "",
    val nameIsRegex: Boolean = false,
    val ownerIds: Set<PersonId> = emptySet(),
    val assetIds: Set<AssetId> = emptySet(),
    val includeShadow: Boolean = false,
    val nonZeroOnly: Boolean = false,
)

sealed interface AccountsEvent {
    data class SetName(
        val text: String,
    ) : AccountsEvent

    data class SetNameIsRegex(
        val regex: Boolean,
    ) : AccountsEvent

    data class SetOwners(
        val ownerIds: Set<PersonId>,
    ) : AccountsEvent

    data class SetAssets(
        val assetIds: Set<AssetId>,
    ) : AccountsEvent

    data class SetIncludeShadow(
        val includeShadow: Boolean,
    ) : AccountsEvent

    data class SetNonZeroOnly(
        val nonZeroOnly: Boolean,
    ) : AccountsEvent
}

/**
 * What an accounts screen shows. [all] is every account, whatever the filter (e.g. to keep a new account's
 * name unique); [displayed] is what the filter keeps.
 */
data class AccountsModel(
    val loaded: Boolean,
    val all: List<AccountWithBalances>,
    val displayed: List<AccountWithBalances>,
    val people: List<Person>,
    val availableAssets: List<Asset>,
    val shadowAccountCount: Int,
    val filter: AccountsFilter,
    /** Why [AccountsFilter.nameText] isn't a valid regex. The list keeps the last valid name filter meanwhile. */
    val nameError: String?,
    val eventSink: (AccountsEvent) -> Unit,
)

/**
 * The accounts list with live filters, shared by the desktop/Android Accounts screen and the TUI. Pass the
 * snapshot flow remembered (`remember(query) { query.snapshot() }`): it's collected once and re-filtered
 * locally, so changing a filter never re-subscribes.
 */
@Composable
fun presentAccounts(snapshots: Flow<AccountSnapshot>): AccountsModel {
    val snapshot by snapshots.collectAsStateWithSchemaErrorHandling(initial = null)
    // One state value, so a model never pairs a new name with the previous name's match.
    var state by remember { mutableStateOf(FilterState()) }

    val current = snapshot ?: EMPTY

    // Drop selections for owners that no longer exist (a person was deleted) and assets no account holds any more.
    val availableOwnerIds = current.people.mapTo(mutableSetOf()) { it.id }
    LaunchedEffect(availableOwnerIds) {
        state = state.copy(filter = state.filter.copy(ownerIds = state.filter.ownerIds intersect availableOwnerIds))
    }
    val availableAssetIds = current.availableAssets.mapTo(mutableSetOf()) { it.id }
    LaunchedEffect(availableAssetIds) {
        state = state.copy(filter = state.filter.copy(assetIds = state.filter.assetIds intersect availableAssetIds))
    }

    val displayed =
        remember(current, state) {
            val filter = state.filter
            current.filter(
                AccountQueryCriteria(
                    owners = filter.ownerIds.mapTo(mutableSetOf()) { OwnerRef.Id(it) },
                    name = state.nameMatch,
                    assets = filter.assetIds.mapTo(mutableSetOf()) { AssetRef.Id(it) },
                    includeShadow = filter.includeShadow,
                    nonZeroOnly = filter.nonZeroOnly,
                ),
            )
        }

    val eventSink: (AccountsEvent) -> Unit = remember { { event -> state = state.on(event) } }

    return AccountsModel(
        loaded = snapshot != null,
        all = current.accounts,
        displayed = displayed,
        people = current.people,
        availableAssets = current.availableAssets,
        shadowAccountCount = current.shadowAccountCount,
        filter = state.filter,
        nameError = state.nameError,
        eventSink = eventSink,
    )
}

private data class FilterState(
    val filter: AccountsFilter = AccountsFilter(),
    val nameMatch: NameMatch? = null,
    val nameError: String? = null,
) {
    fun on(event: AccountsEvent): FilterState {
        val next =
            when (event) {
                is AccountsEvent.SetName -> filter.copy(nameText = event.text)
                is AccountsEvent.SetNameIsRegex -> filter.copy(nameIsRegex = event.regex)
                is AccountsEvent.SetOwners -> filter.copy(ownerIds = event.ownerIds)
                is AccountsEvent.SetAssets -> filter.copy(assetIds = event.assetIds)
                is AccountsEvent.SetIncludeShadow -> filter.copy(includeShadow = event.includeShadow)
                is AccountsEvent.SetNonZeroOnly -> filter.copy(nonZeroOnly = event.nonZeroOnly)
            }
        if (next.nameText == filter.nameText && next.nameIsRegex == filter.nameIsRegex) return copy(filter = next)
        return parseName(next).fold(
            onSuccess = { FilterState(next, it, nameError = null) },
            onFailure = { copy(filter = next, nameError = it.message ?: "Invalid regular expression") },
        )
    }
}

private val EMPTY = AccountSnapshot(accounts = emptyList(), people = emptyList())

private fun parseName(filter: AccountsFilter): Result<NameMatch?> =
    when {
        filter.nameText.isBlank() -> Result.success(null)
        !filter.nameIsRegex -> Result.success(NameMatch.Substring(filter.nameText))
        else ->
            try {
                Result.success(NameMatch.Pattern(filter.nameText))
            } catch (e: IllegalArgumentException) {
                Result.failure(e)
            }
    }
