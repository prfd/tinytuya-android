package com.prfd.tinytuya.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.prfd.tinytuya.ui.theme.TinytuyaTheme

@Composable
internal fun SetupGuideScreen(
  onBack: () -> Unit,
  onOpenGuide: () -> Unit,
  onContinue: () -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 12.dp)
  ) {
    OnboardingHeader(onBack = onBack, step = 1)
    Spacer(Modifier.height(28.dp))
    Eyebrow("ONE-TIME SETUP")
    Text(
      text = "Create and use your Tuya Developer Platform account",
      style = MaterialTheme.typography.headlineMedium,
      modifier = Modifier.padding(top = 8.dp),
    )
    Text(
      text =
        "Tuya Platform gives TinyTuya the device IDs and local keys needed for direct LAN control. After import, normal use does not need the cloud.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
    )
    SetupStep(
      number = "1",
      title = "Pair your devices",
      body =
        "Pair your Tuya devices using Smart Life App or Tuya Smart App. Do not use a 'guest' account.",
    )
    SetupStep(
      number = "2",
      title = "Create a Cloud project",
      body =
        "On the Tuya Developer Platform, create a Cloud project with Development Method and Industry set to Smart Home. On API Services, make sure that \"IoT Core\" and \"Authorization Token Management\" are enabled.",
      accent = true,
    )
    SetupStep(
      number = "3",
      title = "Link your app account",
      body =
        "Go to Devices › Link App Account › Add App Account. Scan the QR code with Smart Life and keep Automatic Link selected.",
    )
    SetupStep(
      number = "4",
      title = "Copy the Client ID and secret",
      body = "From the project's Overview tab, copy the Client ID and Client Secret. Make sure to remember the project location.",
      accent = true,
    )
    OutlinedButton(
      onClick = onOpenGuide,
      modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
      Text("Open detailed guide ↗")
    }
    Spacer(Modifier.height(14.dp))
    PrimaryActionButton(
      text = "I have my credentials",
      onClick = onContinue,
    )
    Spacer(Modifier.height(24.dp))
  }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun SetupGuidePreview() {
  TinytuyaTheme(darkTheme = false) {
    SetupGuideScreen(
      onBack = {},
      onOpenGuide = {},
      onContinue = {},
    )
  }
}

@Composable
private fun SetupStep(
  number: String,
  title: String,
  body: String,
  accent: Boolean = false,
) {
  val container =
    if (accent) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceVariant
    }
  val content =
    if (accent) {
      MaterialTheme.colorScheme.onPrimaryContainer
    } else {
      MaterialTheme.colorScheme.onSurface
    }

  Surface(
    color = container,
    contentColor = content,
    shape = MaterialTheme.shapes.large,
    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
  ) {
    Row(modifier = Modifier.padding(18.dp)) {
      Surface(
        color =
          if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor =
          if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        shape = CircleShape,
      ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
          Text(number, style = MaterialTheme.typography.labelLarge)
        }
      }
      Spacer(Modifier.width(14.dp))
      Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
          text = body,
          style = MaterialTheme.typography.bodyMedium,
          color = content.copy(alpha = 0.78f),
          modifier = Modifier.padding(top = 5.dp),
        )
      }
    }
  }
}
