package org.telegram.configuration

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `measures and clears only the owned directory`() {
        val root = temporary.newFolder("owned")
        val unrelated = temporary.newFile("account.db").apply { writeText("keep") }
        File(root, "index.json").writeText("[]")
        val nested = File(root, "media").apply { mkdir() }
        File(nested, "copy.bin").writeBytes(ByteArray(123))
        assertEquals(125L, LocalDirectory.size(root))
        LocalDirectory.clear(root)
        assertTrue(root.isDirectory)
        assertEquals(0L, LocalDirectory.size(root))
        assertEquals("keep", unrelated.readText())
        LocalDirectory.clear(root)
    }

    @Test fun `refuses symlinks before deleting any content`() {
        val root = temporary.newFolder("owned")
        val safe = File(root, "copy.bin").apply { writeText("keep on failure") }
        val outside = temporary.newFolder("outside")
        val externalFile = File(outside, "account.db").apply { writeText("protected") }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        try {
            LocalDirectory.clear(root)
            fail("Expected refusal")
        } catch (expected: IOException) {
            assertTrue(safe.exists())
            assertEquals("protected", externalFile.readText())
        }
    }

    @Test fun `missing directory is an empty data set`() {
        val root = File(temporary.root, "missing")
        assertEquals(0L, LocalDirectory.size(root))
        LocalDirectory.clear(root)
        assertFalse(root.exists())
    }
}
