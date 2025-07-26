package com.deeplinkly.flutter_deeplinkly

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.TimeZone
import androidx.annotation.NonNull

import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse

class FlutterDeeplinklyPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {

    private lateinit var channel: MethodChannel
    private var activity: Activity? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Log.d("Deeplinkly", "✅ onAttachedToEngine called")
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "deeplinkly/channel")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "getPlatformVersion") {
            result.success("Android ${android.os.Build.VERSION.RELEASE}")
        } else {
            result.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Log.d("Deeplinkly", "✅ onAttachedToActivity called")
        activity = binding.activity

        handleIntent(activity?.intent)

        binding.addOnNewIntentListener {
            Log.d("Deeplinkly", "🔁 onNewIntent received")
            handleIntent(it)
            true
        }
        try {
            val context = binding.activity.applicationContext
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
            val apiKey = appInfo.metaData.getString("com.deeplinkly.sdk.api_key") ?: return
            checkInstallReferrer(context, apiKey)
        } catch (e: Exception) {
            Log.e("Deeplinkly", "❌ Failed to check referrer: ${e.message}")
        }

    }

    private fun handleIntent(intent: Intent?) {
        try {
            Log.d("Deeplinkly", "📨 handleIntent called with: $intent")

            val data: Uri? = intent?.data ?: return
            Log.d("Deeplinkly", "Data: $data")

            val context = activity ?: return
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
            val apiKey = appInfo.metaData.getString("com.deeplinkly.sdk.api_key") ?: return

            val clickId = data?.getQueryParameter("click_id")
            val code = data?.pathSegments?.firstOrNull()


            if (clickId == null && code == null) {
                Log.d("Deeplinkly", "❌ No click_id or code found in intent URI")
                return
            }

            val enrichmentData = try {
                collectEnrichmentData(context)
            } catch (e: Exception) {
                reportError(apiKey, "collectEnrichmentData failed", e.stackTraceToString(), clickId)
                emptyMap<String, String?>()
            }.toMutableMap()

            if (clickId != null) enrichmentData["click_id"] = clickId
            if (clickId == null && code != null) enrichmentData["code"] = code
            enrichmentData["android_reported_at"] = try {
                Instant.now().toString()
            } catch (_: Exception) {
                System.currentTimeMillis().toString()
            }

            val finalUrl = if (clickId != null) {
                "https://sahilasopa.pagekite.me/api/v1/resolve-click?click_id=$clickId"
            } else {
                "https://sahilasopa.pagekite.me/api/v1/resolve-click?code=$code"
            }

            // 🔄 Async request to resolve-click + enrichment
            Thread {
                try {
                    val conn = URL(finalUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    conn.setRequestProperty("Accept", "application/json")

                    val responseCode = conn.responseCode
                    if (responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().readText()
                        val json = JSONObject(response)
                        val params = json.optJSONObject("params") ?: JSONObject()

                        val dartMap = HashMap<String, Any?>()
                        dartMap["click_id"] = clickId ?: json.optString("click_id", null)
                        params.keys().forEach { key -> dartMap[key] = params.get(key) }
                        val resolvedClickId = clickId ?: json.optString("click_id", null)
                        if (!resolvedClickId.isNullOrBlank()) {
                            enrichmentData["click_id"] = resolvedClickId
                        }

                        activity?.runOnUiThread {
                            channel.invokeMethod("onDeepLink", dartMap)
                        }

                        sendEnrichment(enrichmentData, apiKey)

                    } else {
                        reportError(apiKey, "resolve failed with code $responseCode", "resolve: $finalUrl", clickId)
                    }
                } catch (e: Exception) {
                    reportError(apiKey, "resolve exception", e.stackTraceToString(), clickId)
                }
            }.start()

        } catch (e: Exception) {
            try {
                val apiKey = activity?.packageManager?.getApplicationInfo(
                    activity!!.packageName,
                    android.content.pm.PackageManager.GET_META_DATA
                )?.metaData?.getString("com.deeplinkly.sdk.api_key") ?: return
                reportError(apiKey, "handleIntent outer crash", e.stackTraceToString())
            } catch (_: Exception) {
            }
        }
    }


    private fun collectEnrichmentData(context: Context): Map<String, String?> {
        val pm = context.packageManager
        val pkg = context.packageName
        val appInfo = pm.getApplicationInfo(pkg, android.content.pm.PackageManager.GET_META_DATA)
        val versionInfo = pm.getPackageInfo(pkg, 0)

        return mapOf(
            "android_id" to android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID),
            "manufacturer" to android.os.Build.MANUFACTURER,
            "brand" to android.os.Build.BRAND,
            "device" to android.os.Build.DEVICE,
            "product" to android.os.Build.PRODUCT,
            "sdk_int" to android.os.Build.VERSION.SDK_INT.toString(),
            "os_release" to android.os.Build.VERSION.RELEASE,
            "installer_package" to pm.getInstallerPackageName(pkg),
            "device_model" to android.os.Build.MODEL,
            "app_version" to versionInfo.versionName,
            "app_build_number" to versionInfo.versionCode.toString(),
            "locale" to context.resources.configuration.locales[0].toString(),
            "region" to context.resources.configuration.locales[0].country,
            "timezone" to java.util.TimeZone.getDefault().id
        )
    }

    private fun reportError(apiKey: String, message: String, stack: String, clickId: String? = null) {
        Thread {
            try {
                val url = URL("https://sahilasopa.pagekite.me/api/v1/sdk-error")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true

                val payload = JSONObject().apply {
                    put("message", message)
                    put("stack", stack)
                    if (clickId != null) put("click_id", clickId)
                }

                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.responseCode // force send
            } catch (_: Exception) {
                // swallow error
            }
        }.start()
    }


    private fun sendEnrichment(data: Map<String, String?>, apiKey: String) {
        Thread {
            try {
                val url = URL("https://sahilasopa.pagekite.me/api/v1/enrich")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true

                val json = JSONObject(data.filterValues { it != null })
                conn.outputStream.use { it.write(json.toString().toByteArray()) }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    Log.d("Deeplinkly", "📤 Enrichment sent successfully")
                } else {
                    Log.e("Deeplinkly", "❌ Enrichment failed with code $responseCode")
                }
            } catch (e: Exception) {
                Log.e("Deeplinkly", "❌ Exception during enrichment: ${e.message}", e)
            }
        }.start()
    }


    override fun onDetachedFromActivity() {
        Log.d("Deeplinkly", "❌ onDetachedFromActivity called")
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        Log.d("Deeplinkly", "🔁 onReattachedToActivityForConfigChanges called")
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        Log.d("Deeplinkly", "❌ onDetachedFromActivityForConfigChanges called")
        activity = null
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d("Deeplinkly", "❌ onDetachedFromEngine called")
        channel.setMethodCallHandler(null)
    }


    private fun checkInstallReferrer(context: Context, apiKey: String) {
        Log.d("Deeplinkly", "🔍 Starting checkInstallReferrer()")
        try {
            val referrerClient = InstallReferrerClient.newBuilder(context).build()
            referrerClient.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    Log.d("Deeplinkly", "📡 onInstallReferrerSetupFinished(code=$responseCode)")
                    when (responseCode) {
                        InstallReferrerResponse.OK -> {
                            val response = referrerClient.installReferrer
                            val rawReferrer = response.installReferrer
                            Log.d("Deeplinkly", "🎯 Raw installReferrer=\"$rawReferrer\"")

                            val clickId = Uri.parse("https://dummy?$rawReferrer")
                                .getQueryParameter("click_id")
                            Log.d("Deeplinkly", "   → parsed click_id=$clickId")

                            if (clickId.isNullOrEmpty()) {
                                Log.w("Deeplinkly", "   → click_id missing, skipping deferred resolve")
                            } else {
                                // Build enrichment payload
                                val enrichmentData = try {
                                    collectEnrichmentData(context)
                                } catch (e: Exception) {
                                    reportError(apiKey, "collectEnrichmentData failed", e.stackTraceToString(), clickId)
                                    emptyMap<String, String?>()
                                }.toMutableMap().apply {
                                    put("click_id", clickId)
                                    put("install_referrer", rawReferrer)
                                    put(
                                        "android_reported_at",
                                        try {
                                            Instant.now().toString()
                                        } catch (_: Exception) {
                                            System.currentTimeMillis().toString()
                                        }
                                    )
                                }

                                // Resolve and send enrichment in background
                                Thread {
                                    val urlString = "https://sahilasopa.pagekite.me/api/v1/resolve?click_id=$clickId"
                                    Log.d("Deeplinkly", "🌐 Calling resolve URL=$urlString")
                                    try {
                                        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                                            setRequestProperty("Authorization", "Bearer $apiKey")
                                            setRequestProperty("Accept", "application/json")
                                        }
                                        val code = conn.responseCode
                                        Log.d("Deeplinkly", "   ← resolve returned code=$code")

                                        if (code == HttpURLConnection.HTTP_OK) {
                                            val body = conn.inputStream.bufferedReader().readText()
                                            Log.d("Deeplinkly", "   ← resolve body=$body")

                                            val json = JSONObject(body)
                                            val params = json.optJSONObject("params") ?: JSONObject()
                                            val dartMap = HashMap<String, Any?>().apply {
                                                put("click_id", clickId)
                                                params.keys().forEach { key -> put(key, params.get(key)) }
                                            }

                                            activity?.runOnUiThread {
                                                Log.d("Deeplinkly", "👉 invoking Dart onDeepLink with $dartMap")
                                                channel.invokeMethod("onDeepLink", dartMap)
                                            }

                                            sendEnrichment(enrichmentData, apiKey)
                                        } else {
                                            Log.e("Deeplinkly", "❌ resolve failed with code $code")
                                            reportError(apiKey, "resolve failed", "code=$code", clickId)
                                        }
                                    } catch (e: Exception) {
                                        Log.e("Deeplinkly", "❌ exception in resolve thread", e)
                                        reportError(apiKey, "resolve exception", e.stackTraceToString(), clickId)
                                    }
                                }.start()
                            }
                        }

                        InstallReferrerResponse.FEATURE_NOT_SUPPORTED ->
                            Log.w("Deeplinkly", "⚠️ FEATURE_NOT_SUPPORTED")

                        InstallReferrerResponse.SERVICE_UNAVAILABLE ->
                            Log.w("Deeplinkly", "⚠️ SERVICE_UNAVAILABLE")

                        InstallReferrerResponse.DEVELOPER_ERROR ->
                            Log.e("Deeplinkly", "❌ DEVELOPER_ERROR – check manifest & dependency")

                        else ->
                            Log.w("Deeplinkly", "❓ Unknown InstallReferrerResponse code $responseCode")
                    }

                    try {
                        referrerClient.endConnection()
                        Log.d("Deeplinkly", "🔒 checkInstallReferrer: connection closed")
                    } catch (e: Exception) {
                        Log.e("Deeplinkly", "❌ error closing referrerClient", e)
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    Log.w("Deeplinkly", "⚠️ onInstallReferrerServiceDisconnected()")
                }
            })
        } catch (e: Exception) {
            Log.e("Deeplinkly", "❌ Exception in checkInstallReferrer()", e)
            reportError(apiKey, "installReferrer error", e.stackTraceToString())
        }
    }

}
