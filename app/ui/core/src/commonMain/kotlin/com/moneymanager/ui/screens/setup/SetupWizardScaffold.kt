package com.moneymanager.ui.screens.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Chrome shared by every wizard step: step indicator on top, the step body in the middle, and the
 * Back / Skip / Next controls at the bottom. The body owns its own scrolling — the step composables are
 * full screens that already scroll — so nothing here scrolls.
 */
@Composable
@Suppress("LongParameterList")
fun SetupWizardScaffold(
    steps: List<SetupWizardStep>,
    currentStep: SetupWizardStep,
    canGoBack: Boolean,
    canSkip: Boolean,
    nextEnabled: Boolean,
    nextLabel: String,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The wizard replaces the app Scaffold, so it must inset itself against the system bars and the
        // keyboard rather than inheriting that from the Scaffold.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Set up Money Manager", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Step ${steps.indexOf(currentStep) + 1} of ${steps.size} · ${currentStep.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onExit) { Text("Skip setup") }
            }

            Spacer(modifier = Modifier.height(12.dp))
            StepIndicator(steps = steps, currentStep = currentStep)
            Spacer(modifier = Modifier.height(16.dp))

            Text(currentStep.title, style = MaterialTheme.typography.titleLarge)
            Text(
                currentStep.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.weight(1f).fillMaxWidth(), content = content)

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, enabled = canGoBack) { Text("Back") }
                Spacer(modifier = Modifier.weight(1f))
                if (canSkip) {
                    TextButton(onClick = onSkip) { Text("Skip") }
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Button(onClick = onNext, enabled = nextEnabled) { Text(nextLabel) }
            }
        }
    }
}

/** Dots for every step; steps up to and including the current one are filled. */
@Composable
private fun StepIndicator(
    steps: List<SetupWizardStep>,
    currentStep: SetupWizardStep,
) {
    val currentIndex = steps.indexOf(currentStep)
    Row(
        // Six labelled steps don't fit across a phone, so let the indicator scroll rather than clip.
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, step ->
            val color =
                if (index <= currentIndex) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            Box(
                modifier = Modifier.size(10.dp).clip(CircleShape).background(color),
            )
            Text(
                step.title,
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (index == currentIndex) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}
