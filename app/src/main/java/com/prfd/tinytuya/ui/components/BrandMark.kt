package com.prfd.tinytuya.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Code-native TinyTuya network mark shared by every app-level destination. */
@Composable
fun BrandMark(
  modifier: Modifier = Modifier,
  colors: BrandMarkColors = brandMarkColors(),
) {
  Canvas(modifier = modifier.semantics { contentDescription = "TinyTuya" }) {
    drawBrandMark(colors)
  }
}

/** Colors used by [BrandMark] and its theme-independent canvas renderer. */
@Immutable
data class BrandMarkColors(
  val primary: Color,
  val container: Color,
  val onContainer: Color,
)

/** Draws the mark on any Compose [DrawScope], including a bitmap-backed offscreen canvas. */
fun DrawScope.drawBrandMark(colors: BrandMarkColors) {
  drawCircle(colors.container)
  val center = Offset(size.width * 0.5f, size.height * 0.52f)
  val left = Offset(size.width * 0.27f, size.height * 0.38f)
  val right = Offset(size.width * 0.74f, size.height * 0.31f)
  val bottom = Offset(size.width * 0.61f, size.height * 0.76f)
  drawLine(colors.primary, center, left, strokeWidth = size.width * 0.065f)
  drawLine(colors.primary, center, right, strokeWidth = size.width * 0.065f)
  drawLine(colors.primary, center, bottom, strokeWidth = size.width * 0.065f)
  drawCircle(colors.onContainer, size.width * 0.09f, center)
  drawCircle(colors.primary, size.width * 0.075f, left)
  drawCircle(colors.primary, size.width * 0.075f, right)
  drawCircle(colors.primary, size.width * 0.075f, bottom)
}

@Composable
private fun brandMarkColors(): BrandMarkColors {
  val colorScheme = MaterialTheme.colorScheme
  return BrandMarkColors(
    primary = colorScheme.primary,
    container = colorScheme.primaryContainer,
    onContainer = colorScheme.onPrimaryContainer,
  )
}
