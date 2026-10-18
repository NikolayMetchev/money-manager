package lib

import android.content.Context
import sqlite.Db
import ui.Widget

actual fun open(): Widget = Widget(Db(Context()))
