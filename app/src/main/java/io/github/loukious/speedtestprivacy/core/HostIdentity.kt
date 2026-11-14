package io.github.loukious.speedtestprivacy.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

/** Identity comes from the installed APK bytes, never from a discovery cache's claims. */
data class HostIdentity(
    val versionName: String,
    val versionCode: Long,
    val fingerprint: String,
    val verifiedProfile: Boolean,
    val apkPaths: List<String>
) {
    companion object {
        internal const val VERIFIED_BASE_SHA256 = "de1a74960d4377190a88a944a348d9398c237cf65aae6059bdec45a1b4914c1f"
        internal const val VERIFIED_VERSION_CODE = 265370L

        fun compute(context: Context, log: (String) -> Unit = {}): HostIdentity {
            val info = context.applicationInfo
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode
                else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
            return fromApks(
                packageInfo.versionName.orEmpty(), versionCode,
                listOf(info.sourceDir) + info.splitSourceDirs.orEmpty().sorted(),
                File(context.cacheDir, "speedtest-privacy-discovery"), log
            )
        }

        internal fun fromApks(
            versionName: String,
            versionCode: Long,
            apkPaths: List<String>,
            cacheDirectory: File,
            log: (String) -> Unit = {}
        ): HostIdentity {
            require(apkPaths.isNotEmpty()) { "No installed APK source" }
            val paths = apkPaths.map { File(it).canonicalPath }.distinct()
            val records = ApkFingerprintStore(File(cacheDirectory, "apk-digests.properties"))
                .readApks(paths, log)
            return HostIdentity(
                versionName, versionCode, combinedFingerprint(records),
                profileMatches(versionCode, records.first().sha256, records.drop(1).any { it.hasDex }),
                paths
            )
        }

        internal fun profileMatches(versionCode: Long, baseSha256: String, splitHasDex: Boolean): Boolean =
            versionCode == VERIFIED_VERSION_CODE && baseSha256 == VERIFIED_BASE_SHA256 && !splitHasDex

        internal fun hasExecutableDex(file: File): Boolean = ZipFile(file).use { zip ->
            val entries = zip.entries()
            var found = false
            while (entries.hasMoreElements() && !found) {
                val entry = entries.nextElement()
                found = !entry.isDirectory && DEX_ENTRY.matches(entry.name)
            }
            found
        }

        private val DEX_ENTRY = Regex("classes(?:[0-9]+)?\\.dex")

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().hex()
        }

        private fun combinedFingerprint(records: List<ApkDigestRecord>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("SpeedtestPrivacy.apk-set.v1\u0000".toByteArray(Charsets.UTF_8))
            records.forEachIndexed { index, record ->
                // Framing preserves base/split order and prevents ambiguous concatenation.
                digest.update(ByteBuffer.allocate(4).putInt(index).array())
                digest.update(ByteBuffer.allocate(8).putLong(record.metadata.length).array())
                digest.update(record.sha256.toByteArray(Charsets.US_ASCII))
            }
            return digest.digest().hex()
        }

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

internal data class ApkMetadata(val path: String, val length: Long, val modified: Long) {
    companion object {
        fun read(path: String): ApkMetadata {
            val file = File(path)
            if (!file.isFile || file.length() <= 0) throw IOException("Installed APK is unavailable: $path")
            return ApkMetadata(file.canonicalPath, file.length(), file.lastModified())
        }
    }
}

internal data class ApkDigestRecord(val metadata: ApkMetadata, val sha256: String, val hasDex: Boolean)

/** Digest reuse is allowed only when canonical path, length and mtime all remain unchanged. */
internal class ApkFingerprintStore(
    private val file: File,
    private val digestFile: (File) -> String = { HostIdentity.sha256(it) }
) {
    fun readApks(paths: List<String>, log: (String) -> Unit = {}): List<ApkDigestRecord> {
        val cached = readRecords().associateBy { it.metadata.path }
        val result = paths.map { path ->
            val before = ApkMetadata.read(path)
            val record = cached[path]?.takeIf { it.metadata == before } ?: run {
                val apk = File(path)
                val hash = digestFile(apk)
                check(SHA256.matches(hash)) { "Invalid APK digest" }
                ApkDigestRecord(before, hash, HostIdentity.hasExecutableDex(apk))
            }
            if (before != ApkMetadata.read(path)) throw IOException("Installed APK changed during identity verification")
            record
        }
        // A base APK can change while a later split is being hashed.
        if (result.any { it.metadata != ApkMetadata.read(it.metadata.path) })
            throw IOException("Installed APK set changed during identity verification")
        runCatching { writeRecords(result) }.onFailure {
            runCatching { log("APK digest cache write failed: ${it.javaClass.simpleName}") }
        }
        return result
    }

    private fun readRecords(): List<ApkDigestRecord> = runCatching {
        val properties = DiscoveryStorage.read(file) ?: return@runCatching emptyList()
        if (properties.getProperty("schema") != "1") return@runCatching emptyList()
        val count = properties.getProperty("count").toInt()
        require(count in 1..256)
        val records = (0 until count).map { index ->
            val prefix = "apk.$index."
            val metadata = ApkMetadata(
                checkNotNull(properties.getProperty(prefix + "path")),
                properties.getProperty(prefix + "length").toLong(),
                properties.getProperty(prefix + "modified").toLong()
            )
            val hash = checkNotNull(properties.getProperty(prefix + "sha256"))
            require(SHA256.matches(hash))
            val hasDex = when (properties.getProperty(prefix + "hasDex")) {
                "true" -> true
                "false" -> false
                else -> error("Invalid DEX flag")
            }
            ApkDigestRecord(metadata, hash, hasDex)
        }
        require(records.map { it.metadata.path }.distinct().size == records.size)
        records
    }.getOrDefault(emptyList())

    private fun writeRecords(records: List<ApkDigestRecord>) {
        val properties = Properties().apply {
            setProperty("schema", "1")
            setProperty("count", records.size.toString())
            records.forEachIndexed { index, record ->
                val prefix = "apk.$index."
                setProperty(prefix + "path", record.metadata.path)
                setProperty(prefix + "length", record.metadata.length.toString())
                setProperty(prefix + "modified", record.metadata.modified.toString())
                setProperty(prefix + "sha256", record.sha256)
                setProperty(prefix + "hasDex", record.hasDex.toString())
            }
        }
        DiscoveryStorage.write(file, properties)
    }

    private companion object { val SHA256 = Regex("[0-9a-f]{64}") }
}
