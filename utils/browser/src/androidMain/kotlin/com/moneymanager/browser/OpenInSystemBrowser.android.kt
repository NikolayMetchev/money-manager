package com.moneymanager.browser

// Android signs in through Google Identity Services, not a browser this library opens.
internal actual fun openInSystemBrowser(url: String): Boolean = false
