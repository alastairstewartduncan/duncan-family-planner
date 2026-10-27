package uk.co.duncan.familyplanner.brick

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager

/** Permissions, app lists and the packages that are always allowed. */
object BrickSetup {

    fun serviceEnabled(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo?.serviceInfo?.let { si -> si.packageName == context.packageName && si.name == BlockerService::class.java.name } == true
        }
    }

    fun adminComponent(context: Context) = ComponentName(context, BrickAdminReceiver::class.java)

    fun adminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(adminComponent(context)) == true

    fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android 13+ hides accessibility for side-loaded apps until "Allow restricted settings" is ticked here. */
    fun appInfo(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun nfcSettings() = Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun addAdmin(context: Context) = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Stops the planner being uninstalled without a parent knowing, so Brick can't be dodged.",
        )

    fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    // ---- Which apps are always fine ---------------------------------------------------------

    /** Phone, messages, the home screen, the keyboard and the system itself are never blocked. */
    fun alwaysAllowed(context: Context): Set<String> {
        val pm = context.packageManager
        val set = mutableSetOf(
            context.packageName, "android", "com.android.systemui",
            "com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer",
            "com.android.incallui", "com.samsung.android.incallui",
            "com.google.android.apps.safetyhub", "com.android.nfc",
        )
        launchers(context).forEach(set::add)
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(set::add)
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(set::add)
        runCatching {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { set += it.packageName }
        }
        // Never let these through, even if someone picks them: they're how Brick would be switched off.
        set.removeAll(neverAllowed(pm))
        return set
    }

    /** Settings and installers stay blocked while bricked so the blocker can't be turned off. */
    fun neverAllowed(pm: PackageManager): Set<String> {
        val set = mutableSetOf(
            "com.android.settings", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.vending", "com.samsung.android.app.settings.bixby",
        )
        runCatching {
            pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName?.let(set::add)
        }
        return set
    }

    fun launchers(context: Context): List<String> =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }

    data class AppEntry(val packageName: String, val label: String, val icon: Drawable?)

    /** Apps with a launcher icon that someone could choose to keep, minus the ones that are always fine. */
    fun launchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val always = alwaysAllowed(context)
        val never = neverAllowed(pm)
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName !in always && it.packageName !in never }
            .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString(), runCatching { pm.getApplicationIcon(it) }.getOrNull()) }
            .sortedBy { it.label.lowercase() }
    }

    /** Sensible essentials to start with: maps, clock, calculator, camera, Tailscale. */
    fun suggestedEssentials(context: Context): Set<String> {
        val candidates = setOf(
            "com.google.android.apps.maps", "com.waze",
            "com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock",
            "com.google.android.calculator", "com.sec.android.app.popupcalculator",
            "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.android.camera2",
            "com.tailscale.ipn",
        )
        val installed = launchableApps(context).map { it.packageName }.toSet()
        return candidates.intersect(installed)
    }

    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun isSystemApp(info: ApplicationInfo) = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
}

/** Uninstall protection. While this is on, the app can't be removed without first switching it off in Settings (which Brick blocks). */
class BrickAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Switching this off lets the planner be uninstalled. The family will be told."

    override fun onDisabled(context: Context, intent: Intent) {
        BrickEngine.init(context)
        BrickEngine.pushStatus(context)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        BrickEngine.init(context)
        BrickEngine.pushStatus(context)
    }
}
