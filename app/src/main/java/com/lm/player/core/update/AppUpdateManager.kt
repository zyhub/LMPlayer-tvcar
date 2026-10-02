package com.lm.player.core.update

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.lm.player.core.network.NetworkClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val latestVersionCode: Int,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSizeBytes: Long = 0L,
    val isForceUpdate: Boolean = false
)

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    const val CURRENT_VERSION_NAME = "1.10.7"
    const val CURRENT_VERSION_CODE = 54
    const val AUTHOR_NAME = "Zhou"
    const val AUTHOR_EMAIL = "1390999045@qq.com"
    const val APP_DESCRIPTION = "本软件为 TV车机版，专为智能电视、机顶盒与车载中控横屏大屏量身打造的高保真无损音乐播放器。专属接入柠檬音乐服务端，支持5大音源全网融合搜索与无损畅听、全盘本地音频深度扫描、智能歌词联动以及电视遥控器与车机触控双栖深度适配。"

    // 默认的 GitHub 仓库全路径 (格式: "用户名/仓库名")
    const val DEFAULT_GITHUB_REPO = "zyhub/LMPlayer-tvcar"

    /**
     * 动态获取当前应用安装版本号名称
     */
    fun getAppVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName?.ifBlank { null } ?: CURRENT_VERSION_NAME
        } catch (_: Exception) {
            CURRENT_VERSION_NAME
        }
    }

    /**
     * 动态获取当前应用安装版本代码 (VersionCode)
     */
    fun getAppVersionCode(context: Context): Long {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (_: Exception) {
            CURRENT_VERSION_CODE.toLong()
        }
    }

    /**
     * 获取更新偏好配置
     */
    fun getUpdatePrefs(context: Context): android.content.SharedPreferences {
        return context.getSharedPreferences("zds_update_prefs", Context.MODE_PRIVATE)
    }

    /**
     * 检查当前版本或全局是否已被用户设置为「永不更新」
     */
    fun isUpdateIgnored(context: Context, version: String): Boolean {
        val prefs = getUpdatePrefs(context)
        if (prefs.getBoolean("never_update", false)) return true
        val skipVersion = prefs.getString("skip_version", "")
        return skipVersion == version
    }

    /**
     * 设置全局永不自动更新
     */
    fun setNeverUpdate(context: Context, never: Boolean) {
        getUpdatePrefs(context).edit().putBoolean("never_update", never).apply()
    }

    /**
     * 忽略特定版本的自动弹窗更新
     */
    fun setSkipVersion(context: Context, version: String) {
        getUpdatePrefs(context).edit().putString("skip_version", version).apply()
    }

    /**
     * 重置忽略状态 (用于在设置中心手动检查时重新唤醒)
     */
    fun resetUpdateIgnore(context: Context) {
        getUpdatePrefs(context).edit().clear().apply()
    }

    /**
     * 检查新版本 (无缝原生支持 GitHub Releases API 与自定义接口)
     */
    suspend fun checkForUpdates(context: Context, customUrlOrRepo: String? = null): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val currentVersionName = getAppVersionName(context)
            val currentVersionCode = getAppVersionCode(context)
            val targetTarget = customUrlOrRepo?.ifBlank { null } ?: DEFAULT_GITHUB_REPO

            if (targetTarget.isNotBlank()) {
                val apiUrl = if (targetTarget.startsWith("http://") || targetTarget.startsWith("https://")) {
                    targetTarget
                } else {
                    "https://api.github.com/repos/${targetTarget.trim()}/releases/latest"
                }

                val client = NetworkClientFactory.createOkHttpClient(context)
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "LMPlayerTV-App")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)

                        // 1. 解析 GitHub Releases 标准 JSON (仅匹配含 tv 标识的 TV车机版 专属安装包，避免误下载手机版 APK)
                        if (json.has("tag_name") && json.has("assets")) {
                            val tagName = json.optString("tag_name", "").trim()
                            val cleanVersion = tagName.trimStart('v', 'V')
                            val notes = json.optString("body", "性能优化与功能增强").ifBlank { "版本更新 $tagName" }
                            val assets = json.optJSONArray("assets")
                            var apkDownloadUrl = ""
                            var apkSize = 0L

                            if (assets != null && assets.length() > 0) {
                                for (i in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(i)
                                    val name = asset.optString("name", "").lowercase()
                                    if (name.endsWith(".apk") && name.contains("tv")) {
                                        apkDownloadUrl = asset.optString("browser_download_url", "")
                                        apkSize = asset.optLong("size", 0L)
                                        break
                                    }
                                }
                            }

                            val hasUpdate = apkDownloadUrl.isNotBlank() && isVersionNewer(cleanVersion, currentVersionName)
                            return@withContext Result.success(
                                UpdateInfo(
                                    hasUpdate = hasUpdate,
                                    latestVersion = cleanVersion,
                                    latestVersionCode = currentVersionCode.toInt() + (if (hasUpdate) 1 else 0),
                                    releaseNotes = notes,
                                    downloadUrl = apkDownloadUrl,
                                    apkSizeBytes = apkSize,
                                    isForceUpdate = false
                                )
                            )
                        } else {
                            // 2. 解析通用自定义 API JSON
                            val vCode = json.optInt("versionCode", currentVersionCode.toInt())
                            val vName = json.optString("versionName", currentVersionName)
                            val notes = json.optString("releaseNotes", "性能优化与功能增强")
                            val url = json.optString("downloadUrl", "")
                            val size = json.optLong("apkSizeBytes", 0L)
                            val force = json.optBoolean("isForceUpdate", false)

                            val hasUpdate = vCode.toLong() > currentVersionCode || isVersionNewer(vName, currentVersionName)
                            return@withContext Result.success(
                                UpdateInfo(
                                    hasUpdate = hasUpdate,
                                    latestVersion = vName,
                                    latestVersionCode = vCode,
                                    releaseNotes = notes,
                                    downloadUrl = url,
                                    apkSizeBytes = size,
                                    isForceUpdate = force
                                )
                            )
                        }
                    }
                }
            }

            // 默认返回当前版本状态
            Result.success(
                UpdateInfo(
                    hasUpdate = false,
                    latestVersion = currentVersionName,
                    latestVersionCode = currentVersionCode.toInt(),
                    releaseNotes = "当前已是最新 TV车机版 (v$currentVersionName)。\n\n" +
                        "• 修复首次启动平台选择向导用遥控器操作时光标「消失」：上下方向键不再把焦点交给身后被遮住的主界面\n" +
                        "• 向导光标固定在「电视」「车机」两个选项之间左右移动，选择更稳、不再迷路",
                    downloadUrl = ""
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check update", e)
            Result.failure(e)
        }
    }

    /**
     * 语义化版本号比较 (例如 "1.2.0" > "1.1.0")
     */
    fun isVersionNewer(latestVersion: String, currentVersion: String): Boolean {
        val lParts = latestVersion.trimStart('v', 'V').split('.').mapNotNull { it.toIntOrNull() }
        val cParts = currentVersion.trimStart('v', 'V').split('.').mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(lParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val l = lParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * 下载 APK 安装包并在进度回调中通知 UI
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.cacheDir, "updates")
            if (!baseDir.exists()) baseDir.mkdirs()
            val apkFile = File(baseDir, "LMPlayerTV_update.apk")
            if (apkFile.exists()) apkFile.delete()

            val client = NetworkClientFactory.createOkHttpClient(context)
            val request = Request.Builder().url(downloadUrl).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.message}")
                val body = response.body ?: throw Exception("Empty response body")
                val totalLength = body.contentLength()

                // 进度节流：8 KB 回调一次，一个 60 MB 的安装包就要往主线程投递 7000+ 次任务，
                // 每次只是一个进度条刷新，纯属拖慢下载；限制为「距上次上报 ≥150 ms 或进度推进 ≥1%」
                var lastReportTime = 0L
                var lastReportProgress = -1f

                // 两个流都用 use 收尾：异常路径下也要关掉，否则文件描述符泄漏
                body.byteStream().use { inputStream ->
                    FileOutputStream(apkFile).use { outputStream ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var totalBytesRead = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            val progress = if (totalLength > 0) totalBytesRead.toFloat() / totalLength else 0f
                            val now = System.currentTimeMillis()
                            if (now - lastReportTime >= 150L || progress - lastReportProgress >= 0.01f) {
                                lastReportTime = now
                                lastReportProgress = progress
                                withContext(Dispatchers.Main) {
                                    onProgress(progress, totalBytesRead, totalLength)
                                }
                            }
                        }
                        outputStream.flush()
                    }
                }

                // 收尾补报：节流会吃掉最后一次，进度条必须停在 100%
                withContext(Dispatchers.Main) {
                    onProgress(if (totalLength > 0) 1f else 0f, totalLength.coerceAtLeast(0L), totalLength)
                }
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download APK failed", e)
            Result.failure(e)
        }
    }

    /**
     * 电视端合规提示：规避直接调起 PackageInstaller 触发广电安全中心拦截
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(context, "安装包不存在，请重新下载", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            context,
            "安装包已保存至: ${apkFile.absolutePath}，请通过电视文件管理器或U盘助手安装",
            Toast.LENGTH_LONG
        ).show()
    }
}
