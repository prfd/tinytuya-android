package com.prfd.tinytuya.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun OnboardingHeader(
  onBack: () -> Unit,
  step: Int,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    TextButton(
      onClick = onBack,
      contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
      Text("←  Back")
    }
    Spacer(Modifier.weight(1f))
    StepIndicator(step = step)
  }
}

@Composable
private fun StepIndicator(step: Int) {
  Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
    repeat(2) { index ->
      Box(
        modifier =
          Modifier.width(if (index + 1 == step) 28.dp else 8.dp)
            .height(8.dp)
            .clip(CircleShape)
            .background(
              if (index + 1 <= step) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.outlineVariant
              }
            )
      )
    }
  }
}

@Composable
internal fun PrimaryActionButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Button(
    onClick = onClick,
    modifier = modifier.fillMaxWidth().height(56.dp),
    shape = MaterialTheme.shapes.medium,
    contentPadding = PaddingValues(horizontal = 20.dp),
    colors =
      ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
      ),
  ) {
    Text(text, style = MaterialTheme.typography.labelLarge)
  }
}

@Composable
internal fun Eyebrow(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    fontSize = 11.sp,
    letterSpacing = 1.5.sp,
    color = MaterialTheme.colorScheme.primary,
  )
}
