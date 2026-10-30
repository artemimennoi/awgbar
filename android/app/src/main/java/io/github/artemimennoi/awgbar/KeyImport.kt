package io.github.artemimennoi.awgbar

import java.text.Normalizer
import java.util.Base64
import java.util.zip.Inflater
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class ImportedTunnel(val name: String, val config: String)

/** User-facing import failure; [message] is a string resource id. */
class ImportError(val res: Int) : Exception()

/**
 * Accepts an AmneziaVPN key (vpn://...), AmneziaVPN JSON (a decoded key, a connection export
 * or a settings backup) or a plain AmneziaWG .conf text. Mirrors KeyImport.swift.
 */
object KeyImport {
    /** Upper bound for what an import reads or unpacks. */
    const val MAX_SIZE = 1 shl 20

    fun parse(text: String, suggestedName: String): List<ImportedTunnel> {
        val trimmed = text.trim()
        if (trimmed.startsWith("vpn://")) return configs(decodeVpnKey(trimmed), suggestedName)
        val looksLikeJson = trimmed.startsWith("{") || trimmed.startsWith("[")
        // A .conf starts with "[Interface]" too, so only a successful parse makes it JSON.
        if (looksLikeJson) parseJson(trimmed)?.let { return configs(it, suggestedName) }
        if (trimmed.contains("[Interface]", ignoreCase = true)) return listOf(ImportedTunnel(suggestedName, trimmed + "\n"))
        if (looksLikeJson) throw ImportError(R.string.err_bad_json)
        throw ImportError(R.string.err_unknown_format)
    }

    private fun parseJson(text: String): Any? = runCatching {
        val tokener = JSONTokener(text)
        val value = tokener.nextValue()
        if (tokener.more() || (value !is JSONObject && value !is JSONArray)) null else value
    }.getOrNull()

    /**
     * AmneziaVPN keeps the ready wg-quick text in `containers[].awg.last_config.config`, where
     * `last_config` is itself JSON encoded as a string. Walk the whole document, unwrap JSON found
     * in strings, and take every string that is a config. The title is the nearest server `description`.
     */
    fun configs(doc: Any, suggestedName: String): List<ImportedTunnel> {
        val result = mutableListOf<ImportedTunnel>()
        val seen = mutableSetOf<String>()
        var sawApiConfig = false

        fun walk(node: Any?, title: String?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("api_config") || node.has("auth_data")) sawApiConfig = true
                    val description = node.optString("description").ifEmpty { null }
                    val t = description ?: title ?: node.optString("hostName").ifEmpty { null }
                    node.keys().asSequence().sorted().forEach { walk(node.get(it), t) }
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.get(i), title)
                is String -> {
                    val text = node.trim()
                    // JSON first: an encoded server list contains "[Interface]" too.
                    val nested = if (text.startsWith("{") || text.startsWith("[")) parseJson(text) else null
                    if (nested != null) {
                        walk(nested, title)
                    } else if (text.contains("[Interface]", true) && text.contains("[Peer]", true) && seen.add(text)) {
                        result += ImportedTunnel(title ?: suggestedName, text + "\n")
                    }
                }
            }
        }

        walk(doc, null)
        if (result.isEmpty()) throw ImportError(if (sawApiConfig) R.string.err_premium_key else R.string.err_no_config)
        return result
    }

    /** vpn:// is base64url of Qt's qCompress output: a 4-byte big-endian length, then a zlib stream. */
    fun decodeVpnKey(key: String): Any {
        val b64 = key.removePrefix("vpn://").filterNot { it.isWhitespace() }.trimEnd('=')
        val raw = runCatching { Base64.getUrlDecoder().decode(b64) }.getOrNull()
            ?: runCatching { Base64.getDecoder().decode(b64.padEnd((b64.length + 3) / 4 * 4, '=')) }.getOrNull()
            ?: throw ImportError(R.string.err_not_base64)
        return unpack(raw) ?: throw ImportError(R.string.err_cannot_unpack)
    }

    /** JSON, possibly qCompress-ed, as found inside vpn:// keys and AmneziaVPN QR codes. */
    private fun unpack(raw: ByteArray): Any? {
        val json = if (raw.size > 6 && raw[4] == 0x78.toByte()) inflate(raw.copyOfRange(4, raw.size)) ?: raw else raw
        return parseJson(String(json, Charsets.UTF_8))
    }

    /** The payload of a scanned QR code or a whole AmneziaVPN QR series: text, JSON or qCompress-ed JSON. */
    fun parseQr(data: ByteArray, suggestedName: String): List<ImportedTunnel> {
        unpack(data)?.let { return configs(it, suggestedName) }
        return parse(String(data, Charsets.UTF_8), suggestedName)
    }

    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
            // A real key unpacks to a few KB, a crafted one to gigabytes.
            if (out.size() > MAX_SIZE) return@runCatching null
        }
        inflater.end()
        out.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Tunnel names follow awg-quick's interface rule, [a-zA-Z0-9_=+.-]{1,15}, like on macOS. */
    fun tunnelName(title: String, taken: Set<String>): String {
        val latin = Normalizer.normalize(Translit.toLatin(title), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        // Anything outside the charset becomes one "-": "Rezerv | #202 · NUE" → "Rezerv-202-NUE".
        val sb = StringBuilder()
        for (ch in latin) {
            if (ch.code < 128 && (ch.isLetterOrDigit() || ch in "_=+.")) sb.append(ch)
            else if (sb.isNotEmpty() && sb.last() != '-') sb.append('-')
        }
        var base = sb.toString().trimEnd('-').take(15).trimEnd('-').ifEmpty { "awg" }
        var name = base
        var n = 2
        while (name in taken) {
            val suffix = "-$n"
            name = base.take(15 - suffix.length) + suffix
            n++
        }
        return name
    }

    /** Any text is a fine title: control characters become spaces, and it is cut to 64 characters. */
    fun cleanTitle(raw: String): String? =
        raw.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(64).trim().ifEmpty { null }
}

/** Cyrillic to Latin, close to what macOS's toLatin transform gives for Russian and Kazakh. */
object Translit {
    private val map = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "j", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c",
        'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu",
        'я' to "ya", 'ә' to "a", 'ғ' to "g", 'қ' to "k", 'ң' to "n", 'ө' to "o", 'ұ' to "u", 'ү' to "u",
        'һ' to "h", 'і' to "i",
    )

    fun toLatin(s: String) = buildString {
        for (ch in s) {
            val t = map[ch.lowercaseChar()]
            if (t == null) append(ch)
            else append(if (ch.isUpperCase()) t.replaceFirstChar { it.uppercaseChar() } else t)
        }
    }
}
