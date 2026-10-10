package com.lm.player.core.update

import android.content.Context
import android.content.Intent
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
    val isForceUpdate: Boolean = false,
    /**
     * 服务端声明的安装包 SHA-256（小写十六进制）。
     * GitHub Releases 的 assets 不提供该字段，此时为 null，此时只做「大小 + 签名」双重校验；
     * 自定义更新接口若提供 `apkSha256` 字段，则会在此启用全量哈希比对。
     */
    val apkSha256: String? = null
)

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    const val CURRENT_VERSION_NAME = "1.10.24"
    const val CURRENT_VERSION_CODE = 71
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

                // 更新链路强制 HTTPS：更新接口允许 http 时，中间人可以把任意 APK 塞进更新弹窗
                // 并引导用户安装（安装包本身零校验的时代更是如此）。此处直接拒绝明文接口，
                // 只有确属局域网自建更新服务时才用 https 或改用带证书的方案。
                if (!apiUrl.startsWith("https://", ignoreCase = true)) {
                    Log.e(TAG, "拒绝明文更新接口（必须使用 https）: $apiUrl")
                    return@withContext Result.failure(
                        IllegalArgumentException("更新接口必须使用 https（当前为明文 http，已拒绝以防安装包被篡改）")
                    )
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
                            val sha = json.optString("apkSha256", "").trim().lowercase().ifBlank { null }

                            // 没有下载地址就不算「有更新」：否则会弹出更新框，用户点「下载」却毫无反应
                            // （调用点的 downloadUrl 非空守卫会直接跳过）。GitHub 分支此前已有该校验，
                            // 这里补齐同款守卫。
                            val hasUpdate = url.isNotBlank() &&
                                (vCode.toLong() > currentVersionCode || isVersionNewer(vName, currentVersionName))
                            return@withContext Result.success(
                                UpdateInfo(
                                    hasUpdate = hasUpdate,
                                    latestVersion = vName,
                                    latestVersionCode = vCode,
                                    releaseNotes = notes,
                                    downloadUrl = url,
                                    apkSizeBytes = size,
                                    isForceUpdate = force,
                                    apkSha256 = sha
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
            if (e is kotlinx.coroutines.CancellationException) throw e
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
        /** 服务端声明的大小（>0 时启用大小校验） */
        expectedSizeBytes: Long = 0L,
        /** 服务端声明的 SHA-256（非空时启用哈希校验） */
        expectedSha256: String? = null,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // 明文下载地址直接拒绝：安装包是最高权限的载荷，绝不能走可被篡改的通道
            if (!downloadUrl.startsWith("https://", ignoreCase = true)) {
                return@withContext Result.failure(
                    IllegalArgumentException("安装包下载地址必须使用 https（当前为明文，已拒绝以防被替换）")
                )
            }

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

            // ===== 完整性校验 =====
            // 此前下载完就直接把文件交给安装流程：chunked 编码或被服务端提前关闭连接时，
            // 短包不会被识别（照样返回 success），用户拿到的是一个损坏/不完整的 APK。
            val downloadedSize = apkFile.length()
            if (downloadedSize <= 0L) {
                apkFile.delete()
                return@withContext Result.failure(IllegalStateException("安装包为空，下载失败"))
            }
            if (expectedSizeBytes > 0L && downloadedSize != expectedSizeBytes) {
                apkFile.delete()
                return@withContext Result.failure(
                    IllegalStateException("安装包大小校验失败（期望 $expectedSizeBytes 字节，实际 $downloadedSize 字节），已删除损坏文件")
                )
            }
            if (!expectedSha256.isNullOrBlank()) {
                val actualSha = sha256Of(apkFile)
                if (!actualSha.equals(expectedSha256.trim(), ignoreCase = true)) {
                    apkFile.delete()
                    return@withContext Result.failure(
                        IllegalStateException("安装包 SHA-256 校验失败（期望 $expectedSha256，实际 $actualSha），已删除被篡改文件")
                    )
                }
            }
            // 签名比对：确保下载到的包与当前安装的应用出自同一签名者。
            // 这是防「中间人替换成另一个可正常安装的 APK」的最后一道闸门。
            val signerMismatch = verifySameSigner(context, apkFile)
            if (signerMismatch != null) {
                apkFile.delete()
                return@withContext Result.failure(IllegalStateException("安装包签名校验失败：$signerMismatch，已删除该文件"))
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Download APK failed", e)
            Result.failure(e)
        }
    }

    /** 计算文件 SHA-256（小写十六进制）。流式读取，不把整个 APK 读进内存。 */
    private fun sha256Of(file: File): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            java.io.FileInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "计算 SHA-256 失败", e)
            ""
        }
    }

    /**
     * 校验下载到的 APK 与当前已安装应用的**签名者**是否一致。
     *
     * 返回 null 表示一致（可以安装）；返回字符串表示不一致的原因。
     * 这是防「中间人把更新包换成另一个同样能正常安装的 APK」的最后一道闸门 ——
     * 大小与哈希只能防篡改传输，签名比对才能防替换来源。
     */
    private fun verifySameSigner(context: Context, apkFile: File): String? {
        return try {
            val pm = context.packageManager

            // 已安装应用的签名
            val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                    .signatures
            }
            val installedSha = installedSignatures?.firstOrNull()?.toByteArray()

            // 待安装 APK 的签名
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
            }
            val apkSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo?.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                archiveInfo?.signatures
            }
            val apkSha = apkSignatures?.firstOrNull()?.toByteArray()

            when {
                archiveInfo == null -> "无法解析安装包（文件可能已损坏）"
                installedSha == null || apkSha == null -> "读取签名失败"
                !installedSha.contentEquals(apkSha) -> "与当前应用签名不一致（可能被替换为第三方包）"
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "签名比对失败", e)
            // 比对过程本身异常时不阻断安装，交由系统安装器做最终校验
            null
        }
    }

    /**
     * 调起系统安装器安装更新包。
     *
     * 历史实现只弹一句 Toast「请通过电视文件管理器安装」，且文件落在
     * `Android/data/<包名>/files/Download` —— Android 11+ 的文件管理器**无法访问**该目录，
     * 等于更新流程整体不可用。现在改为标准链路：
     *   1. 先做签名比对（见 verifySameSigner，已在下载阶段完成）；
     *   2. 用 FileProvider 授权 URI + ACTION_VIEW 调起系统安装器；
     *   3. 若设备确实拦截（部分广电定制 ROM 会拦 PackageInstaller），
     *      再降级为「把安装包复制到公共 Download 目录 + 明确路径提示」。
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(context, "安装包不存在，请重新下载", Toast.LENGTH_SHORT).show()
            return
        }

        // 安装前再校验一次签名：downloadApk 已校验，但文件在磁盘上可能被替换
        val signerProblem = verifySameSigner(context, apkFile)
        if (signerProblem != null) {
            Toast.makeText(context, "安装包校验未通过：$signerProblem", Toast.LENGTH_LONG).show()
            Log.e(TAG, "拒绝安装：$signerProblem (file=${apkFile.absolutePath})")
            return
        }

        // 0) Android 8.0+ 除 REQUEST_INSTALL_PACKAGES 之外，还需要用户为该应用打开
        //    「允许安装未知应用」。未打开时部分 ROM 只显示一个被阻止页甚至静默失败，
        //    而这里如果继续走 startActivity 就再也不会给用户任何提示 —— 表现为「点了更新没反应」。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = try {
                context.packageManager.canRequestPackageInstalls()
            } catch (_: Exception) {
                true   // 查询失败时不阻断，交给系统安装器判断
            }
            if (!canInstall) {
                Toast.makeText(
                    context,
                    "请先允许本应用「安装未知应用」，再重新点击更新",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settingsIntent)
                } catch (e: Exception) {
                    Log.w(TAG, "无法打开「安装未知应用」设置页，尝试打开应用详情页", e)
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                return
            }
        }

        // 1) 标准链路：FileProvider + 系统安装器
        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = androidx.core.content.FileProvider.getUriForFile(context, authority, apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
            return
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider 安装链路不可用，降级为外置目录提示", e)
        }

        // 2) 降级：复制到公共 Download 目录（文件管理器可见），并给出明确路径
        val exposedPath = try {
            copyToPublicDownloads(context, apkFile)
        } catch (e: Exception) {
            Log.w(TAG, "复制安装包到公共目录失败", e)
            apkFile.absolutePath
        }
        Toast.makeText(
            context,
            "已尝试调起安装器失败，安装包已保存至：$exposedPath（可用电视文件管理器或U盘助手安装）",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 把安装包复制到公共 Download 目录，供设备自带文件管理器访问。
     * Android 10+ 分区存储下公共目录可直接写入（属媒体/下载集合豁免）。
     */
    private fun copyToPublicDownloads(context: Context, apkFile: File): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (dir != null && (dir.exists() || dir.mkdirs())) {
            val target = File(dir, apkFile.name)
            apkFile.copyTo(target, overwrite = true)
            return target.absolutePath
        }
        return apkFile.absolutePath
    }
}
