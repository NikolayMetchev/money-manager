package com.moneymanager.ui.util

import com.moneymanager.domain.model.Category

/**
 * Represents a node in the category tree hierarchy.
 *
 * @property category The category data for this node
 * @property children The child nodes of this category
 * @property depth The depth level in the tree (0 for root nodes)
 */
data class CategoryNode(
    val category: Category,
    val children: List<CategoryNode> = emptyList(),
    val depth: Int = 0,
)

/**
 * Builds a forest (list of trees) from a flat list of categories.
 * Each root node represents a top-level category (parentId = null),
 * with its descendants nested as children.
 *
 * @param categories The flat list of all categories
 * @return List of root CategoryNode objects forming the forest
 */
fun buildCategoryForest(categories: List<Category>): List<CategoryNode> {
    val childrenByParentId = categories.groupBy { it.parentId }

    fun buildSubtree(
        category: Category,
        depth: Int,
    ): CategoryNode {
        val children =
            childrenByParentId[category.id].orEmpty()
                .map { buildSubtree(it, depth + 1) }
        return CategoryNode(category = category, children = children, depth = depth)
    }

    return childrenByParentId[null].orEmpty()
        .map { buildSubtree(it, depth = 0) }
}

/**
 * Flattens a category forest into a list suitable for display in a LazyColumn.
 * Each node includes its depth for indentation purposes.
 *
 * @param forest The list of root CategoryNode objects
 * @param expandedIds Set of category IDs that are currently expanded
 * @return Flattened list of CategoryNode objects in display order
 */
fun flattenCategoryForest(
    forest: List<CategoryNode>,
    expandedIds: Set<Long>,
): List<CategoryNode> {
    val result = mutableListOf<CategoryNode>()

    fun traverse(node: CategoryNode) {
        result.add(node)
        if (node.category.id in expandedIds) {
            node.children.forEach { traverse(it) }
        }
    }

    forest.forEach { traverse(it) }
    return result
}

/**
 * Gets all descendant IDs of a category (children, grandchildren, etc.).
 * Used to prevent creating cycles when changing parent via drag-and-drop.
 *
 * @param categoryId The ID of the category to find descendants for
 * @param forest The category forest to search in
 * @return Set of all descendant category IDs
 */
fun getDescendantIds(
    categoryId: Long,
    forest: List<CategoryNode>,
): Set<Long> {
    val result = mutableSetOf<Long>()

    fun findAndCollectDescendants(nodes: List<CategoryNode>): Boolean {
        for (node in nodes) {
            if (node.category.id == categoryId) {
                collectAllDescendants(node, result)
                return true
            }
            if (findAndCollectDescendants(node.children)) {
                return true
            }
        }
        return false
    }

    findAndCollectDescendants(forest)
    return result
}

private fun collectAllDescendants(
    node: CategoryNode,
    result: MutableSet<Long>,
) {
    for (child in node.children) {
        result.add(child.category.id)
        collectAllDescendants(child, result)
    }
}
