package com.moneymanager.ui.screens.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ExchangeOrder
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.repository.ExchangeOrderReadRepository
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Per-account list of exchange orders (all statuses — open limit orders included). Clicking a row
 * opens the order detail with its fill trades.
 */
@Composable
fun OrdersScreen(
    accountId: AccountId,
    exchangeOrderRepository: ExchangeOrderReadRepository,
    onOrderClick: (ExchangeOrderId) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val orders by rememberFlowAsStateWithSchemaErrorHandling(accountId, initial = emptyList()) {
        exchangeOrderRepository.getOrdersByAccount(accountId)
    }
    val fillCounts by rememberFlowAsStateWithSchemaErrorHandling(accountId, initial = emptyMap()) {
        exchangeOrderRepository.getFillCountsByAccount(accountId)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
        }
        if (orders.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No orders", style = MaterialTheme.typography.bodyLarge)
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("ordersList"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = orders, key = { it.id.id }) { order ->
                OrderCard(
                    order = order,
                    fillCount = fillCounts[order.id] ?: 0L,
                    onClick = { onOrderClick(order.id) },
                )
            }
        }
    }
}

@Composable
private fun OrderCard(
    order: ExchangeOrder,
    fillCount: Long,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val date = order.createdAt.toLocalDateTime(TimeZone.currentSystemDefault()).date
            Text(
                text = "$date",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(0.15f),
            )
            Text(
                text = order.side,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (order.side.equals("BUY", ignoreCase = true)) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                maxLines = 1,
                modifier = Modifier.weight(0.1f),
            )
            Text(
                text = listOfNotNull(order.orderType, order.timeInForce).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(0.2f),
            )
            Text(
                text = order.quantity.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(0.15f),
            )
            Text(
                text = order.avgPrice ?: order.limitPrice ?: "",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(0.15f),
            )
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.weight(0.15f),
            ) {
                Text(
                    text = order.status.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Text(
                    text = if (fillCount == 1L) "1 fill" else "$fillCount fills",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
