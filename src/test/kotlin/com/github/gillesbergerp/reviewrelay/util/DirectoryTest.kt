package com.github.gillesbergerp.reviewrelay.util

import com.intellij.openapi.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DirectoryTest {

    @Test
    fun `separators do not make two directories different`() {
        assumeTrue(File.separatorChar == '\\')
        assertEquals(Directory("C:\\work\\aws"), Directory("C:/work/aws"))
    }

    @Test
    fun `a trailing separator does not either`() {
        assertEquals(Directory("C:/work/aws/"), Directory("C:/work/aws"))
    }

    @Test
    fun `equal directories hash alike, so a map finds them`() {
        val map = mapOf(Directory("C:/work/aws/") to "here")
        assertEquals("here", map[Directory("C:/work/aws")])
    }

    @Test
    fun `case follows the file system, as it does for a path`() {
        val same = Directory("C:/work/AWS") == Directory("C:/work/aws")
        assertEquals(!SystemInfo.isFileSystemCaseSensitive, same)
    }

    @Test
    fun `a sibling sharing a prefix is neither equal nor inside`() {
        val project = Directory("C:/work/aws")
        assertFalse(project == Directory("C:/work/aws-preview"))
        assertFalse(project.contains(Directory("C:/work/aws-preview/src")))
    }

    @Test
    fun `a directory contains itself and what is under it`() {
        val project = Directory("C:/work/aws")
        assertTrue(project.contains(project))
        assertTrue(project.contains(Directory("C:/work/aws/src/main")))
    }

    @Test
    fun `the name is the last segment, for labelling a session that is elsewhere`() {
        assertEquals("aws", Directory("C:/work/aws").name)
        assertEquals("aws", Directory("C:/work/aws/").name)
    }

    @Test
    fun `nowhere named is null, not an empty directory`() {
        assertNull(Directory.of(null))
        assertNull(Directory.of("   "))
        assertEquals(Directory("C:/x"), Directory.of("C:/x"))
    }
}
