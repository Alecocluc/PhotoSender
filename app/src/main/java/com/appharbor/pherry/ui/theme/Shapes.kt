package com.appharbor.pherry.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Square print corners. Envelopes, tickets, buttons and fields share the 4dp corner; frames are
// nearly square at 2dp; only sheets and dialogs open up to 8–12dp.
val PherryShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

object PherryShape {
    val frame = RoundedCornerShape(2.dp)
    val print = RoundedCornerShape(4.dp)
    val button = RoundedCornerShape(4.dp)
    val sheetTop = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
}
