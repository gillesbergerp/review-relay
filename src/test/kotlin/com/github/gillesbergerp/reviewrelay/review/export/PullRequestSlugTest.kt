package com.github.gillesbergerp.reviewrelay.review.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which repository a pull request is in, which is not always the one gh would guess. */
class PullRequestSlugTest {

    @Test
    fun `a pull request url names its repository`() {
        assertEquals("owner/repo", GitHubDestination.slugOf("https://github.com/owner/repo/pull/48"))
    }

    /** The fork's own URL is what keeps a review off the upstream repository by that number. */
    @Test
    fun `the upstream of a fork is not read from a fork url`() {
        assertEquals("me/repo", GitHubDestination.slugOf("https://github.com/me/repo/pull/1"))
    }

    @Test
    fun `an enterprise host makes no difference to the path`() {
        assertEquals("team/service", GitHubDestination.slugOf("https://github.acme.io/team/service/pull/9"))
    }

    @Test
    fun `a url with nowhere to take a repository from is no answer`() {
        assertNull(GitHubDestination.slugOf(""))
        assertNull(GitHubDestination.slugOf("https://github.com/owner"))
        assertNull(GitHubDestination.slugOf("https://github.com//repo/pull/1"))
    }
}
