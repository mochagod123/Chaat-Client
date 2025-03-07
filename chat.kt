package com.kenkenkenji.chaatclient

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.regex.Pattern
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class Chat() {
    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val client = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        })
        .build()
    private val tokenPattern = Pattern.compile("[a-zA-Z0-9]{32}")
    private var token: String? = null

    fun login(callback: (String) -> Unit) {
        val request = Request.Builder().url("https://c.kuku.lu/").get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback("Error")
            }
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    val matcher = tokenPattern.matcher(body)
                    if (matcher.find()) {
                        token = matcher.group()
                        println("Login成功: $token")
                        callback(token.toString())
                    } else {
                        callback(token.toString())
                    }
                } ?: callback(token.toString())
            }
        })
    }

    fun createRoom(callback: (JSONObject?) -> Unit) {
        val requestBody = FormBody.Builder()
            .add("action", "createRoom")
            .add("csrf_token_check", token ?: "")
            .build()

        val request = Request.Builder()
            .url("https://c.kuku.lu/api_server.php")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null) // エラー時は null を返す
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    try {
                        val json = JSONObject(responseBody) // JSON に変換
                        callback(json) // JSON オブジェクトを返す
                    } catch (e: Exception) {
                        callback(null) // JSON パースに失敗した場合
                    }
                } else {
                    callback(null) // レスポンスが空だった場合
                }
            }
        })
    }

    fun sendRoom(text: String, hash: String?, callback: (String) -> Unit) {
        val targetHash = hash ?: return callback("Error: ハッシュIDが設定されていません")

        println("Token: $token")
        println("Target Hash: $targetHash")

        val safeToken = token?.takeIf { it.isNotEmpty() } ?: return callback("Error: Tokenが空です")

        val formBody = FormBody.Builder()
            .add("action", "sendData")
            .add("hash", targetHash)
            .add("profile_name", "匿名とむ")
            .add("profile_color", "#000000")
            .add("data", """{"type":"chat","msg":"$text"}""") // JSONデータを文字列として扱う
            .add("csrf_token_check", safeToken)
            .build()

        val request = Request.Builder()
            .url("https://c.kuku.lu/room.php")
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Origin", "https://c.kuku.lu")
            .header("Referer", "https://c.kuku.lu/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback("Error: ネットワークエラー - ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback("Error: サーバーエラー - HTTP ${it.code}")
                        return
                    }
                    val responseBody = it.body?.string() ?: "Error: Empty Response"
                    println("Response: $responseBody") // デバッグ用に出力
                    callback(responseBody)
                }
            }
        })
    }

    private fun generateCurrentTimestamp(): String {
        val calendar = Calendar.getInstance()
        return String.format(
            "%04d%02d%02d%02d%02d%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND)
        )
    }

    fun fetchRoom(hash: String?, callback: (List<String>) -> Unit) {
        val targetHash = hash ?: return callback(emptyList())

        println(token)

        val requestBody = FormBody.Builder()
            .add("action", "fetchData")
            .add("hash", targetHash)
            .add("csrf_token_check", token ?: "")
            .add("mode", "log")
            .add("type", "last")
            .add("num", generateCurrentTimestamp())
            .build()

        val request = Request.Builder()
            .url("https://c.kuku.lu/room.php")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(emptyList()) // エラー時は空リストを返す
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    try {
                        val json = JSONObject(responseBody)
                        val dataList = json.optJSONObject("data_list")
                        val msgList = mutableListOf<String>()

                        dataList?.keys()?.forEach { key ->
                            val msg = dataList.optJSONObject(key)
                                ?.optJSONObject("data")
                                ?.optString("msg", null)
                            val author = dataList.optJSONObject(key)
                                ?.optJSONObject("data")
                                ?.optString("name", null)
                            if (msg != null) {
                                msgList.add("$author: $msg")
                            }
                        }

                        callback(msgList) // すべての "msg" を返す
                    } catch (e: Exception) {
                        callback(emptyList()) // JSON パース失敗時は空リスト
                    }
                } else {
                    callback(emptyList()) // レスポンスが空だった場合
                }
            }
        })
    }

}
