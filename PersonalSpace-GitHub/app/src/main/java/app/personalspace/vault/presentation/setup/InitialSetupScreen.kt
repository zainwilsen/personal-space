package app.personalspace.vault.presentation.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.personalspace.vault.core.designsystem.BauhausColors
import app.personalspace.vault.core.designsystem.BauhausDimens
import app.personalspace.vault.core.designsystem.BauhausOutlinedButton
import app.personalspace.vault.core.designsystem.BauhausPrimaryButton
import app.personalspace.vault.core.designsystem.BauhausScreenScaffold
import app.personalspace.vault.core.designsystem.BauhausSurfaceCard

@Composable
fun InitialSetupScreen(
    isBusy: Boolean,
    errorMessage: String?,
    actionsEnabled: Boolean,
    onCreateNewSpace: () -> Unit,
    onRestoreExistingSpace: () -> Unit
) {
    BauhausScreenScaffold(title = "Personal Space") { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = BauhausDimens.PageHorizontalPadding,
                    vertical = 24.dp
                )
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "How would you like to start?",
                style = MaterialTheme.typography.headlineLarge,
                color = BauhausColors.Black
            )
            Text(
                text = "Naya space banao ya apne purane Personal Space ka backup restore karo.",
                style = MaterialTheme.typography.bodyMedium,
                color = BauhausColors.SecondaryText
            )

            errorMessage?.let { message ->
                BauhausSurfaceCard {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BauhausColors.Red
                    )
                }
            }

            BauhausSurfaceCard(elevated = true) {
                Text(
                    text = "NEW SPACE",
                    style = MaterialTheme.typography.labelMedium,
                    color = BauhausColors.Red
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Create New Space",
                    style = MaterialTheme.typography.titleLarge,
                    color = BauhausColors.Black
                )
                Text(
                    text = "Bilkul fresh workspace create hoga. Is device par aap zero se start karenge.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BauhausColors.SecondaryText
                )
                Spacer(modifier = Modifier.height(12.dp))
                BauhausPrimaryButton(
                    text = if (isBusy) "Please wait…" else "Create New Space",
                    onClick = onCreateNewSpace,
                    enabled = actionsEnabled && !isBusy,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            BauhausSurfaceCard {
                Text(
                    text = "EXISTING SPACE",
                    style = MaterialTheme.typography.labelMedium,
                    color = BauhausColors.Blue
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Restore Existing Space",
                    style = MaterialTheme.typography.titleLarge,
                    color = BauhausColors.Black
                )
                Text(
                    text = "Apni .psbackup file select karo ya Google Drive connect karke purana backup restore karo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BauhausColors.SecondaryText
                )
                Spacer(modifier = Modifier.height(12.dp))
                BauhausOutlinedButton(
                    text = if (isBusy) "Please wait…" else "Restore Existing Space",
                    onClick = onRestoreExistingSpace,
                    enabled = actionsEnabled && !isBusy,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (isBusy) {
                CircularProgressIndicator(color = BauhausColors.Red)
            }
        }
    }
}
