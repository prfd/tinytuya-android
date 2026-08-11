package com.prfd.tinytuya.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.prfd.tinytuya.data.python.TuyaCloudRegion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CredentialsScreen(
  state: OnboardingUiState,
  onBack: () -> Unit,
  onRegionChanged: (TuyaCloudRegion) -> Unit,
  onClientIdChanged: (String) -> Unit,
  onClientSecretChanged: (String) -> Unit,
  onSampleDeviceIdChanged: (String) -> Unit,
  onToggleAdvanced: () -> Unit,
  onImport: () -> Unit,
) {
  var regionExpanded by remember { mutableStateOf(false) }
  var secretVisible by remember { mutableStateOf(false) }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 12.dp)
  ) {
    OnboardingHeader(onBack = onBack, step = 2)
    Spacer(Modifier.height(28.dp))
    Eyebrow("SECURE IMPORT")
    Text(
      text =
        if (state.isCredentialUpdate) {
          "Update cloud credentials"
        } else {
          "Connect your project"
        },
      style = MaterialTheme.typography.headlineMedium,
      modifier = Modifier.padding(top = 8.dp),
    )
    Text(
      text =
        if (state.isCredentialUpdate) {
          "Enter the complete replacement authorization key. The saved secret is never revealed or prefilled."
        } else {
          "Enter the authorization key shown on your Tuya Cloud project Overview."
        },
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
    )

    Text(
      text = "Cloud data center",
      style = MaterialTheme.typography.labelLarge,
      modifier = Modifier.padding(start = 4.dp, bottom = 7.dp),
    )
    ExposedDropdownMenuBox(
      expanded = regionExpanded,
      onExpandedChange = { regionExpanded = it },
    ) {
      OutlinedTextField(
        value = state.region.displayName,
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = regionExpanded) },
        modifier =
          Modifier.menuAnchor(
              ExposedDropdownMenuAnchorType.PrimaryNotEditable,
              enabled = true,
            )
            .fillMaxWidth(),
      )
      ExposedDropdownMenu(
        expanded = regionExpanded,
        onDismissRequest = { regionExpanded = false },
      ) {
        TuyaCloudRegion.entries.forEach { region ->
          DropdownMenuItem(
            text = {
              Column {
                Text(region.displayName)
                Text(
                  text = region.apiCode,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
            onClick = {
              onRegionChanged(region)
              regionExpanded = false
            },
          )
        }
      }
    }
    Text(
      text = "This must match the data center selected for your Tuya project.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 18.dp),
    )

    OutlinedTextField(
      value = state.clientId.reveal(),
      onValueChange = onClientIdChanged,
      label = { Text("Client ID / Access ID") },
      placeholder = { Text("Enter your project Client ID") },
      singleLine = true,
      isError = state.validationAttempted && state.clientId.isBlank,
      supportingText =
        if (state.validationAttempted && state.clientId.isBlank) {
          { Text("Client ID is required") }
        } else {
          null
        },
      keyboardOptions =
        KeyboardOptions(
          keyboardType = KeyboardType.Ascii,
          imeAction = ImeAction.Next,
        ),
      modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
      value = state.clientSecret.reveal(),
      onValueChange = onClientSecretChanged,
      label = { Text("Client Secret / Access Secret") },
      placeholder = { Text("Enter your project secret") },
      singleLine = true,
      isError = state.validationAttempted && state.clientSecret.isBlank,
      supportingText =
        if (state.validationAttempted && state.clientSecret.isBlank) {
          { Text("Client Secret is required") }
        } else {
          null
        },
      visualTransformation =
        if (secretVisible) {
          VisualTransformation.None
        } else {
          PasswordVisualTransformation()
        },
      trailingIcon = {
        TextButton(
          onClick = { secretVisible = !secretVisible },
          contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
          Text(if (secretVisible) "Hide" else "Show")
        }
      },
      keyboardOptions =
        KeyboardOptions(
          keyboardType = KeyboardType.Password,
          imeAction = ImeAction.Done,
        ),
      modifier = Modifier.fillMaxWidth(),
    )

    TextButton(
      onClick = onToggleAdvanced,
      contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
      Text(if (state.showAdvanced) "Hide advanced field  −" else "Advanced: sample Device ID  +")
    }
    if (state.showAdvanced) {
      OutlinedTextField(
        value = state.sampleDeviceId.reveal(),
        onValueChange = onSampleDeviceIdChanged,
        label = { Text("Sample Device ID (optional)") },
        supportingText = {
          Text("Useful for older projects when automatic device lookup is incomplete.")
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
      )
    }

    CredentialPrivacyNote()
    PrimaryActionButton(
      text =
        if (state.isCredentialUpdate) {
          "Verify, save, and sync"
        } else {
          "Connect and import devices"
        },
      onClick = onImport,
      modifier = Modifier.padding(top = 18.dp),
    )
  }
}

@Composable
private fun CredentialPrivacyNote() {
  Surface(
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
  ) {
    Row(modifier = Modifier.padding(16.dp)) {
      LockMark(Modifier.size(30.dp))
      Spacer(Modifier.width(12.dp))
      Column {
        Text(
          "Encrypted after verification",
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          text =
            "This form stays only in memory while Tuya verifies it. After success, the region, Client ID, and secret are encrypted on this device.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}

@Composable
private fun LockMark(modifier: Modifier = Modifier) {
  val color = MaterialTheme.colorScheme.primary
  Canvas(modifier = modifier.semantics { contentDescription = "Private" }) {
    val stroke = size.width * 0.09f
    drawRoundRect(
      color = color,
      topLeft = Offset(size.width * 0.25f, size.height * 0.03f),
      size = Size(size.width * 0.50f, size.height * 0.62f),
      cornerRadius = CornerRadius(size.width * 0.24f),
      style = Stroke(width = stroke),
    )
    drawRoundRect(
      color = color,
      topLeft = Offset(size.width * 0.12f, size.height * 0.38f),
      size = Size(size.width * 0.76f, size.height * 0.58f),
      cornerRadius = CornerRadius(size.width * 0.13f),
    )
  }
}

private val TuyaCloudRegion.displayName: String
  get() =
    when (this) {
      TuyaCloudRegion.CHINA -> "Mainland China"
      TuyaCloudRegion.WESTERN_AMERICA -> "Western America"
      TuyaCloudRegion.EASTERN_AMERICA -> "Eastern America"
      TuyaCloudRegion.CENTRAL_EUROPE -> "Central Europe"
      TuyaCloudRegion.WESTERN_EUROPE -> "Western Europe"
      TuyaCloudRegion.INDIA -> "India"
      TuyaCloudRegion.SINGAPORE -> "Singapore"
    }
