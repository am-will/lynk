package dev.androidagent.storage

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class AppPrivateBlobStoreTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun unknownLengthIsStoppedAtTheStreamingLimitAndPartialIsDeleted() {
        val root = root()
        val store = store(root, maxItemBytes = 8)

        assertThrows(BlobLimitExceededException::class.java) {
            store.import(request("blob_oversize"), openInput = { ByteArrayInputStream(ByteArray(9) { 1 }) })
        }

        assertEquals(emptyList<String>(), root.listFiles().orEmpty().map(File::getName))
    }

    @Test
    fun declaredOversizeIsRejectedBeforeOpeningInput() {
        val root = root()
        val store = store(root, maxItemBytes = 8)
        var opened = false

        assertThrows(BlobLimitExceededException::class.java) {
            store.import(request("blob_declared", declaredSize = 9), openInput = {
                opened = true
                ByteArrayInputStream(byteArrayOf(1))
            })
        }

        assertFalse(opened)
        assertEquals(emptyList<String>(), root.listFiles().orEmpty().map(File::getName))
    }

    @Test
    fun cancellationDeletesPartialAndPublishesNoMetadata() {
        val root = root()
        val store = store(root, maxItemBytes = 16)
        var checks = 0

        assertThrows(BlobImportCancelledException::class.java) {
            store.import(
                request("blob_cancelled"),
                openInput = { OneByteAtATimeInputStream(ByteArray(8) { 2 }) },
                shouldCancel = { ++checks > 3 }
            )
        }

        assertEquals(emptyList<String>(), root.listFiles().orEmpty().map(File::getName))
    }

    @Test
    fun shortDeclaredBodyIsRejectedAndPartialIsDeleted() {
        val root = root()
        val store = store(root, maxItemBytes = 16)

        assertThrows(java.io.IOException::class.java) {
            store.import(request("blob_short-body", declaredSize = 5), openInput = {
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            })
        }

        assertEquals(emptyList<String>(), root.listFiles().orEmpty().map(File::getName))
    }

    @Test
    fun successfulImportPublishesPayloadMetadataAndChecksumAtomically() {
        val root = root()
        val store = store(root, maxItemBytes = 16)
        val bytes = "hello".toByteArray()

        val imported = store.import(request("blob_successful", declaredSize = bytes.size.toLong()), openInput = {
            ByteArrayInputStream(bytes)
        })
        val resolved = store.resolve(imported.id, imported.sha256)

        assertNotNull(resolved)
        assertArrayEquals(bytes, resolved!!.file.readBytes())
        assertEquals(64, imported.sha256.length)
        assertEquals(setOf("blob_successful.blob", "blob_successful.json"), root.listFiles().orEmpty().map(File::getName).toSet())
    }

    @Test
    fun startupCleanupRemovesPartialsAndOrphans() {
        val root = root()
        File(root, ".blob_abandoned.partial").writeText("partial")
        File(root, "blob_orphan.blob").writeText("orphan")
        File(root, "blob_missing.json").writeText("{}")

        store(root, maxItemBytes = 16)

        assertEquals(emptyList<String>(), root.listFiles().orEmpty().map(File::getName))
    }

    private fun root(): File = Files.createTempDirectory("lynk-blob-store-").toFile().also(roots::add)

    private fun store(root: File, maxItemBytes: Long): AppPrivateBlobStore =
        AppPrivateBlobStore(
            directory = root,
            limits = BlobStoreLimits(
                maxItemBytes = maxItemBytes,
                maxBlobCount = 4,
                maxAggregateBytes = maxItemBytes * 4,
                freeSpaceReserveBytes = 0,
                retentionMillis = 60_000
            ),
            usableSpaceBytes = { Long.MAX_VALUE }
        )

    private fun request(id: String, declaredSize: Long? = null): BlobImportRequest =
        BlobImportRequest(id, "sample.bin", "application/octet-stream", declaredSize)

    private class OneByteAtATimeInputStream(private val bytes: ByteArray) : InputStream() {
        private var index = 0

        override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index >= bytes.size) return -1
            buffer[offset] = bytes[index++]
            return 1
        }
    }
}
