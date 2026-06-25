package com.moneymanager.ui.util

import java.text.NumberFormat

actual fun formatGroupedNumber(value: Long): String = NumberFormat.getIntegerInstance().format(value)
