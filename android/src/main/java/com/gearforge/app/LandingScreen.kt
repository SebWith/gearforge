package com.gearforge.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gearforge.core.GearParams

// The hero artwork sits on a dark gradient, so the brand and controls use a fixed
// light-on-dark palette for consistent contrast regardless of the app theme.
private val HeroText = Color(0xFFEAF6FF)
private val HeroAccent = Color(0xFF82D1FF)
private val HeroOnAccent = Color(0xFF00344C)

/** Landing page with a hero 3D gear, a premium brand lockup and one clear call-to-action. */
@Composable
fun LandingScreen(
    darkTheme: Boolean,
    lang: I18n.Lang,
    onStart: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onLoadSaved: (GearParams) -> Unit
) {
    var showSavedFiles by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val shortestSide = minOf(maxWidth, maxHeight)
        val isLandscape = maxWidth > maxHeight
        val isWide = shortestSide >= 600.dp
        val horizontalPadding = if (isWide) 64.dp else 24.dp
        val maxContentWidth = 520.dp

        // Measured heights of the top brand block and the bottom CTA block size the centred
        // gear so it never overlaps either block on any screen shape or aspect ratio.
        var headerHeight by remember(isLandscape) { mutableStateOf(if (isLandscape) 92.dp else 168.dp) }
        var footerHeight by remember(isLandscape) { mutableStateOf(if (isLandscape) 72.dp else 220.dp) }

        // Equal clearance above and below keeps the gear exactly on the screen centre line.
        val reserve = maxOf(headerHeight, footerHeight) + 16.dp
        val gearSize = minOf(
            maxWidth - horizontalPadding * 2 - 24.dp,
            maxHeight - reserve * 2
        ).coerceIn(72.dp, 520.dp)

        // Hero background (dark blue gradient with light rays) behind everything.
        Image(
            painter = painterResource(R.drawable.bg_hero),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Soft scrim so the brand and buttons stay readable over the background.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (darkTheme) 0.40f else 0.24f))
        )

        // Top brand lockup.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = horizontalPadding)
                .onGloballyPositioned { headerHeight = with(density) { it.size.height.toDp() } }
        ) {
            Spacer(Modifier.height(if (isLandscape) 14.dp else 24.dp))
            Text(
                I18n.t(lang, "brand_name"),
                fontSize = when {
                    isWide -> 68.sp
                    isLandscape -> 38.sp
                    else -> 48.sp
                },
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = HeroText,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.45f),
                        offset = Offset(0f, 3f),
                        blurRadius = 16f
                    )
                )
            )
            Spacer(Modifier.height(2.dp))
            Text(
                I18n.t(lang, "brand_subtitle"),
                fontSize = if (isLandscape) 11.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = if (isLandscape) 3.sp else 4.sp,
                color = HeroText.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(if (isLandscape) 6.dp else 10.dp))
            Text(
                I18n.t(lang, "tagline"),
                fontSize = when {
                    isWide -> 15.sp
                    isLandscape -> 12.sp
                    else -> 14.sp
                },
                lineHeight = when {
                    isWide -> 21.sp
                    isLandscape -> 16.sp
                    else -> 19.sp
                },
                textAlign = TextAlign.Center,
                color = HeroText.copy(alpha = 0.78f)
            )
        }

        // Hero gear artwork, centred on the screen (transparent-background image).
        Image(
            painter = painterResource(R.drawable.hero_gear),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.Center)
                .size(gearSize)
        )

        // Bottom call-to-action block.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = horizontalPadding)
                .padding(bottom = if (isLandscape) 12.dp else 16.dp)
                .onGloballyPositioned { footerHeight = with(density) { it.size.height.toDp() } }
        ) {
            if (isLandscape) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                ) {
                    Button(
                        onClick = onStart,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HeroAccent,
                            contentColor = HeroOnAccent
                        ),
                        modifier = Modifier
                            .weight(1.6f)
                            .height(56.dp)
                    ) {
                        Text(I18n.t(lang, "create_new_gear"), fontSize = 18.sp)
                    }
                    LandingSecondaryButton(
                        text = I18n.t(lang, "saved_files"),
                        onClick = { showSavedFiles = true },
                        icon = Icons.Filled.FolderOpen,
                        modifier = Modifier.weight(1f).height(56.dp)
                    )
                    LandingSecondaryButton(
                        text = I18n.t(lang, "settings"),
                        onClick = onSettings,
                        modifier = Modifier.weight(1f).height(56.dp)
                    )
                    LandingSecondaryButton(
                        text = I18n.t(lang, "about"),
                        onClick = onAbout,
                        modifier = Modifier.weight(1f).height(56.dp)
                    )
                }
            } else {
                Button(
                    onClick = onStart,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HeroAccent,
                        contentColor = HeroOnAccent
                    ),
                    modifier = Modifier
                        .widthIn(max = maxContentWidth)
                        .fillMaxWidth()
                        .height(60.dp)
                ) {
                    Text(I18n.t(lang, "create_new_gear"), fontSize = 19.sp)
                }
                Spacer(Modifier.height(12.dp))
                LandingSecondaryButton(
                    text = I18n.t(lang, "saved_files"),
                    onClick = { showSavedFiles = true },
                    icon = Icons.Filled.FolderOpen,
                    modifier = Modifier
                        .widthIn(max = maxContentWidth)
                        .fillMaxWidth()
                        .height(52.dp)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .widthIn(max = maxContentWidth)
                        .fillMaxWidth()
                ) {
                    LandingSecondaryButton(
                        text = I18n.t(lang, "settings"),
                        onClick = onSettings,
                        modifier = Modifier.weight(1f).height(52.dp)
                    )
                    LandingSecondaryButton(
                        text = I18n.t(lang, "about"),
                        onClick = onAbout,
                        modifier = Modifier.weight(1f).height(52.dp)
                    )
                }
            }
        }
    }

    if (showSavedFiles) {
        SavedFilesSheet(lang = lang, onLoad = onLoadSaved, onDismiss = { showSavedFiles = false })
    }
}

/** Secondary landing action: a rounded outlined button tuned for the light hero. */
@Composable
private fun LandingSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = HeroText),
        border = BorderStroke(1.dp, HeroAccent.copy(alpha = 0.55f)),
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 16.sp)
    }
}

/** Bottom sheet listing saved gear designs, with load and delete actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedFilesSheet(
    lang: I18n.Lang,
    onLoad: (GearParams) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(SavedConfigs.list(context)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                I18n.t(lang, "saved_files"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Text(
                I18n.t(lang, "saved_files_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )
            if (files.isEmpty()) {
                Text(
                    I18n.t(lang, "no_saved_files"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            } else {
                files.forEach { (name, p) ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onLoad(p); onDismiss() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "$name \u2014 ${p.teeth}${I18n.t(lang, "unit_teeth_short")} ${I18n.t(lang, "unit_module_short")}${Format.decimal(p.module, 2, lang)}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { SavedConfigs.delete(context, name); files = SavedConfigs.list(context) }) {
                            Icon(Icons.Filled.Delete, contentDescription = I18n.t(lang, "delete"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AboutDialog(lang: I18n.Lang, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t(lang, "about_title")) },
        text = { Text(I18n.t(lang, "about_body")) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(I18n.t(lang, "close")) } }
    )
}
