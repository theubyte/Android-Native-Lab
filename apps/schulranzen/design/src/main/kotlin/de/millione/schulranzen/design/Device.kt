package de.millione.schulranzen.design

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Phones are for reading, scanning and learning; writing belongs to the
 * tablet. The line is Android's own for tablets: the short side of the screen
 * in dp, so it does not flip when the device is rotated.
 */
const val PHONE_MAX_SMALLEST_WIDTH_DP = 600

fun isPhone(context: Context): Boolean =
    context.resources.configuration.smallestScreenWidthDp < PHONE_MAX_SMALLEST_WIDTH_DP

/** Follows the configuration, so a foldable changes sides when it is opened. */
@Composable
fun rememberIsPhone(): Boolean = LocalConfiguration.current.smallestScreenWidthDp < PHONE_MAX_SMALLEST_WIDTH_DP
