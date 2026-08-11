package com.prfd.tinytuya.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prfd.tinytuya.data.python.CloudImportResult

@Composable
internal fun ImportingScreen() {
  Column(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.primaryContainer,
    ) {
      Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
          modifier = Modifier.size(54.dp),
          strokeWidth = 5.dp,
        )
      }
    }
    Text(
      text = "Talking to your Tuya project…",
      style = MaterialTheme.typography.headlineMedium,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 30.dp),
    )
    Text(
      text =
        "Fetching linked devices, local keys, and capability mappings. This request is time-bounded and may take a few seconds.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 12.dp),
    )
  }
}

@Composable
internal fun ErrorScreen(
  error: CloudImportUiState.Error,
  onRetry: () -> Unit,
  onReviewSetup: () -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 28.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(28.dp))
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.errorContainer,
      contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
      Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
        Text("!", style = MaterialTheme.typography.displaySmall)
      }
    }
    Text(
      text = errorTitle(error.code),
      style = MaterialTheme.typography.headlineMedium,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 26.dp),
    )
    Text(
      text = error.message,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 12.dp),
    )
    Surface(
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = MaterialTheme.shapes.small,
      modifier = Modifier.padding(top = 18.dp),
    ) {
      Text(
        text = "Reference · ${error.code}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
      )
    }
    Spacer(Modifier.height(34.dp))
    PrimaryActionButton(text = "Check credentials and retry", onClick = onRetry)
    OutlinedButton(
      onClick = onReviewSetup,
      modifier = Modifier.fillMaxWidth().height(54.dp).padding(top = 8.dp),
    ) {
      Text("Review setup guide")
    }
    Text(
      text =
        "New values are not saved unless Tuya accepts them. If this sync used the saved account, choose the button above to enter a complete replacement.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 18.dp),
    )
  }
}

private fun errorTitle(code: String): String =
  when (code) {
    "CLOUD_CREDENTIALS_INVALID" -> "Credentials not accepted"
    "CLOUD_PERMISSION_DENIED" -> "Project access is missing"
    "CLOUD_QUOTA_EXHAUSTED" -> "Cloud quota reached"
    "CLOUD_SUBSCRIPTION_INACTIVE" -> "Cloud service is inactive"
    "CLOUD_TIMEOUT" -> "Tuya took too long"
    "CLOUD_NETWORK_ERROR" -> "Could not reach Tuya"
    "CATALOG_KEY_CREATE_FAILED",
    "CATALOG_KEY_UNAVAILABLE" -> "Secure key unavailable"
    "CATALOG_WRITE_FAILED",
    "CATALOG_ENCRYPT_FAILED" -> "Could not secure devices"
    "CATALOG_READ_FAILED",
    "CATALOG_DECRYPT_FAILED",
    "CATALOG_INVALID" -> "Local catalog needs attention"
    "CREDENTIAL_VAULT_KEY_CREATE_FAILED",
    "CREDENTIAL_VAULT_KEY_UNAVAILABLE" -> "Credential key unavailable"
    "CREDENTIAL_VAULT_WRITE_FAILED",
    "CREDENTIAL_VAULT_ENCRYPT_FAILED" -> "Could not save credentials"
    "CREDENTIAL_VAULT_READ_FAILED",
    "CREDENTIAL_VAULT_DECRYPT_FAILED",
    "CREDENTIAL_VAULT_INVALID" -> "Saved credentials need attention"
    else -> "Import did not complete"
  }

@Composable
internal fun SuccessScreen(
  result: CloudImportResult,
  onImportAgain: () -> Unit,
  onOpenInventory: () -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 28.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
          Text("✓", style = MaterialTheme.typography.headlineMedium)
        }
      }
      Spacer(Modifier.width(16.dp))
      Column {
        Eyebrow("CLOUD IMPORT COMPLETE")
        Text(
          text =
            when (result.deviceCount) {
              0 -> "No linked devices yet"
              1 -> "1 device found"
              else -> "${result.deviceCount} devices found"
            },
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
    Text(
      text =
        if (result.deviceCount == 0) {
          "The project connected successfully and its credentials are encrypted on this device, but Tuya returned no linked devices. Review account linking or add devices in Smart Life."
        } else {
          "The cloud handshake worked. Your cloud account and device catalog are encrypted locally with separate Android Keystore keys."
        },
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 22.dp, bottom = 18.dp),
    )

    if (result.missingLocalKeyCount > 0) {
      WarningCard(
        text =
          "${result.missingLocalKeyCount} ${if (result.missingLocalKeyCount == 1) "device is" else "devices are"} missing a local key and cannot be controlled locally yet."
      )
    }
    result.warnings.forEach { warning -> WarningCard(text = warning) }

    if (result.devices.isNotEmpty()) {
      Text(
        text = "IMPORTED INVENTORY",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
      )
      result.devices.take(12).forEach { device ->
        ImportedDeviceRow(
          name = device.name.ifBlank { "Unnamed Tuya device" },
          category = device.category.ifBlank { "Unknown category" },
          hasLocalKey = !device.localKey.isBlank,
        )
      }
      if (result.devices.size > 12) {
        Text(
          text = "+ ${result.devices.size - 12} more devices",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 8.dp),
        )
      }
    }

    Surface(
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = MaterialTheme.shapes.large,
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
      Column(Modifier.padding(18.dp)) {
        Text(
          "Next: open your local home",
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          text =
            "Open the device list to find devices on Wi-Fi, refresh their current status, and control supported devices locally.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 5.dp),
        )
      }
    }
    if (result.devices.isNotEmpty()) {
      PrimaryActionButton(
        text = "Open device list",
        onClick = onOpenInventory,
        modifier = Modifier.padding(top = 12.dp),
      )
      TextButton(
        onClick = onImportAgain,
        modifier = Modifier.fillMaxWidth().height(52.dp),
      ) {
        Text("Import another project")
      }
    } else {
      PrimaryActionButton(
        text = "Review setup and try again",
        onClick = onImportAgain,
        modifier = Modifier.padding(top = 12.dp),
      )
    }
    Spacer(Modifier.height(18.dp))
  }
}

@Composable
private fun WarningCard(text: String) {
  Surface(
    color = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
  ) {
    Row(Modifier.padding(15.dp)) {
      Text("!", fontWeight = FontWeight.Bold)
      Spacer(Modifier.width(12.dp))
      Text(text, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun ImportedDeviceRow(
  name: String,
  category: String,
  hasLocalKey: Boolean,
) {
  OutlinedCard(
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
  ) {
    Row(
      modifier = Modifier.padding(15.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
          Text(
            category.take(2).uppercase().ifBlank { "TU" },
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }
      Spacer(Modifier.width(13.dp))
      Column(Modifier.weight(1f)) {
        Text(
          text = name,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = category,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text = if (hasLocalKey) "Ready" else "No key",
        style = MaterialTheme.typography.labelLarge,
        color =
          if (hasLocalKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
      )
    }
  }
}
