package com.example.deskpet.sync

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SupabaseSync {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Replace with your own Supabase project URL and anon key
    private val SUPABASE_URL = "https://your-project.supabase.co"
    private val SUPABASE_KEY = "your-anon-key"

    fun logGesture(type: String, x: Int, y: Int) {
        val body = JSONObject().apply {
            put("gesture_type", type)
            put("x", x)
            put("y", y)
        }
        postToSupabase("gesture_log", body)
    }

    fun logAppUsage(packageName: String) {
        val body = JSONObject().apply {
            put("package_name", packageName)
        }
        postToSupabase("app_usage", body)
    }

    private fun postToSupabase(table: String, body: JSONObject) {
        scope.launch {
            try {
                val url = URL("$SUPABASE_URL/rest/v1/$table")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", SUPABASE_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }
}