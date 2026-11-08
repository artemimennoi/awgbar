package io.github.artemimennoi.awgbar

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.text.Collator

/** An app the user can send around the tunnel. */
data class AppEntry(val packageName: String, val label: String)

/**
 * Apps for Bypass: the ones with a launcher icon, which the manifest's `<queries>` makes visible
 * without QUERY_ALL_PACKAGES. VpnService can only leave out packages it can see.
 */
object Apps {
    /** App names in the phone language's order, as the launcher sorts them. */
    val byName: Comparator<String> get() = Collator.getInstance().apply { strength = Collator.SECONDARY }.let { c -> Comparator { a, b -> c.compare(a, b) } }

    fun launchable(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(launcher, 0)
        }
        return found.map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()
            .map { AppEntry(it, label(context, it)) }
            .sortedWith(compareBy(byName) { it.label })
    }

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getApplicationInfo(packageName, 0)
    }.isSuccess

    /** The app's name as the launcher shows it, or the package name once it is gone. */
    fun label(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}
