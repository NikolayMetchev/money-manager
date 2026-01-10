package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.ui.util.formatAmount
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun AccountTransactionCard(
    runningBalance: AccountRow,
    accounts: List<Account>,
    screenSizeClass: ScreenSizeClass,
    isHighlighted: Boolean = false,
    onAccountClick: (AccountId) -> Unit = {},
    onEditClick: (Transfer) -> Unit = {},
    onAuditClick: (TransferId) -> Unit = {},
) {
    // Determine which account to display based on the current view
    // The account column should show the OTHER account in the transaction
    // runningBalance.accountId tells us which account's perspective we're viewing
    val otherAccount =
        when {
            // If current account is the source, show the target (where money went)
            runningBalance.sourceAccountId == runningBalance.accountId -> accounts.find { it.id == runningBalance.targetAccountId }
            // If current account is the target, show the source (where money came from)
            runningBalance.targetAccountId == runningBalance.accountId -> accounts.find { it.id == runningBalance.sourceAccountId }
            // Fallback: shouldn't happen, but if neither matches, show nothing
            else -> null
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors =
            if (isHighlighted) {
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                )
            } else {
                CardDefaults.cardColors()
            },
    ) {
        // Use consistent style and autoSize for all columns
        val cellStyle = MaterialTheme.typography.bodyMedium
        val cellAutoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 14.sp)

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Date column
            val dateTime = runningBalance.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
            Text(
                text = "${dateTime.date}",
                style = cellStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                autoSize = cellAutoSize,
                modifier = Modifier.weight(0.15f),
            )

            // Time column (only on medium/expanded screens)
            if (screenSizeClass != ScreenSizeClass.Compact) {
                val time = dateTime.time
                val millis = time.nanosecond / 1_000_000
                val timeText =
                    "${time.hour.toString().padStart(2, '0')}:" +
                        "${time.minute.toString().padStart(2, '0')}:" +
                        "${time.second.toString().padStart(2, '0')}." +
                        millis.toString().padStart(3, '0')
                Text(
                    text = timeText,
                    style = cellStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    autoSize = cellAutoSize,
                    modifier = Modifier.weight(0.1f),
                )
            }

            // Account name column (clickable)
            Text(
                text = otherAccount?.name ?: "Unknown",
                style = cellStyle,
                color =
                    if (otherAccount != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
                autoSize = cellAutoSize,
                modifier =
                    Modifier
                        .weight(0.2f)
                        .padding(horizontal = 8.dp)
                        .clickable(enabled = otherAccount != null) {
                            otherAccount?.id?.let { accountId ->
                                onAccountClick(accountId)
                            }
                        },
            )

            // Description column
            if (runningBalance.description.isNotBlank()) {
                Text(
                    text = runningBalance.description,
                    style = cellStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    autoSize = cellAutoSize,
                    modifier = Modifier.weight(0.25f).padding(horizontal = 8.dp),
                )
            } else {
                Spacer(modifier = Modifier.weight(0.25f))
            }

            // Amount column
            Text(
                text = formatAmount(runningBalance.transactionAmount),
                style = cellStyle,
                color =
                    if (runningBalance.transactionAmount.amount >= 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                textAlign = TextAlign.End,
                maxLines = 1,
                autoSize = cellAutoSize,
                modifier = Modifier.weight(0.15f),
            )

            // Balance column
            Text(
                text = formatAmount(runningBalance.runningBalance),
                style = cellStyle,
                color =
                    if (runningBalance.runningBalance.amount >= 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                textAlign = TextAlign.End,
                maxLines = 1,
                autoSize = cellAutoSize,
                modifier = Modifier.weight(0.15f).padding(start = 8.dp),
            )

            // Edit button - reconstruct Transfer from AccountRow
            IconButton(
                onClick = {
                    // Reconstruct Transfer object from AccountRow fields
                    // Note: Amount needs to be positive value from the materialized view
                    val amount =
                        if (runningBalance.transactionAmount.amount < 0) {
                            Money(-runningBalance.transactionAmount.amount, runningBalance.transactionAmount.currency)
                        } else {
                            runningBalance.transactionAmount
                        }
                    val transfer =
                        Transfer(
                            id = runningBalance.transactionId as TransferId,
                            timestamp = runningBalance.timestamp,
                            description = runningBalance.description,
                            sourceAccountId = runningBalance.sourceAccountId,
                            targetAccountId = runningBalance.targetAccountId,
                            amount = amount,
                        )
                    onEditClick(transfer)
                },
                modifier = Modifier.size(32.dp),
            ) {
                Text(
                    text = "\u270F\uFE0F",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            // Audit button - show audit history for this transaction
            IconButton(
                onClick = {
                    onAuditClick(runningBalance.transactionId as TransferId)
                },
                modifier = Modifier.size(32.dp),
            ) {
                Text(
                    text = "\uD83D\uDCDC",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
