package com.prfd.tinytuya.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prfd.tinytuya.ui.components.BrandMark

@Composable
internal fun WelcomeScreen(
  onStartSetup: () -> Unit,
  onSkipGuide: () -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 20.dp)
  ) {
    Wordmark()
    Spacer(Modifier.height(28.dp))
    HomeNetworkIllustration()
    Spacer(Modifier.height(32.dp))
    Text(
      text = "Your devices.\nYour network.",
      style = MaterialTheme.typography.displaySmall,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(14.dp))
    Text(
      text =
        "A focused controller powered by TinyTuya. Import once from Tuya, then keep everyday control on your local network.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    PrivacyPoint(
      mark = "1",
      title = "Local by default",
      body = "Device discovery and control stay on your Wi-Fi.",
    )
    PrivacyPoint(
      mark = "2",
      title = "No surveillance stack",
      body = "No ads, analytics, tracking SDKs, or background cloud polling.",
    )
    PrivacyPoint(
      mark = "3",
      title = "You hold the keys",
      body = "Cloud credentials are used only when you explicitly import or sync.",
    )
    Spacer(Modifier.height(28.dp))
    PrimaryActionButton(
      text = "Set up TinyTuya",
      onClick = onStartSetup,
    )
    TextButton(
      onClick = onSkipGuide,
      modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
      Text("I already have cloud credentials")
    }
    Text(
      text = "Open-source software · Not affiliated with Tuya",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
    )
  }
}

@Composable
private fun PrivacyPoint(
  mark: String,
  title: String,
  body: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
      Box(
        modifier = Modifier.size(42.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = mark,
          style = MaterialTheme.typography.labelLarge,
          fontSize = 12.sp,
        )
      }
    }
    Spacer(Modifier.width(14.dp))
    Column {
      Text(text = title, style = MaterialTheme.typography.titleMedium)
      Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}

@Composable
private fun Wordmark() {
  Row(verticalAlignment = Alignment.CenterVertically) {
    BrandMark(Modifier.size(42.dp))
    Spacer(Modifier.width(12.dp))
    Column {
      Text(
        text = "TinyTuya",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = "LOCAL HOME",
        style = MaterialTheme.typography.labelLarge,
        fontSize = 10.sp,
        letterSpacing = 1.7.sp,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
private fun HomeNetworkIllustration() {
  val primary = MaterialTheme.colorScheme.primary
  val primaryContainer = MaterialTheme.colorScheme.primaryContainer
  val tertiary = MaterialTheme.colorScheme.tertiary
  val surface = MaterialTheme.colorScheme.surface
  val outline = MaterialTheme.colorScheme.outlineVariant
  val onSurface = MaterialTheme.colorScheme.onSurface

  Box(
    modifier =
      Modifier.fillMaxWidth()
        .height(246.dp)
        .clip(MaterialTheme.shapes.extraLarge)
        .background(primaryContainer.copy(alpha = 0.65f))
        .semantics { contentDescription = "A private home connected to nearby devices" }
  ) {
    Box(
      Modifier.size(130.dp)
        .offset(x = (-35).dp, y = (-26).dp)
        .clip(CircleShape)
        .background(tertiary.copy(alpha = 0.13f))
    )
    Box(
      Modifier.size(115.dp)
        .align(Alignment.BottomEnd)
        .offset(x = 28.dp, y = 30.dp)
        .clip(CircleShape)
        .background(primary.copy(alpha = 0.10f))
    )

    Canvas(Modifier.fillMaxSize()) {
      val center = Offset(size.width * 0.50f, size.height * 0.55f)
      val deviceLeft = Offset(size.width * 0.14f, size.height * 0.68f)
      val deviceRight = Offset(size.width * 0.86f, size.height * 0.35f)
      val deviceTop = Offset(size.width * 0.67f, size.height * 0.13f)
      listOf(deviceLeft, deviceRight, deviceTop).forEach { node ->
        drawLine(
          color = primary.copy(alpha = 0.36f),
          start = center,
          end = node,
          strokeWidth = 3.dp.toPx(),
        )
      }

      fun drawNode(at: Offset, radius: Float) {
        drawCircle(surface, radius, at)
        drawCircle(outline, radius, at, style = Stroke(width = 1.dp.toPx()))
        drawCircle(primary, radius * 0.22f, at)
      }
      drawNode(deviceLeft, 24.dp.toPx())
      drawNode(deviceRight, 24.dp.toPx())
      drawNode(deviceTop, 20.dp.toPx())

      val houseWidth = 116.dp.toPx()
      val houseHeight = 92.dp.toPx()
      val houseLeft = center.x - houseWidth / 2
      val houseTop = center.y - houseHeight * 0.30f
      val roof =
        Path().apply {
          moveTo(houseLeft - 2.dp.toPx(), houseTop + 5.dp.toPx())
          lineTo(center.x, houseTop - 48.dp.toPx())
          lineTo(houseLeft + houseWidth + 2.dp.toPx(), houseTop + 5.dp.toPx())
          close()
        }
      drawPath(roof, primary)
      drawRoundRect(
        color = surface,
        topLeft = Offset(houseLeft, houseTop),
        size = Size(houseWidth, houseHeight),
        cornerRadius = CornerRadius(14.dp.toPx()),
      )
      drawRoundRect(
        color = onSurface,
        topLeft = Offset(center.x - 12.dp.toPx(), houseTop + 47.dp.toPx()),
        size = Size(24.dp.toPx(), 45.dp.toPx()),
        cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx()),
      )
      drawCircle(
        tertiary,
        2.dp.toPx(),
        Offset(center.x + 6.dp.toPx(), houseTop + 69.dp.toPx()),
      )
    }

    Surface(
      color = MaterialTheme.colorScheme.surface,
      shape = CircleShape,
      shadowElevation = 5.dp,
      modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-12).dp),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(primary))
        Spacer(Modifier.width(8.dp))
        Text("LAN control ready", style = MaterialTheme.typography.labelLarge)
      }
    }
  }
}
