package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.linkReversal
import com.moneymanager.importengineapi.unlinkReversal
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.util.formatAmount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** How far apart a transfer and the one reversing it may be for the picker to offer it. */
internal val REVERSAL_WINDOW: Duration = 30.days

/**
 * The transfers of [nearby] that could reverse [transfer], or be the one it reverses: the same amount in the same
 * asset, moving the other way through one of its accounts (back into the account it left, or out of the one it
 * entered), within [window] of it, nearest first. The other end may be any account: a payment returned before it
 * arrived comes back from the receiving bank, not from the account it was sent to.
 */
internal fun reversalCandidates(
    transfer: Transfer,
    nearby: List<Transfer>,
    window: Duration = REVERSAL_WINDOW,
): List<Transfer> =
    nearby
        .filter { candidate ->
            candidate.id != transfer.id &&
                candidate.amount == transfer.amount &&
                (candidate.targetAccountId == transfer.sourceAccountId || candidate.sourceAccountId == transfer.targetAccountId) &&
                (candidate.timestamp - transfer.timestamp).absoluteValue <= window
        }.sortedBy { (it.timestamp - transfer.timestamp).absoluteValue }

/**
 * The edit dialog's reversal section: the reversal links [transfer] is in, each with Unlink, or a "Link as reversal…"
 * button that picks the transfer reversing it (or the one it reverses) and links the two. [onChanged] runs after a
 * write, once the materialized views are refreshed.
 */
@Composable
internal fun ReversalLinkSection(
    transfer: Transfer,
    transactionRepository: TransactionReadRepository,
    relationshipRepository: RelationshipReadRepository,
    accountRepository: AccountReadRepository,
    maintenance: Maintenance,
    enabled: Boolean,
    onChanged: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberSchemaAwareCoroutineScope()
    val accounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) { accountRepository.getAllAccounts() }
    val relationships by rememberFlowAsStateWithSchemaErrorHandling(transfer.id, initial = emptyList()) {
        relationshipRepository.getByTransaction(transfer.id)
    }
    val reversals = relationships.filter { it.relationshipType.id.id == WellKnownIds.REVERSAL_RELATIONSHIP_TYPE_ID }
    var linked by remember { mutableStateOf<Map<TransferId, Transfer>>(emptyMap()) }
    LaunchedEffect(reversals) {
        val ids = reversals.flatMap { it.members }.map { TransferId(it.transactionId.id) }.toSet()
        linked = if (ids.isEmpty()) emptyMap() else transactionRepository.getTransactionsByIds(ids)
    }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun write(action: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                action()
                maintenance.refreshMaterializedViews()
                picking = false
                onChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                logger.e(expected) { "Failed to change reversal link: ${expected.message}" }
                error = "Failed to change reversal link: ${expected.message}"
            } finally {
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        reversals.forEach { relationship ->
            val reversesThis = relationship.related?.id == transfer.id.id
            val otherId = (if (reversesThis) relationship.primary else relationship.related)?.let { TransferId(it.id) }
            val other = otherId?.let { linked[it] }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = (if (reversesThis) "Reversed by " else "Reverses ") + (other?.let { describe(it, accounts) } ?: "…"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { write { importEngine.unlinkReversal(relationship.id, listOfNotNull(transfer, other)) } },
                    enabled = enabled && !busy,
                    modifier = Modifier.testTag("unlinkReversalButton"),
                ) { Text("Unlink") }
            }
        }
        if (reversals.isEmpty()) {
            OutlinedButton(
                onClick = { picking = true },
                enabled = enabled && !busy,
                modifier = Modifier.testTag("linkReversalButton"),
            ) { Text("Link as reversal…") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    if (picking) {
        ReversalPickerDialog(
            transfer = transfer,
            transactionRepository = transactionRepository,
            accounts = accounts,
            busy = busy,
            onLink = { other, excludeBoth ->
                val (original, reversing) = listOf(transfer, other).sortedBy { it.timestamp }
                write { importEngine.linkReversal(original, reversing, excludeBoth) }
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ReversalPickerDialog(
    transfer: Transfer,
    transactionRepository: TransactionReadRepository,
    accounts: List<Account>,
    busy: Boolean,
    onLink: (Transfer, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var candidates by remember { mutableStateOf<List<Transfer>?>(null) }
    LaunchedEffect(transfer.id) {
        val nearby =
            transactionRepository.getUnreversedTransfersTouchingAccounts(
                accountIds = setOf(transfer.sourceAccountId, transfer.targetAccountId),
                amounts = setOf(transfer.amount),
                maxTimestamp = transfer.timestamp + REVERSAL_WINDOW,
                reversalTypeId = RelationshipTypeId(WellKnownIds.REVERSAL_RELATIONSHIP_TYPE_ID),
            )
        candidates = reversalCandidates(transfer, nearby)
    }
    var selected by remember { mutableStateOf<Transfer?>(null) }
    var excludeBoth by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Link as reversal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${describe(transfer, accounts)} is reversed by, or reverses:", style = MaterialTheme.typography.bodyMedium)
                val found = candidates
                when {
                    found == null -> {
                        Text("Looking for transfers…", style = MaterialTheme.typography.bodySmall)
                    }

                    found.isEmpty() -> {
                        Text(
                            "No transfer of ${formatAmount(transfer.amount)} moves the other way through these accounts " +
                                "within ${REVERSAL_WINDOW.inWholeDays} days.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    else -> {
                        Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            found.forEach { candidate ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !busy) { selected = candidate }
                                            .testTag("reversalCandidate-${candidate.id.id}"),
                                ) {
                                    RadioButton(
                                        selected = selected?.id == candidate.id,
                                        onClick = { selected = candidate },
                                        enabled = !busy,
                                    )
                                    Text(describe(candidate, accounts), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = excludeBoth,
                        onCheckedChange = { excludeBoth = it },
                        enabled = !busy,
                        modifier = Modifier.testTag("reversalExcludeBoth"),
                    )
                    Text("Exclude both from balances", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Leave this on for a payment returned before it arrived: neither movement then counts, so the account " +
                        "it was sent to doesn't hold money it never received.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            LoadingTextButton(
                onClick = { selected?.let { onLink(it, excludeBoth) } },
                enabled = !busy && selected != null,
                loading = busy,
                label = "Link",
            )
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** "2022-11-14 22:54 £10.00 Monzo → MARCUS GS: Sent from Monzo". */
private fun describe(
    transfer: Transfer,
    accounts: List<Account>,
): String {
    val at = transfer.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
    val time = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
    val from = accounts.find { it.id == transfer.sourceAccountId }?.name ?: "?"
    val to = accounts.find { it.id == transfer.targetAccountId }?.name ?: "?"
    val description =
        transfer.description
            .takeIf { it.isNotBlank() }
            ?.let { ": $it" }
            .orEmpty()
    return "${at.date} $time ${formatAmount(transfer.amount)} $from → $to$description"
}
