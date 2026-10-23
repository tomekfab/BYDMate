package com.bydmate.app.agent

import com.bydmate.app.diagnostics.Trace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Open-Meteo client for the get_weather tool. No API key required for either endpoint.
 */
@Singleton
class WeatherClient @Inject constructor(private val http: OkHttpClient) {

    /** Failure whose message is user-facing Polish text, safe to surface in tool error JSON.
     *  Any other Throwable (HTTP codes, parse errors, timeouts) carries an English message and
     *  must be collapsed to a generic Polish error by the caller. */
    class UserError(message: String) : IOException(message)

    /** A non-2xx answer; [code] is safe to log, unlike the URL with the coordinates. */
    class HttpError(val code: Int) : IOException("HTTP $code")

    /** A geocoded location: coordinates plus the resolved place name, voiced back by the tool. */
    data class GeoPoint(val lat: Double, val lon: Double, val name: String)

    /** Current conditions + 3-day forecast, compacted into the JSON the LLM tool returns. */
    suspend fun forecast(lat: Double, lon: Double): Result<String> = withContext(Dispatchers.IO) {
        val startMs = System.nanoTime() / NANOS_PER_MS
        runCatching {
            val url = FORECAST_URL.toHttpUrl().newBuilder()
                .addQueryParameter("latitude", lat.toString())
                .addQueryParameter("longitude", lon.toString())
                .addQueryParameter("current",
                    "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m")
                .addQueryParameter("daily",
                    "temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max")
                .addQueryParameter("timezone", "auto")
                .addQueryParameter("forecast_days", "3")
                // Open-Meteo defaults to km/h; the tool JSON field is wind_ms.
                .addQueryParameter("wind_speed_unit", "ms")
                .build()
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpError(resp.code)
                val body = resp.body?.string().takeUnless { it.isNullOrBlank() }
                    ?: throw IOException("empty body")
                val json = JSONObject(body)
                val current = json.getJSONObject("current")
                val now = JSONObject().apply {
                    put("temp_c", current.getDouble("temperature_2m"))
                    put("feels_c", current.getDouble("apparent_temperature"))
                    put("text", wmoToPolish(current.getInt("weather_code")))
                    put("wind_ms", current.getDouble("wind_speed_10m"))
                    put("humidity", current.getInt("relative_humidity_2m"))
                }
                val daily = json.getJSONObject("daily")
                val dates = daily.getJSONArray("time")
                val maxT = daily.getJSONArray("temperature_2m_max")
                val minT = daily.getJSONArray("temperature_2m_min")
                val codes = daily.getJSONArray("weather_code")
                val precip = daily.getJSONArray("precipitation_probability_max")
                val days = JSONArray().apply {
                    for (i in 0 until dates.length()) {
                        put(JSONObject().apply {
                            put("date", dates.getString(i))
                            put("min_c", minT.getDouble(i))
                            put("max_c", maxT.getDouble(i))
                            put("text", wmoToPolish(codes.getInt(i)))
                            put("precip_prob", precip.getInt(i))
                        })
                    }
                }
                JSONObject().put("now", now).put("days", days).toString()
            }
        }.also { traced(FORECAST_URL, startMs, it) }
    }

    /** Open-Meteo geocoding: first match for a free-text city name. */
    suspend fun geocode(city: String): Result<GeoPoint> = withContext(Dispatchers.IO) {
        val startMs = System.nanoTime() / NANOS_PER_MS
        runCatching {
            val url = GEOCODE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("name", city)
                .addQueryParameter("count", "1")
                .addQueryParameter("language", "pl")
                .build()
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpError(resp.code)
                val body = resp.body?.string().takeUnless { it.isNullOrBlank() }
                    ?: throw IOException("empty body")
                val results = JSONObject(body).optJSONArray("results")
                if (results == null || results.length() == 0) throw UserError("nie znaleziono miasta")
                val r = results.getJSONObject(0)
                // admin1 is intentionally dropped: the short name reads best over TTS.
                GeoPoint(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name"))
            }
        }.also { traced(GEOCODE_URL, startMs, it) }
    }

    /** The trace event of one call; "nie znaleziono miasta" is an answer, not a server failure. */
    private suspend fun traced(url: String, startMs: Long, result: Result<*>) {
        val error = result.exceptionOrNull()?.takeUnless { it is UserError }
        Trace.call("weather", url.toHttpUrl().host, System.nanoTime() / NANOS_PER_MS - startMs, error)
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"

        private val WMO_PL = mapOf(
            0 to "bezchmurnie",
            1 to "lekko zachmurzone",
            2 to "częściowe zachmurzenie",
            3 to "pochmurno",
            45 to "mgła",
            48 to "mgła osadzająca szadź",
            51 to "lekka mżawka",
            53 to "umiarkowana mżawka",
            55 to "silna mżawka",
            56 to "lekka marznąca mżawka",
            57 to "silna marznąca mżawka",
            61 to "nieduży deszcz",
            63 to "umiarkowany deszcz",
            65 to "silny deszcz",
            66 to "lekki marznący deszcz",
            67 to "silny marznący deszcz",
            71 to "nieduży śnieg",
            73 to "umiarkowany śnieg",
            75 to "silny śnieg",
            77 to "ziarna śnieżne",
            80 to "lekkie ulewy",
            81 to "umiarkowane ulewy",
            82 to "silne ulewy",
            85 to "nieduży śnieżyca",
            86 to "silny śnieżyca",
            95 to "burza",
            96 to "burza z lekkim gradem",
            99 to "burza z silnym gradem",
        )

        /** [code] is the Open-Meteo WMO weather_code. Unknown codes fall back to a generic label. */
        fun wmoToPolish(code: Int): String = WMO_PL[code] ?: "nieznana pogoda"
    }
}
