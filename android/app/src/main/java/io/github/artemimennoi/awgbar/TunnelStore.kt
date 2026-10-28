package io.github.artemimennoi.awgbar

import android.content.Context
import java.io.File

/** Saved tunnels: one sanitized .conf per tunnel in app-private storage, plus small preferences. */
class TunnelStore(context: Context) {
    private val dir = File(context.filesDir, "tunnels").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("awgbar", Context.MODE_PRIVATE)

    fun names(): List<String> =
        dir.listFiles { f -> f.name.endsWith(".conf") }.orEmpty().map { it.name.removeSuffix(".conf") }.sortedBy { it.lowercase() }

    fun read(name: String): TunnelConfig = TunnelConfig.parse(File(dir, "$name.conf").readText())

    var selected: String?
        get() = prefs.getString("selected", null)?.takeIf { it in names() } ?: names().firstOrNull()
        set(value) = prefs.edit().putString("selected", value).apply()

    var bypass: String
        get() = prefs.getString("bypass", "") ?: ""
        set(value) = prefs.edit().putString("bypass", value).apply()

    /** Imports every config in [text]; a tunnel with the same keys is replaced in place. Returns the added names. */
    fun import(text: String, suggestedName: String) = save(KeyImport.parse(text, suggestedName))

    fun importQr(data: ByteArray) = save(KeyImport.parseQr(data, "awg"))

    private fun save(tunnels: List<ImportedTunnel>): List<String> {
        val added = mutableListOf<String>()
        for (t in tunnels) {
            val cfg = TunnelConfig.parse(t.config)
            val existing = names().firstOrNull { runCatching { read(it).identity }.getOrNull() == cfg.identity }
            val name = existing ?: KeyImport.tunnelName(t.name, names().toSet())
            File(dir, "$name.conf").writeText(cfg.render())
            added += name
        }
        added.firstOrNull()?.let { selected = it }
        return added
    }

    fun rename(old: String, new: String): Boolean {
        if (!KeyImport.isValidName(new) || new in names()) return false
        if (!File(dir, "$old.conf").renameTo(File(dir, "$new.conf"))) return false
        if (prefs.getString("selected", null) == old) selected = new
        return true
    }

    fun delete(name: String) {
        File(dir, "$name.conf").delete()
    }
}
