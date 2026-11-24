package app.personalspace.vault.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class BottomNavTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val activeIcon: ImageVector
) {
    HOME("dashboard", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    FOLDERS("folders", "Folders", Icons.Outlined.Folder, Icons.Filled.Folder),
    STATISTICS("statistics", "Statistics", Icons.Outlined.BarChart, Icons.Filled.BarChart),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
}

enum class BauhausCardArcCorner {
    TOP_RIGHT,
    BOTTOM_RIGHT
}

/**
 * Primary screen container enforcing POCO F7 safe-area handling:
 * - Status bar + punch-hole display cutout safe area
 * - Additional 28dp top breathing space so headers are positioned comfortably lower
 * - Fixed bottom navigation bar above gesture/navigation insets when requested
 */
@Composable
fun BauhausScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.headlineLarge,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    activeTab: BottomNavTab? = null,
    onSelectTab: ((BottomNavTab) -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(BauhausColors.Background),
        containerColor = BauhausColors.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BauhausTopHeader(
                title = title,
                titleStyle = titleStyle,
                subtitle = subtitle,
                onBack = onBack,
                actions = actions
            )
        },
        bottomBar = {
            if (activeTab != null && onSelectTab != null) {
                BauhausBottomNavigationBar(
                    selectedTab = activeTab,
                    onSelectTab = onSelectTab
                )
            }
        },
        floatingActionButton = {
            val fabLiftModifier = if (activeTab != null && onSelectTab != null) {
                Modifier.padding(bottom = 18.dp, end = 4.dp)
            } else {
                Modifier
                    .windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                    )
                    .padding(bottom = 24.dp, end = 4.dp)
            }
            Box(modifier = fabLiftModifier) {
                floatingActionButton()
            }
        },
        content = content
    )
}

@Composable
fun BauhausTopHeader(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.headlineLarge,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BauhausColors.Background)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                )
            )
            .padding(top = BauhausDimens.HeaderExtraTopSpacing)
            .padding(horizontal = BauhausDimens.PageHorizontalPadding)
            .testTag("bauhaus_top_header")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = BauhausDimens.MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(BauhausDimens.MinTouchTarget)
                        .testTag("header_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Navigate back",
                        tint = BauhausColors.Black
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            if (title.isNotBlank() || !subtitle.isNullOrBlank()) {
                Column(modifier = Modifier.weight(1f)) {
                    if (title.isNotBlank()) {
                        Text(
                            text = title,
                            style = titleStyle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() }
                        )
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = BauhausColors.SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                content = actions
            )
        }

        Spacer(modifier = Modifier.height(BauhausDimens.HeaderBottomSpacing))
    }
}

/**
 * Five equally sized navigation tabs with outline icons and a crisp Bauhaus red
 * indicator bar beneath the active tab icon, fixed above system navigation insets.
 */
@Composable
fun BauhausBottomNavigationBar(
    selectedTab: BottomNavTab,
    onSelectTab: (BottomNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BauhausColors.LightSurface)
            .windowInsetsPadding(
                WindowInsets.navigationBars.only(
                    WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                )
            )
            .testTag("bauhaus_bottom_nav")
    ) {
        HorizontalDivider(thickness = 1.dp, color = BauhausColors.Divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BauhausDimens.BottomNavHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavTab.entries.forEach { tab ->
                val isSelected = tab == selectedTab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(
                            minWidth = BauhausDimens.MinTouchTarget,
                            minHeight = BauhausDimens.MinTouchTarget
                        )
                        .height(BauhausDimens.BottomNavHeight)
                        .clickable(
                            role = Role.Tab,
                            onClickLabel = tab.label
                        ) { onSelectTab(tab) }
                        .semantics {
                            contentDescription = tab.label
                            selected = isSelected
                        }
                        .testTag("nav_tab_${tab.route}"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isSelected) tab.activeIcon else tab.icon,
                            contentDescription = null,
                            tint = BauhausColors.Black,
                            modifier = Modifier.size(25.dp)
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        // Crisp Bauhaus red underline bar beneath active icon matching Minimal Bauhaus.png
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) BauhausColors.Red else Color.Transparent
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BauhausSummaryCard(
    icon: ImageVector,
    label: String,
    value: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingStatus: String? = null,
    arcCorner: BauhausCardArcCorner = BauhausCardArcCorner.BOTTOM_RIGHT
) {
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 122.dp)
            .clip(RoundedCornerShape(BauhausDimens.CornerRadius))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$label: $value" },
        color = BauhausColors.CardSurface,
        shape = RoundedCornerShape(BauhausDimens.CornerRadius)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Clipped colorful Bauhaus quarter-circle corner matching Minimal Bauhaus.png
            Canvas(
                modifier = Modifier
                    .matchParentSize()
            ) {
                val r = 46.dp.toPx()
                val d = r * 2f
                when (arcCorner) {
                    BauhausCardArcCorner.TOP_RIGHT -> {
                        drawArc(
                            color = accentColor,
                            startAngle = 90f,
                            sweepAngle = 90f,
                            useCenter = true,
                            topLeft = Offset(size.width - r, -r),
                            size = Size(d, d)
                        )
                    }
                    BauhausCardArcCorner.BOTTOM_RIGHT -> {
                        drawArc(
                            color = accentColor,
                            startAngle = 180f,
                            sweepAngle = 90f,
                            useCenter = true,
                            topLeft = Offset(size.width - r, size.height - r),
                            size = Size(d, d)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = BauhausColors.Black,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.displaySmall,
                    color = BauhausColors.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = supportingStatus ?: label,
                    style = MaterialTheme.typography.labelMedium,
                    color = BauhausColors.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun BauhausSurfaceCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(BauhausDimens.CornerRadius)
    val bg = BauhausColors.CardSurface
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(clickableModifier),
        color = bg,
        shape = shape
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun BauhausSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .background(BauhausColors.Red)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.defaultMinSize(
                    minWidth = BauhausDimens.MinTouchTarget,
                    minHeight = BauhausDimens.MinTouchTarget
                )
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = BauhausColors.Red
                )
            }
        }
    }
}

@Composable
fun BauhausPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(
            minWidth = BauhausDimens.MinTouchTarget,
            minHeight = BauhausDimens.MinTouchTarget
        ),
        shape = RoundedCornerShape(BauhausDimens.SmallCornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = BauhausColors.Red,
            contentColor = BauhausColors.LightSurface,
            disabledContainerColor = BauhausColors.Divider,
            disabledContentColor = BauhausColors.SecondaryText
        )
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = Color.Unspecified)
    }
}

@Composable
fun BauhausOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(
            minWidth = BauhausDimens.MinTouchTarget,
            minHeight = BauhausDimens.MinTouchTarget
        ),
        shape = RoundedCornerShape(BauhausDimens.SmallCornerRadius),
        border = BorderStroke(1.dp, BauhausColors.Black),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = BauhausColors.Black
        )
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun BauhausTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 12,
    isError: Boolean = false,
    supportingText: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(BauhausDimens.SmallCornerRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = BauhausColors.LightSurface,
            unfocusedContainerColor = BauhausColors.LightSurface,
            focusedBorderColor = BauhausColors.Black,
            unfocusedBorderColor = BauhausColors.Divider,
            focusedLabelColor = BauhausColors.Black,
            unfocusedLabelColor = BauhausColors.SecondaryText,
            cursorColor = BauhausColors.Red,
            errorBorderColor = BauhausColors.Red
        )
    )
}

@Composable
fun BauhausEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    BauhausSurfaceCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PersonalSpaceLogo(emblemSize = 48.dp, showWordmark = false)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = BauhausColors.SecondaryText
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(14.dp))
                BauhausPrimaryButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
fun BauhausErrorBanner(
    message: String,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                BorderStroke(1.dp, BauhausColors.Red),
                RoundedCornerShape(BauhausDimens.SmallCornerRadius)
            ),
        color = BauhausColors.LightSurface,
        shape = RoundedCornerShape(BauhausDimens.SmallCornerRadius)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = BauhausColors.Red,
                modifier = Modifier.weight(1f)
            )
            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text("Dismiss", color = BauhausColors.Black)
                }
            }
        }
    }
}

@Composable
fun BauhausConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BauhausColors.LightSurface,
        titleContentColor = BauhausColors.Black,
        textContentColor = BauhausColors.Black,
        title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .defaultMinSize(minHeight = BauhausDimens.MinTouchTarget)
                    .testTag("dialog_confirm_primary_btn"),
                shape = RoundedCornerShape(BauhausDimens.SmallCornerRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (destructive) BauhausColors.Red else BauhausColors.Blue,
                    contentColor = BauhausColors.LightSurface
                )
            ) {
                Text(
                    text = confirmLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = BauhausColors.LightSurface
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minHeight = BauhausDimens.MinTouchTarget)
                    .testTag("dialog_confirm_cancel_btn")
            ) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = BauhausColors.Black
                )
            }
        }
    )
}

@Composable
fun BauhausTagChip(
    label: String,
    selected: Boolean = false,
    accentColor: Color = BauhausColors.Red,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(BauhausDimens.SmallCornerRadius)
    val bg = if (selected) accentColor else BauhausColors.LightSurface
    val fg = if (selected) {
        if (accentColor == BauhausColors.Yellow) BauhausColors.Black else BauhausColors.LightSurface
    } else {
        BauhausColors.Black
    }

    val clickMod = if (onClick != null) {
        Modifier
            .defaultMinSize(minHeight = 36.dp)
            .clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(BorderStroke(1.dp, if (selected) accentColor else BauhausColors.Divider), shape)
            .then(clickMod)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg
        )
    }
}
