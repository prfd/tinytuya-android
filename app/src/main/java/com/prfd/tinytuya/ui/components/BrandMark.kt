package com.prfd.tinytuya.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Code-native TinyTuya network mark shared by every app-level destination. */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(
        modifier = modifier.semantics { contentDescription = "TinyTuya" }
    ) {
        drawCircle(container)
        val center = Offset(size.width * 0.5f, size.height * 0.52f)
        val left = Offset(size.width * 0.27f, size.height * 0.38f)
        val right = Offset(size.width * 0.74f, size.height * 0.31f)
        val bottom = Offset(size.width * 0.61f, size.height * 0.76f)
        drawLine(primary, center, left, strokeWidth = size.width * 0.065f)
        drawLine(primary, center, right, strokeWidth = size.width * 0.065f)
        drawLine(primary, center, bottom, strokeWidth = size.width * 0.065f)
        drawCircle(onContainer, size.width * 0.09f, center)
        drawCircle(primary, size.width * 0.075f, left)
        drawCircle(primary, size.width * 0.075f, right)
        drawCircle(primary, size.width * 0.075f, bottom)
    }
}
