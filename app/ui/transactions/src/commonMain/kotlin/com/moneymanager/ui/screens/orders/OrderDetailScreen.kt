@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.repository.ExchangeOrderReadRepository
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.util.formatAmount
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val FIELD_LABEL_WIDTH = 130.dp

/**
 * One exchange order: its exchange-reported fields plus the fill trades linked via
 * `exchange_order_trade`. Clicking a fill jumps to it in the account transactions list.
 */
@Composable
fun OrderDetailScreen(
    orderId: ExchangeOrderId,
    exchangeOrderRepository: ExchangeOrderReadRepository,
    onFillTradeClick: (Trade) -> Unit = {},
    onFillTradeAuditClick: (Trade) -> Unit = {},
    onAuditClick: (ExchangeOrderId) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val order by rememberFlowAsStateWithSchemaErrorHandling(orderId, initial = null) {
        exchangeOrderRepository.getOrderById(orderId)
    }
    val fills by rememberFlowAsStateWithSchemaErrorHandling(orderId, initial = emptyList()) {
        exchangeOrderRepository.getFillTradesForOrder(orderId)
    }

    val currentOrder = order ?: return
    val timeZone = TimeZone.currentSystemDefault()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag("orderDetail"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← Back") }
                TextButton(onClick = { onAuditClick(orderId) }) { Text("Audit history") }
            }
        }
        item {
            Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OrderField("Order id", currentOrder.orderRef)
                    OrderField("Side", currentOrder.side)
                    OrderField("Type", currentOrder.orderType)
                    OrderField("Time in force", currentOrder.timeInForce)
                    OrderField("Status", currentOrder.status)
                    OrderField("Quantity", currentOrder.quantity)
                    OrderField("Limit price", currentOrder.limitPrice)
                    OrderField("Average price", currentOrder.avgPrice)
                    OrderField("Created", currentOrder.createdAt.toLocalDateTime(timeZone).toString())
                    OrderField("Updated", currentOrder.updatedAt?.toLocalDateTime(timeZone)?.toString())
                }
            }
        }
        item {
            Text(
                text = if (fills.size == 1) "1 fill trade" else "${fills.size} fill trades",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(items = fills, key = { it.id.id }) { fill ->
            FillTradeCard(
                fill = fill,
                onClick = { onFillTradeClick(fill) },
                onAuditClick = { onFillTradeAuditClick(fill) },
            )
        }
    }
}

@Composable
private fun OrderField(
    label: String,
    value: String?,
) {
    if (value.isNullOrBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(FIELD_LABEL_WIDTH),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FillTradeCard(
    fill: Trade,
    onClick: () -> Unit,
    onAuditClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("fillTrade-${fill.id.id}"),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dateTime = fill.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
            Text(
                text = "${dateTime.date} ${dateTime.time}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(0.28f),
            )
            Text(
                text = fill.description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(0.26f).padding(horizontal = 8.dp),
            )
            Text(
                text = "-${formatAmount(fill.from)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(0.18f),
            )
            Text(
                text = "+${formatAmount(fill.to)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(0.18f),
            )
            // Fill trades have their own audit trail (they are real trades); link to it directly rather
            // than only via the parent order.
            IconButton(
                onClick = onAuditClick,
                modifier = Modifier.testTag("fillTradeAudit-${fill.id.id}"),
            ) {
                Text(text = "📜", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
