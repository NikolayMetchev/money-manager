package com.moneymanager.tui

import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.moneymanager.terminal.fit

/** The open database, and how to switch: the one screen the shell ships with until the feature screens land. */
val DatabaseScreen =
    TuiScreen(title = "Database") { scope ->
        val inner = (scope.width - 2).coerceAtLeast(0)
        Column(modifier = Modifier.padding(horizontal = 1, vertical = 1)) {
            Text(fit("Database", inner), textStyle = TextStyle.Bold)
            Text(fit(scope.session.location.toString(), inner))
            Text("")
            Text(fit("Press o to open or create another database, ? for every key.", inner), textStyle = TextStyle.Dim)
        }
    }
