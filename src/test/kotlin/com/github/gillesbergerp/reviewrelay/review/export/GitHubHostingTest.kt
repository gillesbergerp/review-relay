package com.github.gillesbergerp.reviewrelay.review.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which remote URLs name a host worth asking gh about, and which cannot be read at all. */
class GitHubHostingTest {

    @Test
    fun `the three shapes a remote arrives in all name their host`() {
        assertEquals("github.com", GitHubHosting.hostOf("git@github.com:owner/repo.git"))
        assertEquals("github.com", GitHubHosting.hostOf("https://github.com/owner/repo.git"))
        assertEquals("github.com", GitHubHosting.hostOf("ssh://git@github.com/owner/repo.git"))
    }

    @Test
    fun `a port and a user in the url are not part of the host`() {
        assertEquals("github.acme.io", GitHubHosting.hostOf("ssh://git@github.acme.io:22/owner/repo.git"))
        assertEquals("github.acme.io", GitHubHosting.hostOf("https://dev@github.acme.io/owner/repo.git"))
    }

    @Test
    fun `an enterprise host is a host like any other`() {
        assertEquals("code.acme.io", GitHubHosting.hostOf("git@code.acme.io:owner/repo.git"))
    }

    @Test
    fun `the host is compared in one case`() {
        assertEquals("github.com", GitHubHosting.hostOf("git@GitHub.COM:owner/repo.git"))
    }

    /** A local path is not a host, and on Windows it is the same shape as the scp form. */
    @Test
    fun `a path is not read as a host`() {
        assertNull(GitHubHosting.hostOf("C:\\repos\\thing"))
        assertNull(GitHubHosting.hostOf("/home/dev/repos/thing"))
        assertNull(GitHubHosting.hostOf("../sibling"))
    }

    /** Only ssh can say what an alias resolves to, so this answers "ask someone else". */
    @Test
    fun `an ssh alias is not read as a host`() {
        assertNull(GitHubHosting.hostOf("git@work-github:owner/repo.git"))
        assertNull(GitHubHosting.hostOf("ssh://git@localhost/owner/repo.git"))
    }

    @Test
    fun `nothing is not a host`() {
        assertNull(GitHubHosting.hostOf(""))
        assertNull(GitHubHosting.hostOf("   "))
    }
}
