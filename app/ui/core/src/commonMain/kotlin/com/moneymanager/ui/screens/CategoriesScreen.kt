@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package com.moneymanager.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryBalance
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.CategoryNode
import com.moneymanager.ui.util.buildCategoryForest
import com.moneymanager.ui.util.flattenCategoryForest
import com.moneymanager.ui.util.formatAmount
import com.moneymanager.ui.util.getDescendantIds
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

private val BALANCE_COLUMN_WIDTH = 100.dp
private val HIERARCHY_COLUMN_WIDTH = 250.dp

@Composable
fun CategoriesScreen(
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
) {
    val categories by categoryRepository.getAllCategories()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val categoryBalances by categoryRepository.getCategoryBalances()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val currencies by currencyRepository.getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var expandedIds by remember { mutableStateOf(emptySet<Long>()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<Category?>(null) }

    // Drag state
    var draggedCategoryId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dropTargetId by remember { mutableStateOf<Long?>(null) }
    val itemPositions = remember { mutableStateMapOf<Long, Pair<Float, Float>>() }

    val forest = remember(categories) { buildCategoryForest(categories) }
    val flattenedNodes = remember(forest, expandedIds) { flattenCategoryForest(forest, expandedIds) }

    // Group balances by categoryId for efficient lookup
    val balancesByCategoryId =
        remember(categoryBalances) {
            categoryBalances.groupBy { it.categoryId }
        }

    // Get unique currencies that have balances (for column headers)
    val currenciesWithBalances =
        remember(categoryBalances, currencies) {
            val currencyIdsWithBalances = categoryBalances.map { it.balance.currency.id }.toSet()
            currencies.filter { it.id in currencyIdsWithBalances }
        }

    // Calculate maximum width needed for each currency column
    val columnWidths =
        remember(categoryBalances, currenciesWithBalances) {
            currenciesWithBalances.associate { currency ->
                val maxBalance =
                    categoryBalances
                        .filter { it.balance.currency.id == currency.id }
                        .maxOfOrNull { kotlin.math.abs(it.balance.amount) } ?: 0L
                val maxMoney =
                    categoryBalances
                        .filter { it.balance.currency.id == currency.id }
                        .maxByOrNull { kotlin.math.abs(it.balance.amount) }?.balance
                val formattedMax = maxMoney?.let { formatAmount(it) } ?: formatAmount(0.0, currency)
                // Estimate width: ~8dp per character + 16dp padding
                val estimatedWidth = (formattedMax.length * 8 + 16).dp
                currency.id to maxOf(estimatedWidth, BALANCE_COLUMN_WIDTH)
            }
        }

    val scope = rememberSchemaAwareCoroutineScope()
    val listState = rememberLazyListState()

    // Shared horizontal scroll state for header and all rows
    val balancesScrollState = rememberScrollState()

    // Get descendants of dragged item to prevent invalid drops
    val draggedDescendants =
        remember(draggedCategoryId, forest) {
            draggedCategoryId?.let { getDescendantIds(it, forest) } ?: emptySet()
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Your Categories",
                style = MaterialTheme.typography.headlineMedium,
            )
            TextButton(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Category")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (categories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No categories yet. Add your first category!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Drop zone for making items top-level
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(if (draggedCategoryId != null) 48.dp else 0.dp)
                        .background(
                            if (draggedCategoryId != null && dropTargetId == null) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (draggedCategoryId != null) {
                    Text(
                        text = "Drop here to make top-level",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Currency column headers (only if there are balances)
            if (currenciesWithBalances.isNotEmpty()) {
                CurrencyHeaderRow(
                    currencies = currenciesWithBalances,
                    columnWidths = columnWidths,
                    scrollState = balancesScrollState,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(
                    items = flattenedNodes,
                    key = { _, node -> node.category.id },
                ) { index, node ->
                    val isDragging = node.category.id == draggedCategoryId
                    val isValidDropTarget =
                        draggedCategoryId != null &&
                            node.category.id != draggedCategoryId &&
                            node.category.id !in draggedDescendants
                    val isDropTarget = dropTargetId == node.category.id && isValidDropTarget

                    val isUncategorized = node.category.id == Category.UNCATEGORIZED_ID

                    CategoryTreeItem(
                        node = node,
                        balances = balancesByCategoryId[node.category.id] ?: emptyList(),
                        currenciesWithBalances = currenciesWithBalances,
                        columnWidths = columnWidths,
                        balancesScrollState = balancesScrollState,
                        isExpanded = node.category.id in expandedIds,
                        onToggleExpand = {
                            expandedIds =
                                if (node.category.id in expandedIds) {
                                    expandedIds - node.category.id
                                } else {
                                    expandedIds + node.category.id
                                }
                        },
                        onEditClick = { editingCategory = node.category },
                        isDraggable = !isUncategorized,
                        isDragging = isDragging,
                        isDropTarget = isDropTarget && !isUncategorized,
                        dragOffset = if (isDragging) dragOffset else Offset.Zero,
                        onPositionChanged = { top, bottom ->
                            itemPositions[node.category.id] = top to bottom
                        },
                        onDragStart = {
                            draggedCategoryId = node.category.id
                            dragOffset = Offset.Zero
                        },
                        onDrag = { change ->
                            dragOffset += change

                            // Determine drop target based on position
                            val currentY = (itemPositions[node.category.id]?.first ?: 0f) + dragOffset.y
                            var newDropTarget: Long? = null

                            for ((id, positions) in itemPositions) {
                                if (id != draggedCategoryId && id !in draggedDescendants) {
                                    val (top, bottom) = positions
                                    if (currentY >= top && currentY <= bottom) {
                                        newDropTarget = id
                                        break
                                    }
                                }
                            }

                            dropTargetId = newDropTarget
                        },
                        onDragEnd = {
                            val draggedId = draggedCategoryId
                            val targetId = dropTargetId

                            if (draggedId != null) {
                                val draggedCategory = categories.find { it.id == draggedId }
                                if (draggedCategory != null) {
                                    val newParentId = targetId // null means top-level
                                    if (draggedCategory.parentId != newParentId) {
                                        scope.launch {
                                            try {
                                                categoryRepository.updateCategory(
                                                    draggedCategory.copy(parentId = newParentId),
                                                )
                                            } catch (e: Exception) {
                                                logger.error(e) {
                                                    "Failed to update category hierarchy: ${e.message}"
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            draggedCategoryId = null
                            dragOffset = Offset.Zero
                            dropTargetId = null
                        },
                        onDragCancel = {
                            draggedCategoryId = null
                            dragOffset = Offset.Zero
                            dropTargetId = null
                        },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateCategoryDialogInCategories(
            categoryRepository = categoryRepository,
            onDismiss = { showCreateDialog = false },
        )
    }

    editingCategory?.let { category ->
        EditCategoryDialog(
            category = category,
            categories = categories,
            categoryRepository = categoryRepository,
            onDismiss = { editingCategory = null },
        )
    }
}

@Composable
private fun CurrencyHeaderRow(
    currencies: List<Currency>,
    columnWidths: Map<CurrencyId, Dp>,
    scrollState: ScrollState,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed-width left column for hierarchy
        Box(modifier = Modifier.width(HIERARCHY_COLUMN_WIDTH)) {
            Text(
                text = "Category",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        // Scrollable currency headers
        Row(
            modifier = Modifier.horizontalScroll(scrollState),
        ) {
            currencies.forEach { currency ->
                Text(
                    text = currency.code,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                    modifier =
                        Modifier
                            .width(columnWidths[currency.id] ?: BALANCE_COLUMN_WIDTH)
                            .padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
fun CategoryTreeItem(
    node: CategoryNode,
    balances: List<CategoryBalance>,
    currenciesWithBalances: List<Currency>,
    columnWidths: Map<CurrencyId, Dp>,
    balancesScrollState: ScrollState,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onEditClick: () -> Unit,
    isDraggable: Boolean,
    isDragging: Boolean,
    isDropTarget: Boolean,
    dragOffset: Offset,
    onPositionChanged: (Float, Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val hasChildren = node.children.isNotEmpty()
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        label = "expandIconRotation",
    )

    // Create a map for quick balance lookup by currency
    val balancesByCurrency =
        remember(balances) {
            balances.associateBy { it.balance.currency.id }
        }

    // Main row with two sections: fixed hierarchy column + scrollable balances
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .zIndex(if (isDragging) 1f else 0f)
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInRoot()
                    onPositionChanged(position.y, position.y + coordinates.size.height)
                }
                .graphicsLayer {
                    if (isDragging) {
                        translationY = dragOffset.y
                        scaleX = 1.02f
                        scaleY = 1.02f
                        alpha = 0.9f
                        shadowElevation = 8f
                    }
                }
                .then(
                    if (isDraggable) {
                        Modifier.pointerInput(node.category.id) {
                            detectDragGestures(
                                onDragStart = { onDragStart() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount)
                                },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragCancel() },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left column: fixed width hierarchy with Card
        Box(modifier = Modifier.width(HIERARCHY_COLUMN_WIDTH)) {
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = (node.depth * 16).dp),
                elevation =
                    CardDefaults.cardElevation(
                        defaultElevation = if (node.depth == 0) 2.dp else 1.dp,
                    ),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            when {
                                isDropTarget -> MaterialTheme.colorScheme.primaryContainer
                                node.depth == 0 -> MaterialTheme.colorScheme.surface
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Expand/collapse icon
                    if (hasChildren) {
                        IconButton(
                            onClick = onToggleExpand,
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                modifier =
                                    Modifier
                                        .size(20.dp)
                                        .rotate(rotationAngle),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(20.dp))
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Category name with children count
                    Text(
                        text =
                            buildString {
                                append(node.category.name)
                                if (hasChildren) {
                                    append(" (${node.children.size})")
                                }
                            },
                        style =
                            if (node.depth == 0) {
                                MaterialTheme.typography.titleSmall
                            } else {
                                MaterialTheme.typography.bodySmall
                            },
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )

                    // Edit button
                    if (node.category.id != Category.UNCATEGORIZED_ID) {
                        IconButton(
                            onClick = onEditClick,
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Right column: scrollable balance matrix (aligned with header)
        if (currenciesWithBalances.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(balancesScrollState),
            ) {
                currenciesWithBalances.forEach { currency ->
                    val balance = balancesByCurrency[currency.id]
                    Text(
                        text =
                            if (balance != null) {
                                formatAmount(balance.balance)
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        color =
                            when {
                                balance == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                balance.balance.amount > 0 -> MaterialTheme.colorScheme.primary
                                balance.balance.amount < 0 -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier =
                            Modifier
                                .width(columnWidths[currency.id] ?: BALANCE_COLUMN_WIDTH)
                                .padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun CreateCategoryDialogInCategories(
    categoryRepository: CategoryRepository,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedParentId by remember { mutableStateOf<Long?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val categories by categoryRepository.getAllCategories()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create New Category") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Category Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                var searchQuery by remember { mutableStateOf("") }
                val filteredCategories =
                    remember(categories, searchQuery) {
                        val available = categories.filter { it.id != Category.UNCATEGORIZED_ID }
                        if (searchQuery.isBlank()) {
                            available
                        } else {
                            available.filter { category ->
                                category.name.contains(searchQuery, ignoreCase = true)
                            }
                        }
                    }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded && !isSaving },
                ) {
                    OutlinedTextField(
                        value =
                            if (expanded) {
                                searchQuery
                            } else if (selectedParentId == null) {
                                "None (Top Level)"
                            } else {
                                categories.find { it.id == selectedParentId }?.name ?: "None (Top Level)"
                            },
                        onValueChange = { searchQuery = it },
                        label = { Text("Parent Category") },
                        placeholder = { Text("Type to search...") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        enabled = !isSaving,
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = {
                            expanded = false
                            searchQuery = ""
                        },
                    ) {
                        DropdownMenuItem(
                            text = { Text("None (Top Level)") },
                            onClick = {
                                selectedParentId = null
                                expanded = false
                                searchQuery = ""
                            },
                        )
                        filteredCategories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    selectedParentId = category.id
                                    expanded = false
                                    searchQuery = ""
                                },
                            )
                        }
                    }
                }

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) {
                        errorMessage = "Category name is required"
                    } else {
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                val newCategory =
                                    Category(
                                        id = 0,
                                        name = name.trim(),
                                        parentId = selectedParentId,
                                    )
                                categoryRepository.createCategory(newCategory)
                                onDismiss()
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to create category: ${e.message}" }
                                errorMessage = "Failed to create category: ${e.message}"
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun EditCategoryDialog(
    category: Category,
    categories: List<Category>,
    categoryRepository: CategoryRepository,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(category.name) }
    var selectedParentId by remember { mutableStateOf(category.parentId) }
    var expanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    val scope = rememberSchemaAwareCoroutineScope()

    // Get descendants to prevent selecting them as parent (would create cycle)
    val forest = remember(categories) { buildCategoryForest(categories) }
    val descendantIds = remember(category.id, forest) { getDescendantIds(category.id, forest) }
    val isUncategorized = category.id == Category.UNCATEGORIZED_ID

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Edit Category") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Category Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving && !isUncategorized,
                )

                if (isUncategorized) {
                    Text(
                        text = "The Uncategorized category cannot be modified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    var searchQuery by remember { mutableStateOf("") }
                    val filteredCategories =
                        remember(categories, searchQuery, category.id, descendantIds) {
                            val available =
                                categories.filter {
                                    it.id != category.id &&
                                        it.id !in descendantIds &&
                                        it.id != Category.UNCATEGORIZED_ID
                                }
                            if (searchQuery.isBlank()) {
                                available
                            } else {
                                available.filter { cat ->
                                    cat.name.contains(searchQuery, ignoreCase = true)
                                }
                            }
                        }

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded && !isSaving },
                    ) {
                        OutlinedTextField(
                            value =
                                if (expanded) {
                                    searchQuery
                                } else if (selectedParentId == null) {
                                    "None (Top Level)"
                                } else {
                                    categories.find { it.id == selectedParentId }?.name ?: "None (Top Level)"
                                },
                            onValueChange = { searchQuery = it },
                            label = { Text("Parent Category") },
                            placeholder = { Text("Type to search...") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                            enabled = !isSaving,
                            singleLine = true,
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = {
                                expanded = false
                                searchQuery = ""
                            },
                        ) {
                            DropdownMenuItem(
                                text = { Text("None (Top Level)") },
                                onClick = {
                                    selectedParentId = null
                                    expanded = false
                                    searchQuery = ""
                                },
                            )
                            filteredCategories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name) },
                                    onClick = {
                                        selectedParentId = cat.id
                                        expanded = false
                                        searchQuery = ""
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    TextButton(
                        onClick = { showDeleteConfirmation = true },
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        enabled = !isSaving,
                    ) {
                        Text("Delete Category")
                    }
                }

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) {
                        errorMessage = "Category name is required"
                    } else {
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                categoryRepository.updateCategory(
                                    category.copy(
                                        name = name.trim(),
                                        parentId = selectedParentId,
                                    ),
                                )
                                onDismiss()
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to update category: ${e.message}" }
                                errorMessage = "Failed to update category: ${e.message}"
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = !isSaving && !isUncategorized,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text("Cancel")
            }
        },
    )

    if (showDeleteConfirmation) {
        DeleteCategoryDialog(
            category = category,
            categoryRepository = categoryRepository,
            onDismiss = { showDeleteConfirmation = false },
            onDeleted = onDismiss,
        )
    }
}

@Composable
fun DeleteCategoryDialog(
    category: Category,
    categoryRepository: CategoryRepository,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    var isDeleting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        icon = {
            Text(
                text = "⚠️",
                style = MaterialTheme.typography.headlineMedium,
            )
        },
        title = { Text("Delete Category?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Are you sure you want to delete \"${category.name}\"?",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text =
                        "Child categories will be moved to the parent of this category. " +
                            "Transactions using this category will become uncategorized.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isDeleting = true
                    errorMessage = null
                    scope.launch {
                        try {
                            categoryRepository.deleteCategory(category.id)
                            onDeleted()
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to delete category: ${e.message}" }
                            errorMessage = "Failed to delete category: ${e.message}"
                            isDeleting = false
                        }
                    }
                },
                enabled = !isDeleting,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Delete")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun CategoryCard(category: Category) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (category.parentId != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Subcategory",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}
