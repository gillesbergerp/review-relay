package com.github.gillesbergerp.reviewrelay.util

import com.intellij.openapi.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PathMatchTest {

    private val backslash = File.separatorChar

    /**
     * Absolute on whichever machine runs this. "C:/proj" is a relative name off Windows, where
     * resolve() nests it under the working directory and every answer below changes with it.
     */
    private val base = if (SystemInfo.isWindows) "C:/proj" else "/proj"
    private val elsewhere = if (SystemInfo.isWindows) "C:/elsewhere" else "/elsewhere"

    @Test
    fun `normalize trims a trailing separator`() {
        assertEquals("C:/work/aws", PathMatch.normalize("C:/work/aws/"))
    }

    @Test
    fun `paths that differ only in separators are the same`() {
        assumeTrue(backslash == '\\')
        assertTrue(PathMatch.same("C:" + backslash + "work" + backslash + "aws", "C:/work/aws"))
    }

    @Test
    fun `a worktree is not its main checkout`() {
        assertFalse(PathMatch.same("C:/work/worktrees/aws-fb-monitors", "C:/work/aws"))
    }

    @Test
    fun `a sibling directory sharing a prefix is not the same`() {
        assertFalse(PathMatch.same("C:/work/aws-preview", "C:/work/aws"))
    }

    @Test
    fun `case is ignored only where the file system ignores it`() {
        val same = PathMatch.same("C:/work/AWS", "C:/work/aws")
        assertEquals(!SystemInfo.isFileSystemCaseSensitive, same)
    }

    @Test
    fun `a null path never matches`() {
        assertFalse(PathMatch.same(null, "C:/work/aws"))
        assertFalse(PathMatch.same("C:/work/aws", null))
    }

    @Test
    fun `a directory inside a project is below it, separators notwithstanding`() {
        assertTrue(PathMatch.below("C:/work/aws", "C:/work/aws/src/main"))
        assumeTrue(backslash == '\\')
        assertTrue(PathMatch.below("C:/work/aws", "C:" + backslash + "work" + backslash + "aws" + backslash + "src"))
    }

    @Test
    fun `below asks about case the same way same does`() {
        val below = PathMatch.below("C:/work/AWS", "C:/work/aws/src")
        assertEquals(!SystemInfo.isFileSystemCaseSensitive, below)
    }

    @Test
    fun `a project is not below itself, and a sibling sharing a prefix is not below either`() {
        assertFalse(PathMatch.below("C:/work/aws", "C:/work/aws"))
        assertFalse(PathMatch.below("C:/work/aws", "C:/work/aws-preview/src"))
    }

    /** An agent names the file it found something in, and the path arrives as it typed it. */
    @Test
    fun `a path inside the project resolves to where it is`() {
        assertEquals("$base/src/Foo.kt", PathMatch.within(base, "src/Foo.kt"))
        assertEquals("$base/src/Foo.kt", PathMatch.within(base, """src\Foo.kt"""))
        assertEquals("$base/src/Foo.kt", PathMatch.within(base, "./src/Foo.kt"))
    }

    @Test
    fun `an absolute path inside the project is kept`() {
        assertEquals("$base/src/Foo.kt", PathMatch.within(base, "$base/src/Foo.kt"))
    }

    /** The VFS walks `..` like anything else, so a proposal could have read a file outside. */
    @Test
    fun `a path that climbs out of the project is refused`() {
        assertNull(PathMatch.within(base, "../secret.txt"))
        assertNull(PathMatch.within(base, "src/../../secret.txt"))
        assertNull(PathMatch.within(base, "$elsewhere/secret.txt"))
    }

    @Test
    fun `climbing out and back in again is still inside`() {
        assertEquals("$base/src/Foo.kt", PathMatch.within(base, "src/sub/../Foo.kt"))
    }

    @Test
    fun `the project directory itself is not a file in it`() {
        assertNull(PathMatch.within(base, "."))
        assertNull(PathMatch.within(base, ""))
    }
}
