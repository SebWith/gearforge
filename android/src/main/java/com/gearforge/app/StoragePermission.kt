package com.gearforge.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Runtime gate for the legacy `WRITE_EXTERNAL_STORAGE` grant.
 *
 * `ExportManager.saveToDownloads` has two paths:
 *
 *  - **API >= 29 (Q):** writes through `MediaStore.Downloads`, which is app-scoped and
 *    needs no permission at all.
 *  - **API 24..28:** writes to `Environment.getExternalStoragePublicDirectory(DOWNLOADS)`
 *    directly. `WRITE_EXTERNAL_STORAGE` has been a **dangerous** permission since API 23,
 *    so the manifest declaration alone is not enough — it must be granted at runtime.
 *
 * The `AndroidManifest.xml` entry carries `android:maxSdkVersion="28"` for exactly this
 * reason. Because `minSdk` is 24, the whole Android 7.0–9.0 range takes the second path.
 *
 * Before this gate existed, no runtime request was made anywhere in the app, so every
 * export on those API levels failed with a `SecurityException`. `saveToDownloads` already
 * converted it into `Result.failure`, so the user got a generic "export failed" toast with
 * no explanation and no way to fix it — and the bug was invisible on the API 36 emulator
 * used for the store screenshots, because that path never touches the permission.
 *
 * Call this from a **single choke point** in front of the export flow so every entry path
 * (Pro, remaining free export, rewarded ad) inherits the gate.
 *
 * @param onDenied invoked when the user refuses the grant. The caller must not proceed.
 * @return a function that runs its argument immediately when no grant is needed, and
 *   after the grant is obtained otherwise.
 */
@Composable
internal fun rememberLegacyStoragePermission(onDenied: () -> Unit): ((() -> Unit) -> Unit) {
    val context = LocalContext.current
    // Keep the latest callback without re-registering the launcher on every recomposition.
    val denied by rememberUpdatedState(onDenied)

    // The export continuation to resume once the system dialog is answered. Held in
    // Compose state so it survives the configuration change the dialog may trigger.
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val continuation = pending.value
        pending.value = null
        if (granted) continuation?.invoke() else denied()
    }

    return remember(launcher, context) {
        { onGranted ->
            if (legacyStoragePermissionGranted(context) || !legacyStoragePermissionRequired()) {
                onGranted()
            } else {
                pending.value = onGranted
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}

/** True when the export path is the pre-Q direct-to-external-storage write. */
internal fun legacyStoragePermissionRequired(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

/** True when the legacy write path is already permitted (or is not in use). */
internal fun legacyStoragePermissionGranted(context: Context): Boolean =
    !legacyStoragePermissionRequired() ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
