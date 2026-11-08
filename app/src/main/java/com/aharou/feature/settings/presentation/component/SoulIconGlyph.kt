package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.aharou.core.soul.SoulIcon
import com.aharou.core.soul.SoulMetadata

@Composable
internal fun SoulIconGlyph(icon: String, sizeDp: Dp, emojiSp: TextUnit, fallbackEmoji: String = SoulMetadata.DISPLAY_EMOJI) {
    val bitmap = remember(icon) { SoulIcon.decode(icon) }
    when {
        bitmap != null -> Image(bitmap = bitmap.asImageBitmap(), contentDescription = null,
            modifier = Modifier.size(sizeDp).clip(RoundedCornerShape(sizeDp * SoulIcon.CORNER_RADIUS_FRACTION)))
        icon.isNotEmpty() -> Text(text = icon, fontSize = emojiSp)
        else -> Text(text = fallbackEmoji, fontSize = emojiSp)
    }
}
