package com.moneymanager.terminal

// The JDK can't ask about stderr alone. Its console is there only when stdin and stdout are both terminals,
// so a run with redirected stdout (`mm-jvm … > out.json`) shows plain lines rather than bars.
actual fun stderrIsTerminal(): Boolean = System.console()?.isTerminal == true

// The JVM build runs from the desktop installer's runtime; Windows Terminal and conhost on Windows 10+
// take escape sequences on their own, and the JDK can't flip console modes.
actual fun enableStderrVirtualTerminal(): Boolean = true
