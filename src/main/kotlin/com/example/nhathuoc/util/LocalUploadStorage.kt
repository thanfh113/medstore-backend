package com.example.nhathuoc.util

import com.example.nhathuoc.util.CloudinaryHelper.UploadType
import java.io.File
import java.util.UUID

object LocalUploadStorage {
    private val rootDir: File by lazy {
        File(Env.get("LOCAL_UPLOAD_DIR") ?: "uploads").canonicalFile
    }

    fun rootDirectory(): File = rootDir

    data class SavedFile(
        val relativeUrl: String,
        val publicId: String,
        val format: String,
        val resourceType: String,
        val bytes: Int
    )

    fun shouldStoreLocal(type: UploadType, extension: String): Boolean {
        val normalized = extension.trim().trimStart('.').lowercase()
        return type == UploadType.BANNER || normalized == "pdf"
    }

    fun save(bytes: ByteArray, type: UploadType, extension: String): SavedFile {
        if (bytes.size > type.maxBytes) {
            val maxMB = type.maxBytes / (1024 * 1024)
            throw IllegalArgumentException("File qua lon. Toi da ${maxMB}MB cho loai ${type.folder}")
        }

        val format = extension.trim().trimStart('.').lowercase().ifBlank { "bin" }
        val folder = type.folder
        val dir = File(rootDir, folder).canonicalFile
        if (!dir.path.startsWith(rootDir.path)) {
            throw IllegalArgumentException("Duong dan upload khong hop le")
        }
        dir.mkdirs()

        val fileName = "${UUID.randomUUID()}.$format"
        val target = File(dir, fileName).canonicalFile
        if (!target.path.startsWith(rootDir.path)) {
            throw IllegalArgumentException("Duong dan upload khong hop le")
        }
        target.writeBytes(bytes)

        val publicPath = "$folder/$fileName"
        return SavedFile(
            relativeUrl = "/static/uploads/$publicPath",
            publicId = "local:$publicPath",
            format = format,
            resourceType = if (format == "pdf") "raw" else type.resourceType,
            bytes = bytes.size
        )
    }

    fun delete(publicId: String): Boolean {
        if (!publicId.startsWith("local:")) return false
        val relative = publicId.removePrefix("local:").replace('\\', '/')
        if (relative.contains("..")) return false

        val target = File(rootDir, relative).canonicalFile
        if (!target.path.startsWith(rootDir.path)) return false
        return target.exists() && target.isFile && target.delete()
    }
}
