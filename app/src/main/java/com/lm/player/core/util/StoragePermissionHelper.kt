package com.lm.player.core.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 存储权限统一判定 (BYD 车机存储问题修复，第十八节)。
 *
 * 各系统版本下「用普通文件方式读写共享存储」需要的权限并不相同：
 * - Android 6 ~ 10 (API 23~29)：读取 READ_EXTERNAL_STORAGE、写入 WRITE_EXTERNAL_STORAGE。
 *   本应用开启了 requestLegacyExternalStorage，Android 10 上仍按这套旧规则执行；
 * - Android 11 ~ 12L (API 30~32)：读取 READ_EXTERNAL_STORAGE；写入公共目录需「所有文件访问」；
 * - Android 13+ (API 33+)：读取音频改为 READ_MEDIA_AUDIO；写入同上。
 *
 * 此前清单里写入权限只声明到 Android 9，Android 10 车机上永远拿不到写入权限，
 * 所以「更换下载目录」在内部存储的任何文件夹上都提示没有权限。
 */
object StoragePermissionHelper {

    /** 读取本地音乐需要的运行时权限 */
    fun readPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.READ_MEDIA_AUDIO)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * 写入下载目录需要的运行时权限。
     * Android 11+ 的写入走「所有文件访问」或系统文件选择器，不是运行时权限，这里返回空。
     */
    fun writePermissions(): List<String> = when {
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q -> listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        else -> emptyList()
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context, permissions: List<String>): List<String> =
        permissions.filter { !isGranted(context, it) }

    fun hasReadPermission(context: Context): Boolean = missing(context, readPermissions()).isEmpty()

    /** 运行时写入权限是否就绪 (Android 11+ 恒为 true，因为那里没有对应的运行时权限) */
    fun hasWritePermission(context: Context): Boolean = missing(context, writePermissions()).isEmpty()

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /** Android 10 上旧版存储模式是否实际生效 (其它版本返回 null 表示不适用) */
    fun isLegacyStorageActive(): Boolean? =
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            runCatching { Environment.isExternalStorageLegacy() }.getOrNull()
        } else null

    fun permissionLabel(permission: String): String = when {
        permission.endsWith("READ_MEDIA_AUDIO") -> "读取音乐"
        permission.endsWith("READ_EXTERNAL_STORAGE") -> "读取存储"
        permission.endsWith("WRITE_EXTERNAL_STORAGE") -> "写入存储"
        else -> permission.substringAfterLast('.')
    }

    /** 系统文件夹选择器 (DocumentsUI) 是否存在：部分车机系统裁掉了它，直接启动会失败或闪退回来 */
    fun canOpenDocumentTree(context: Context): Boolean = runCatching {
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 系统「应用信息 / 权限」页是否存在 (车机系统常被裁掉) */
    fun canOpenAppDetails(context: Context): Boolean = runCatching {
        appDetailsIntent(context).resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val pm = context.packageManager
        val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        if (runCatching { perApp.resolveActivity(pm) }.getOrNull() != null) return perApp
        val general = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        if (runCatching { general.resolveActivity(pm) }.getOrNull() != null) return general
        return null
    }

    /**
     * 每个存储卷 (内部存储、各 U 盘) 上本应用的专属音乐目录：`<卷>/Android/data/<包名>/files/Music`。
     * 这些目录**不需要任何权限**就能读写，U 盘上也可以 —— Android 10 上 U 盘公共目录无法用普通文件方式写入，
     * 专属目录是唯一不依赖系统文件选择器的可写位置。代价是卸载应用时会被系统一并清除。
     */
    fun appSpecificMusicDirs(context: Context): List<File> = runCatching {
        ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_MUSIC).filterNotNull()
    }.getOrDefault(emptyList())

    /** 目录所在卷的根路径 (由专属目录路径反推)，用于和存储卷列表对应 */
    fun volumeRootOf(appSpecificDir: File): String {
        val abs = appSpecificDir.absolutePath
        val idx = abs.indexOf("/Android/data/")
        return if (idx > 0) abs.substring(0, idx) else abs
    }

    /** 真正写一个探针文件来判断目录可写 (canWrite() 在 FUSE / 车机挂载点上并不可靠)。须在 IO 线程调用。 */
    fun probeWritable(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            if (!dir.isDirectory) return false
            val probe = File(dir, ".lm_probe_${System.currentTimeMillis()}.tmp")
            val ok = probe.createNewFile()
            if (ok) probe.delete()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** 目录能否列出内容。须在 IO 线程调用。 */
    fun probeReadable(dir: File): Boolean = try {
        dir.isDirectory && dir.listFiles() != null
    } catch (_: Exception) {
        false
    }

    /** 路径是否位于 U 盘 / 外接卷 (而非内部存储) */
    fun isOnRemovableVolume(path: String): Boolean {
        val internal = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull() ?: "/storage/emulated/0"
        return !(path == internal || path.startsWith("$internal/") || path.startsWith("/storage/emulated/") || path.startsWith("/sdcard"))
    }

    /** 是否为本应用的专属目录 (无需权限即可写入) */
    fun isAppSpecificPath(context: Context, path: String): Boolean =
        path.contains("/Android/data/${context.packageName}")

    /**
     * 写入失败时给出具体原因 (不再一律提示「无写入权限」)。
     */
    fun explainWriteFailure(context: Context, path: String): String {
        val missingWrite = missing(context, writePermissions())
        return when {
            missingWrite.isNotEmpty() ->
                "无法写入：未授予「${missingWrite.joinToString("、") { permissionLabel(it) }}」权限。" +
                    "请在弹窗中允许，或到系统设置 → 应用 → LMPlayer → 权限 中开启存储权限后重试"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess() && !isAppSpecificPath(context, path) ->
                "无法写入：Android 11 及以上写入此位置需要「所有文件访问」权限，或改用系统文件夹选择器选择目录"
            isOnRemovableVolume(path) && !isAppSpecificPath(context, path) ->
                "无法写入：系统不允许直接写入 U 盘的普通文件夹。请在选择器里点「U 盘专属目录」，或改用系统文件夹选择器选择 U 盘目录"
            else ->
                "无法写入该目录（系统限制此位置）。请换一个文件夹，或选择「专属目录」快捷项"
        }
    }
}
