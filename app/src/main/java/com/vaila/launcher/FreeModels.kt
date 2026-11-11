package com.vaila.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ln
import kotlin.math.max

/**
 * Finds free models that can use tools and ranks them. Source: the public OpenRouter
 * model list (no key needed to read it). The ranking is a heuristic: bigger, newer and
 * longer-context models first.
 */
object FreeModels {
    const val OPENROUTER_BASE = "https://openrouter.ai/api/v1"
    private const val CACHE_MS = 12 * 3600 * 1000L

    data class Free(val id: String, val name: String, val context: Int, val vision: Boolean, val score: Double)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)

    /** Cached list (refreshed every 12 h or on [force]). */
    fun openRouter(ctx: Context, force: Boolean = false): List<Free> {
        val p = prefs(ctx)
        val cached = p.getString("or_cache", null)
        val at = p.getLong("or_cache_at", 0)
        if (!force && cached != null && System.currentTimeMillis() - at < CACHE_MS) {
            return runCatching { parse(JSONArray(cached)) }.getOrDefault(emptyList())
        }
        val c = URL("$OPENROUTER_BASE/models").openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "PocketShop/1.0")
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IOException("OpenRouter HTTP $code")
        val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
        // Keep only what we need, so the cache stays small.
        val slim = JSONArray()
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            val pr = m.optJSONObject("pricing") ?: continue
            if (!isZero(pr.optString("prompt")) || !isZero(pr.optString("completion"))) continue
            val params = m.optJSONArray("supported_parameters")?.toString().orEmpty()
            if ("\"tools\"" !in params) continue
            val mods = m.optJSONObject("architecture")?.optJSONArray("input_modalities")?.toString().orEmpty()
            slim.put(JSONObject()
                .put("id", m.optString("id"))
                .put("name", m.optString("name"))
                .put("context", m.optInt("context_length"))
                .put("created", m.optLong("created"))
                .put("vision", "image" in mods))
        }
        p.edit().putString("or_cache", slim.toString()).putLong("or_cache_at", System.currentTimeMillis()).apply()
        return parse(slim)
    }

    private fun isZero(s: String) = s.toDoubleOrNull()?.let { it == 0.0 } ?: false

    private fun parse(a: JSONArray): List<Free> {
        val out = mutableListOf<Free>()
        for (i in 0 until a.length()) {
            val m = a.getJSONObject(i)
            val id = m.getString("id")
            val ctx = m.optInt("context")
            out += Free(id, m.optString("name", id), ctx, m.optBoolean("vision"), score(id, m.optString("name"), ctx, m.optLong("created")))
        }
        return out.sortedByDescending { it.score }
    }

    /** Heuristic: parameter count (from the name), recency and context length. */
    private fun score(id: String, name: String, ctx: Int, created: Long): Double {
        val b = Regex("(\\d+(?:\\.\\d+)?)\\s*[bB](?![a-zA-Z])").findAll("$id $name")
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull() ?: 30.0
        val years = if (created > 0) (created - 1_704_067_200L) / 31_536_000.0 else 0.0 // since 2024-01-01
        var s = ln(b + 1) + 1.5 * years + 0.5 * ln(max(ctx, 8192) / 8192.0)
        if (Regex("(?i)\\b(1b|3b|mini|nano|tiny|small)\\b").containsMatchIn("$id $name")) s -= 1.0
        return s
    }

    /** Best model, honouring a pinned choice; [needVision] prefers image-capable ones. */
    fun best(ctx: Context, needVision: Boolean): Free? {
        val list = runCatching { openRouter(ctx) }.getOrDefault(emptyList())
        val pinned = prefs(ctx).getString("ormodel", "").orEmpty()
        if (pinned.isNotEmpty()) list.firstOrNull { it.id == pinned }?.let { return it }
        if (needVision) list.firstOrNull { it.vision }?.let { return it }
        return list.firstOrNull()
    }

    /** Next-best models after [skip], for fail-over inside OpenRouter. */
    fun ranked(ctx: Context, needVision: Boolean): List<Free> {
        val list = runCatching { openRouter(ctx) }.getOrDefault(emptyList())
        val pinned = prefs(ctx).getString("ormodel", "").orEmpty()
        val head = list.filter { it.id == pinned }
        val rest = list.filter { it.id != pinned }.let { l -> if (needVision) l.sortedByDescending { it.vision } else l }
        return head + rest
    }
}
