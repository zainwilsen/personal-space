package app.personalspace.vault.presentation.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.personalspace.vault.core.designsystem.BauhausColors
import app.personalspace.vault.core.designsystem.BauhausDimens
import app.personalspace.vault.core.designsystem.BauhausOutlinedButton
import app.personalspace.vault.core.designsystem.BauhausPrimaryButton
import app.personalspace.vault.core.designsystem.BauhausSurfaceCard
import app.personalspace.vault.core.designsystem.PersonalSpaceLogo

@Composable
fun InitialSetupScreen(
    isBusy: Boolean,
    errorMessage: String?,
    actionsEnabled: Boolean,
    onCreateNewSpace: () -> Unit,
    onRestoreExistingSpace: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BauhausColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = BauhausDimens.PageHorizontalPadding,
                    vertical = 24.dp
                )
                .padding(top = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            PersonalSpaceLogo(
                emblemSize = 112.dp,
                showWordmark = false
            )

            Text(
                text = "Personal Space",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = BauhausColors.Black
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

            BauhausPrimaryButton(
                text = "Create New",
                onClick = onCreateNewSpace,
                enabled = actionsEnabled && !isBusy,
                modifier = Modifier.fillMaxWidth()
            )

            BauhausOutlinedButton(
                text = "Restore Existing",
                onClick = onRestoreExistingSpace,
                enabled = actionsEnabled && !isBusy,
                modifier = Modifier.fillMaxWidth()
            )

            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(24.dp),
                    color = BauhausColors.Red,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}