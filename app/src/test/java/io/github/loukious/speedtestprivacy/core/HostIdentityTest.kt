package io.github.loukious.speedtestprivacy.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HostIdentityTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun apk(file: File, entry: String, contents: String): File {
        ZipOutputStream(file.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(entry))
            output.write(contents.toByteArray())
            output.closeEntry()
        }
        return file
    }

    @Test fun baseAndConfigurationSplitBytesBothAffectFingerprint() {
        val base = apk(temporary.newFile("base.apk"), "classes.dex", "dex fixture")
        val split = apk(temporary.newFile("split.apk"), "resources.arsc", "configuration one")
        val cache = temporary.newFolder("cache")
        val first = HostIdentity.fromApks("one", 1, listOf(base.path, split.path), cache)
        apk(split, "resources.arsc", "configuration two with a different length")
        val second = HostIdentity.fromApks("one", 1, listOf(base.path, split.path), cache)
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.apkPaths, second.apkPaths)
        assertFalse(first.verifiedProfile)
        assertFalse(second.verifiedProfile)
    }

    @Test fun digestsReuseOnlyUnchangedPathLengthAndModificationTime() {
        val base = apk(temporary.newFile("base.apk"), "classes.dex", "first")
        var computed = 0
        val store = ApkFingerprintStore(temporary.newFile("digests")) { file ->
            computed++
            HostIdentity.sha256(file)
        }
        val first = store.readApks(listOf(base.canonicalPath)).single()
        assertEquals(1, computed)
        assertEquals(first, store.readApks(listOf(base.canonicalPath)).single())
        assertEquals(1, computed)
        assertTrue(base.setLastModified(base.lastModified() + 10_000))
        store.readApks(listOf(base.canonicalPath))
        assertEquals(2, computed)
        apk(base, "classes.dex", "second with another length")
        val modified = store.readApks(listOf(base.canonicalPath)).single()
        assertEquals(3, computed)
        assertNotEquals(first.sha256, modified.sha256)
        val relocated = temporary.newFile("relocated.apk")
        base.copyTo(relocated, overwrite = true)
        store.readApks(listOf(relocated.canonicalPath))
        assertEquals(4, computed)
    }

    @Test fun changedApkDuringDigestCannotProduceTrustedIdentity() {
        val base = apk(temporary.newFile("base.apk"), "classes.dex", "before")
        val store = ApkFingerprintStore(temporary.newFile("digests")) { file ->
            val digest = HostIdentity.sha256(file)
            assertTrue(file.setLastModified(file.lastModified() + 10_000))
            digest
        }
        try {
            store.readApks(listOf(base.canonicalPath))
            fail("Concurrent APK replacement must invalidate identity")
        } catch (_: java.io.IOException) {
            assertEquals(0L, temporary.root.resolve("digests").length())
        }
    }

    @Test fun baseMutationWhileHashingLaterSplitInvalidatesEntireSet() {
        val base = apk(temporary.newFile("base.apk"), "classes.dex", "base")
        val split = apk(temporary.newFile("split.apk"), "resources.arsc", "split")
        val store = ApkFingerprintStore(temporary.newFile("digests")) { file ->
            val digest = HostIdentity.sha256(file)
            if (file.canonicalPath == split.canonicalPath)
                assertTrue(base.setLastModified(base.lastModified() + 10_000))
            digest
        }
        try {
            store.readApks(listOf(base.canonicalPath, split.canonicalPath))
            fail("Earlier APK metadata changed while a later APK was hashed")
        } catch (_: java.io.IOException) {
            assertEquals(0L, temporary.root.resolve("digests").length())
        }
    }

    @Test fun verifiedProfileRequiresExactBaseCodeAndNoExtraDex() {
        val hash = HostIdentity.VERIFIED_BASE_SHA256
        assertTrue(HostIdentity.profileMatches(265370L, hash, splitHasDex = false))
        assertFalse(HostIdentity.profileMatches(265371L, hash, splitHasDex = false))
        assertFalse(HostIdentity.profileMatches(265370L, "0".repeat(64), splitHasDex = false))
        assertFalse(HostIdentity.profileMatches(265370L, hash, splitHasDex = true))
        val config = apk(temporary.newFile("config.apk"), "resources.arsc", "resources")
        val feature = apk(temporary.newFile("feature.apk"), "classes2.dex", "dex")
        val unrelated = apk(temporary.newFile("asset.apk"), "assets/classes.dex", "asset")
        assertFalse(HostIdentity.hasExecutableDex(config))
        assertTrue(HostIdentity.hasExecutableDex(feature))
        assertFalse(HostIdentity.hasExecutableDex(unrelated))
    }

    @Test fun malformedDigestCacheCannotClaimTheKnownProfile() {
        val base = apk(temporary.newFile("base.apk"), "classes.dex", "unknown bytes")
        val cache = temporary.newFile("digests")
        cache.writeText("schema=1\ncount=1\napk.0.sha256=${HostIdentity.VERIFIED_BASE_SHA256}\n")
        val result = ApkFingerprintStore(cache).readApks(listOf(base.canonicalPath)).single()
        assertEquals(HostIdentity.sha256(base), result.sha256)
        assertNotEquals(HostIdentity.VERIFIED_BASE_SHA256, result.sha256)
    }
}
