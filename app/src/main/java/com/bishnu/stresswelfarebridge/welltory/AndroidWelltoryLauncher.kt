package com.bishnu.stresswelfarebridge.welltory

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * The Android side of the launch: resolves which documented link to open and starts it.
 *
 * The URI text itself is built by [WelltoryRequestBuilder] (pure, unit-tested); this class only adds
 * the `Intent` plumbing the documented mechanism requires, including the
 * `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP` flags Welltory asks partners to set so the
 * host app is not duplicated in the task stack.
 */
object AndroidWelltoryLauncher {

    private const val TAG = "WelltoryLauncher"

    /** Outcome of trying to start a Welltory measurement. */
    sealed class LaunchOutcome {
        /** The measurement screen was opened. */
        data class Started(val usedInstallFallback: Boolean) : LaunchOutcome()

        /** Welltory could not be started; [reason] is safe to show the user. */
        data class Failed(val reason: String) : LaunchOutcome()
    }

    /**
     * Builds the `callback` value in the documented `package/activity` form.
     *
     * Must name an activity declared `android:exported="true"` in the manifest, since Welltory
     * launches it from another process.
     */
    fun callbackComponent(context: Context, activity: Class<*>): String =
        "${context.packageName}/${activity.name}"

    /** True when any installed app can handle the `welltory://` measurement scheme. */
    fun isWelltoryInstalled(context: Context): Boolean = runCatching {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse(WelltoryConfig.MEASUREMENT_SCHEME_URI))
        probe.resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    /**
     * Opens the Welltory measurement for the outstanding request.
     *
     * When Welltory is installed the direct `welltory://` link is used; otherwise the documented Play
     * link is opened, which shows Welltory's install page. If neither can be resolved - for example
     * with no Play app and no Welltory - this reports failure rather than pretending to have started.
     */
    fun launch(context: Context, pending: WelltoryRequestRegistry.PendingRequest): LaunchOutcome {
        val callback = callbackComponent(context, WelltoryCallbackActivity::class.java)
        val installed = isWelltoryInstalled(context)
        val uri = WelltoryRequestBuilder.uriToOpen(
            schemeResolvable = installed,
            callbackComponent = callback,
            nonce = pending.nonce,
            accountId = pending.accountId
        )
        return start(context, uri, usedInstallFallback = !installed)
    }

    /** Opens the Welltory install listing, used by the "not installed" prompt. */
    fun openInstallPage(context: Context): LaunchOutcome =
        start(context, WelltoryRequestBuilder.installUri(), usedInstallFallback = true)

    private fun start(context: Context, uri: String, usedInstallFallback: Boolean): LaunchOutcome {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            // Documented by Welltory to avoid duplicating the partner app's task entries.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return try {
            context.startActivity(intent)
            LaunchOutcome.Started(usedInstallFallback)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity could handle the Welltory link", e)
            LaunchOutcome.Failed(
                "Welltory could not be opened on this device. Install Welltory from the Play Store " +
                    "and try again."
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Welltory link could not be started", e)
            LaunchOutcome.Failed("StressSense is not allowed to open Welltory on this device.")
        }
    }
}