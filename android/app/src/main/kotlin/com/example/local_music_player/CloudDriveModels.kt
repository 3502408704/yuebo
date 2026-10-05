package com.example.local_music_player

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream

/** 云端存储支持的网盘。 */
enum class CloudDisk(val displayName: String) {
    BAIDU("百度网盘"),
    QUARK("夸克网盘"),
}

/**
 * 云端文件统一视图（供网盘无关的文件浏览器使用）。
 * id：百度 fsId 字符串 / 夸克 fid；path：百度真实路径 / 夸克 fid（目录打开与删除均用它）。
 * mtime 统一为毫秒（百度 serverMtime 秒 ×1000，夸克 updated_at 已是毫秒）。
 */
data class CloudFile(
    val provider: CloudDisk,
    val id: String,
    val path: String,
    val parentId: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val category: Int,
    val mtime: Long,
)

internal fun BaiduPanFile.toCloudFile(): CloudFile = CloudFile(
    provider = CloudDisk.BAIDU,
    id = fsId.toString(),
    path = path,
    parentId = path.substringBeforeLast('/', "/").ifBlank { "/" },
    name = serverFilename,
    isDir = isDir,
    size = size,
    category = category,
    mtime = serverMtime * 1_000,
)

internal fun QuarkPanFile.toCloudFile(): CloudFile = CloudFile(
    provider = CloudDisk.QUARK,
    id = fid,
    path = fid,
    parentId = pdirFid,
    name = fileName,
    isDir = isDir,
    size = size,
    category = category,
    mtime = updatedAt,
)

/** 统一视图还原为百度文件（播放/下载/文件操作走 provider 专属逻辑）。 */
internal fun CloudFile.toBaiduPanFile(): BaiduPanFile = BaiduPanFile(
    fsId = id.toLongOrNull() ?: 0L,
    path = path,
    isDir = isDir,
    serverFilename = name,
    size = size,
    category = category,
    serverMtime = mtime / 1_000,
)

/** 统一视图还原为夸克文件。 */
internal fun CloudFile.toQuarkPanFile(): QuarkPanFile = QuarkPanFile(
    fid = id,
    pdirFid = parentId,
    fileName = name,
    isDir = isDir,
    size = size,
    category = category,
    updatedAt = mtime,
)

/** 用 zxing 把二维码内容渲染成 PNG 字节（夸克二维码 URL 需本地生成图片）。 */
internal fun quarkQrImageBytes(qrUrl: String): ByteArray {
    val size = 512
    val matrix = QRCodeWriter().encode(
        qrUrl,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }
}
