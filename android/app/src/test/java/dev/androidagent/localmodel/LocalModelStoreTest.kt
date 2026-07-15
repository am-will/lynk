package dev.androidagent.localmodel

import dev.androidagent.storage.BlobLimitExceededException
import dev.androidagent.storage.BlobStoreLimits
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class LocalModelStoreTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun rejectsWrongExtensionBeforeOpeningProvider() {
        val root = root()
        var opened = false

        assertThrows(IllegalArgumentException::class.java) {
            LocalModelStore.importStream(
                directory = root,
                metadata = metadata("model.bin", 4),
                openInput = {
                    opened = true
                    ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
                },
                limits = testLimits()
            )
        }

        assertFalse(opened)
        assertEquals(emptyList<String>(), root.listFiles().orEmpty().map(File::getName))
    }

    @Test
    fun derivesDisplayNameFromInstalledModelPath() {
        assertEquals("Bonsai-1.7B-Q1_0", LocalModelStore.displayName("/models/Bonsai-1.7B-Q1_0.gguf"))
        assertEquals("gemma", LocalModelStore.displayName("/models/gemma.litertlm"))
    }

    @Test
    fun detectsSupportedFormatsByExtension() {
        assertEquals(LocalModelStore.ModelFormat.LiteRtLm, LocalModelStore.formatForDisplayName("model.LITERTLM"))
        assertEquals(LocalModelStore.ModelFormat.Gguf, LocalModelStore.formatForDisplayName("model.gguf"))
        assertNull(LocalModelStore.formatForDisplayName("model.gguf.bin"))
        assertNull(LocalModelStore.formatForDisplayName(null))
    }

    @Test
    fun existingUnsupportedFileIsNotAnAvailableLocalModel() {
        val unsupported = File(root(), "model.bin").apply { writeText("not a model") }
        val supported = File(root(), "model.gguf").apply { writeText("model") }

        assertFalse(LocalModelStore.exists(unsupported.absolutePath))
        assertTrue(LocalModelStore.exists(supported.absolutePath))
    }

    @Test
    fun ggufUsesIndependentDirectoryAndLargerLongLimits() {
        val liteRtLm = LocalModelStore.ModelFormat.LiteRtLm
        val gguf = LocalModelStore.ModelFormat.Gguf

        assertEquals("local-model-blobs", liteRtLm.directoryName)
        assertEquals("local-gguf-model-blobs", gguf.directoryName)
        assertEquals(12L * 1024L * 1024L * 1024L, gguf.limits.maxItemBytes)
        assertEquals(3, gguf.limits.maxBlobCount)
        assertEquals(24L * 1024L * 1024L * 1024L, gguf.limits.maxAggregateBytes)
    }

    @Test
    fun importsGgufWithGgufPayloadSuffix() {
        val root = root()
        val imported = LocalModelStore.importStream(
            directory = root,
            metadata = metadata("model.gguf", 4),
            openInput = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) },
            limits = testLimits()
        )

        assertEquals("gguf", imported.file.extension)
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "gguf" })
    }

    @Test
    fun failedReplacementLeavesPreviouslyPublishedModelIntact() {
        val root = root()
        val limits = testLimits()
        val originalBytes = byteArrayOf(1, 2, 3, 4)
        val original = LocalModelStore.importStream(
            directory = root,
            metadata = metadata("working.litertlm", originalBytes.size.toLong()),
            openInput = { ByteArrayInputStream(originalBytes) },
            limits = limits
        )

        assertThrows(BlobLimitExceededException::class.java) {
            LocalModelStore.importStream(
                directory = root,
                metadata = metadata("replacement.litertlm", null),
                openInput = { ByteArrayInputStream(ByteArray(9)) },
                limits = limits
            )
        }

        assertArrayEquals(originalBytes, original.file.readBytes())
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "litertlm" })
    }

    private fun root(): File = Files.createTempDirectory("lynk-model-store-").toFile().also(roots::add)

    private fun metadata(name: String, size: Long?): LocalModelStore.ModelMetadata =
        LocalModelStore.ModelMetadata(name, size, "application/octet-stream")

    private fun testLimits(): BlobStoreLimits = BlobStoreLimits(
        minItemBytes = 1,
        maxItemBytes = 8,
        maxBlobCount = 3,
        maxAggregateBytes = 24,
        freeSpaceReserveBytes = 0,
        retentionMillis = 60_000
    )
}
