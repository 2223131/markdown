package com.example.myapplication.qwen

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

fun streamQwenResponse(prompt: String): Flow<String> = flow {
    val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val json = """
        {
          "model": "qwen-turbo",
          "input": {"prompt": "$prompt"},
          "parameters": {"temperature": 0.8, "top_p": 0.8},
          "stream": true
        }
    """.trimIndent()

    val request = Request.Builder()
        .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation")
        .addHeader("Authorization", "Bearer sk-90a12f0a4dac42659deb6400a87432cb")
        .addHeader("Accept", "text/event-stream")
        .post(json.toRequestBody("application/json".toMediaType()))
        .build()

    val response = client.newCall(request).execute()

    if (!response.isSuccessful) {
        val err = response.body?.string()
        throw Exception("请求失败 ${response.code}, 内容：$err")
    }

    val source = response.body?.source() ?: throw Exception("响应体为空")

    var lastText = ""  // 上一次完整内容
    while (!source.exhausted()) {
        val line = source.readUtf8Line() ?: continue
        if (line.startsWith("data:")) {
            val content = line.removePrefix("data:").trim()
            if (content == "[DONE]") break

            val fullText = try {
                JSONObject(content)
                    .optJSONObject("output")
                    ?.optString("text") ?: ""
            } catch (e: Exception) {
                ""
            }

            if (fullText.isNotBlank()) {

                val delta = fullText.removePrefix(lastText)  // 只拿新增内容
                lastText = fullText
                if (delta.isNotBlank()) {
                    emit(delta)
                    Log.d("data",delta)
                }
            }
        }
    }

    source.close()
    response.close()
}.flowOn(Dispatchers.IO)
