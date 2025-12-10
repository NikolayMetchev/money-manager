package com.moneymanager.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.ui.util.CategoryNode
import com.moneymanager.ui.util.buildCategoryForest
import com.moneymanager.ui.util.flattenCategoryForest

@Composable
fun CategoriesScreen(categoryRepository: CategoryRepository) {
    val categories by categoryRepository.getAllCategories().collectAsState(initial = emptyList())
    var expandedIds by remember { mutableStateOf(emptySet<Long>()) }

    val forest = remember(categories) { buildCategoryForest(categories) }
    val flattenedNodes = remember(forest, expandedIds) { flattenCategoryForest(forest, expandedIds) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Text(
            text = "Your Categories",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )

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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(flattenedNodes, key = { it.category.id }) { node ->
                    CategoryTreeItem(
                        node = node,
                        isExpanded = node.category.id in expandedIds,
                        onToggleExpand = {
                            expandedIds =
                                if (node.category.id in expandedIds) {
                                    expandedIds - node.category.id
                                } else {
                                    expandedIds + node.category.id
                                }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryTreeItem(
    node: CategoryNode,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val hasChildren = node.children.isNotEmpty()
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        label = "expandIconRotation",
    )

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = (node.depth * 24).dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (node.depth == 0) 2.dp else 1.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (node.depth == 0) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasChildren) { onToggleExpand() }
                    .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasChildren) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier =
                            Modifier
                                .size(24.dp)
                                .rotate(rotationAngle),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Spacer(modifier = Modifier.width(24.dp))
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = node.category.name,
                    style =
                        if (node.depth == 0) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                )
            }

            if (hasChildren) {
                Text(
                    text = "${node.children.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
