package com.prfd.tinytuya.ui.onboarding

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SetupGuideScreen(
  onBack: () -> Unit,
  onOpenOfficialGuide: () -> Unit,
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
      text = "Create your cloud handshake",
      style = MaterialTheme.typography.headlineMedium,
      modifier = Modifier.padding(top = 8.dp),
    )
    Text(
      text =
        "Tuya's API gives TinyTuya the device IDs and local keys needed for direct LAN control. After import, normal use does not need the cloud.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
    )
    SetupStep(
      number = "1",
      title = "Pair devices in Smart Life",
      body =
        "Add each device to the Smart Life app and confirm it responds there. Use the same Smart Life account in the linking step below.",
    )
    SetupStep(
      number = "2",
      title = "Create a Smart Home project",
      body =
        "On the Tuya Developer Platform, create a Cloud project with Development Method set to Smart Home. Select the data center that matches your Smart Life account.",
      accent = true,
    )
    SecondScreenNote()
    SetupStep(
      number = "3",
      title = "Link your app account",
      body =
        "Open Devices › Link App Account › Add App Account. Scan the QR code with Smart Life and keep Automatic Link selected.",
    )
    SetupStep(
      number = "4",
      title = "Copy the authorization key",
      body =
        "From the project's Overview, copy the Client ID and Client Secret. Tuya may require an active IoT Core plan or trial; its availability and terms can change.",
    )
    OutlinedButton(
      onClick = onOpenOfficialGuide,
      modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
      Text("Open Tuya's official guide  ↗")
    }
    Spacer(Modifier.height(14.dp))
    PrimaryActionButton(
      text = "I have my credentials",
      onClick = onContinue,
    )
    Spacer(Modifier.height(24.dp))
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

@Composable
private fun SecondScreenNote() {
  OutlinedCard(
    colors =
      CardDefaults.outlinedCardColors(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
      ),
    border =
      BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
      ),
    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
  ) {
    Row(modifier = Modifier.padding(16.dp)) {
      Text(
        text = "QR",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.padding(top = 2.dp),
      )
      Spacer(Modifier.width(14.dp))
      Column {
        Text(
          text = "Keep a second screen nearby",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
          text =
            "You will display a QR code in the developer portal and scan it with Smart Life on this phone.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.78f),
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}
