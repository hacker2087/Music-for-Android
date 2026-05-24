package com.example.data

import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun queryGemini(prompt: String, customKey: String? = null, systemPrompt: String? = null): String {
        val activeKey = if (!customKey.isNullOrBlank()) customKey else BuildConfig.GEMINI_API_KEY
        if (activeKey.isBlank() || activeKey == "MY_GEMINI_API_KEY") {
            return "Debes ingresar tu API Key de Gemini en configuración para activar las funciones de IA."
        }

        val requestUrl = "$BASE_URL?key=$activeKey"

        val root = JSONObject()
        val contentsArray = JSONArray()
        val contentObj = JSONObject()
        val partsArray = JSONArray()
        val partObj = JSONObject()
        partObj.put("text", prompt)
        partsArray.put(partObj)
        contentObj.put("parts", partsArray)
        contentsArray.put(contentObj)
        root.put("contents", contentsArray)

        if (!systemPrompt.isNullOrBlank()) {
            val systemObj = JSONObject()
            val sysPartsArray = JSONArray()
            val sysPartObj = JSONObject()
            sysPartObj.put("text", systemPrompt)
            sysPartsArray.put(sysPartObj)
            systemObj.put("parts", sysPartsArray)
            root.put("systemInstruction", systemObj)
        }

        val bodyText = root.toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = bodyText.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(requestUrl)
            .post(requestBody)
            .build()

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val respBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        return@withContext "Error del servidor de IA (${response.code}): $respBody"
                    }

                    val rootResp = JSONObject(respBody)
                    val candidates = rootResp.getJSONArray("candidates")
                    val candidate = candidates.getJSONObject(0)
                    val contentResp = candidate.getJSONObject("content")
                    val partsResp = contentResp.getJSONArray("parts")
                    val partResp = partsResp.getJSONObject(0)
                    partResp.getString("text")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                "Error de conexión con la IA de Gemini: ${e.localizedMessage ?: e.message}"
            }
        }
    }
}
