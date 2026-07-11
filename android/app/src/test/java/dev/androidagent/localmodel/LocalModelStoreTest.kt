package dev.androidagent.localmodel

import dev.androidagent.storage.BlobLimitExceededException
import dev.androidagent.storage.BlobStoreLimits
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
