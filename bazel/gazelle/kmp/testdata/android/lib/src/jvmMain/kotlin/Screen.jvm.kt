package lib

import jdbc.Driver
import ui.Widget

actual fun open(): Widget = Widget(Driver())
