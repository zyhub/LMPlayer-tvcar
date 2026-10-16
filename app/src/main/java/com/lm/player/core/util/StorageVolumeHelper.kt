package com.lm.player.core.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import java.io.File

data class StorageVolumeInfo(
    val id: String,
    val name: String,
    val path: String,
    val file: File,
    val isRemovable: Boolean,
    val isPrimary: Boolean,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L
)

/**
 * 跨 Android 6.0 ~ 14+ 存储卷与外接 U 盘 / SD 卡设备探测工具类
 */
object StorageVolumeHelper {

    fun getMountedVolumes(context: Context): List<StorageVolumeInfo> {
        val result = mutableListOf<StorageVolumeInfo>()
        val seenPaths = mutableSetOf<String>()

        // 1. 内部主存储
        val internalDir = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        val internalPath = internalDir.absolutePath
        seenPaths.add(internalPath)
        try {
            seenPaths.add(internalDir.canonicalPath)
        } catch (_: Exception) {}

        var internalTotal = 0L
        var internalFree = 0L
        try {
            val stat = android.os.StatFs(internalPath)
            internalTotal = stat.totalBytes
            internalFree = stat.availableBytes
        } catch (_: Exception) {}

        result.add(
            StorageVolumeInfo(
                id = "internal",
                name = "内部存储",
                path = internalPath,
                file = internalDir,
                isRemovable = false,
                isPrimary = true,
                totalBytes = internalTotal,
                freeBytes = internalFree
            )
        )

        // 2. StorageManager (API 24+)
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (sm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (vol in sm.storageVolumes) {
                    val state = vol.state
                    if (state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY) {
                        val dirPath: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            vol.directory?.absolutePath
                        } else {
                            try {
                                val getPathMethod = vol.javaClass.getMethod("getPath")
                                getPathMethod.invoke(vol) as? String
                            } catch (_: Exception) {
                                null
                            }
                        }

                        if (!dirPath.isNullOrBlank()) {
                            val file = File(dirPath)
                            val can = try { file.canonicalPath } catch (_: Exception) { dirPath }
                            if (!seenPaths.contains(dirPath) && !seenPaths.contains(can) && file.exists()) {
                                seenPaths.add(dirPath)
                                seenPaths.add(can)
                                val desc = try { vol.getDescription(context) } catch (_: Exception) { "" }
                                val isRemovable = vol.isRemovable
                                val label = when {
                                    desc.isNotBlank() -> desc
                                    isRemovable -> "U盘 (${file.name})"
                                    else -> file.name
                                }
                                var total = 0L
                                var free = 0L
                                try {
                                    val stat = android.os.StatFs(dirPath)
                                    total = stat.totalBytes
                                    free = stat.availableBytes
                                } catch (_: Exception) {}

                                result.add(
                                    StorageVolumeInfo(
                                        id = "vol_${file.name}",
                                        name = label,
                                        path = dirPath,
                                        file = file,
                                        isRemovable = isRemovable,
                                        isPrimary = vol.isPrimary,
                                        totalBytes = total,
                                        freeBytes = free
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. ContextCompat.getExternalFilesDirs (用于捕获已挂载的外接存储卷根目录)
        try {
            val extDirs = ContextCompat.getExternalFilesDirs(context, null)
            for (dir in extDirs) {
                if (dir != null) {
                    val abs = dir.absolutePath
                    val idx = abs.indexOf("/Android/data")
                    if (idx > 0) {
                        val rootPath = abs.substring(0, idx)
                        val file = File(rootPath)
                        val can = try { file.canonicalPath } catch (_: Exception) { rootPath }
                        if (!seenPaths.contains(rootPath) && !seenPaths.contains(can) && file.exists()) {
                            seenPaths.add(rootPath)
                            seenPaths.add(can)
                            var total = 0L
                            var free = 0L
                            try {
                                val stat = android.os.StatFs(rootPath)
                                total = stat.totalBytes
                                free = stat.availableBytes
                            } catch (_: Exception) {}
                            result.add(
                                StorageVolumeInfo(
                                    id = "ext_${file.name}",
                                    name = "U盘 (${file.name})",
                                    path = rootPath,
                                    file = file,
                                    isRemovable = true,
                                    isPrimary = false,
                                    totalBytes = total,
                                    freeBytes = free
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 4. 常见车载系统与 TV 盒子常见挂载节点探测
        val commonMounts = listOf(
            "/storage",
            "/mnt/usb_storage",
            "/mnt/media_rw",
            "/mnt/udisk",
            "/mnt/usb",
            "/storage/usbotg",
            "/storage/udisk0",
            "/storage/udisk1",
            "/storage/udisk2"
        )
        for (mount in commonMounts) {
            try {
                val f = File(mount)
                if (f.exists() && f.isDirectory) {
                    if (mount == "/storage") {
                        val subFiles = f.listFiles()
                        subFiles?.forEach { sub ->
                            if (sub.isDirectory && sub.name !in listOf("emulated", "self", "knox") && !sub.name.startsWith(".")) {
                                val subPath = sub.absolutePath
                                val can = try { sub.canonicalPath } catch (_: Exception) { subPath }
                                if (!seenPaths.contains(subPath) && !seenPaths.contains(can)) {
                                    seenPaths.add(subPath)
                                    seenPaths.add(can)
                                    result.add(
                                        StorageVolumeInfo(
                                            id = "mount_${sub.name}",
                                            name = "U盘 (${sub.name})",
                                            path = subPath,
                                            file = sub,
                                            isRemovable = true,
                                            isPrimary = false
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        val can = try { f.canonicalPath } catch (_: Exception) { mount }
                        if (!seenPaths.contains(mount) && !seenPaths.contains(can)) {
                            seenPaths.add(mount)
                            seenPaths.add(can)
                            result.add(
                                StorageVolumeInfo(
                                    id = "mount_${f.name}",
                                    name = "U盘 (${f.name})",
                                    path = mount,
                                    file = f,
                                    isRemovable = true,
                                    isPrimary = false
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return result
    }
}
