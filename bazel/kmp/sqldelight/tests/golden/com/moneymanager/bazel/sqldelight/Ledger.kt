@file:Suppress("REDUNDANT_VISIBILITY_MODIFIER", "ASSIGNED_VALUE_IS_NEVER_READ")

package com.moneymanager.bazel.sqldelight

import kotlin.Long
import kotlin.String

public data class Ledger(
  public val id: Long,
  public val name: String,
  public val balance: Long,
)
