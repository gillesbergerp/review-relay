import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    // Tied to the Kotlin version above: it is a compiler plugin, not a library.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.github.gillesbergerp"
version = "0.13.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1") {
            useInstaller.set(false)
        }
        bundledModule("intellij.platform.vcs.impl.shared")
        bundledModule("intellij.platform.builtInServer.impl")
        // The base-ref diff is git-specific; the generic VCS API has no changeset-against-a-ref.
        bundledPlugin("Git4Idea")
        // Its toolset extension point is how any MCP client reaches the review; optional at runtime.
        pluginVerifier()
    }
    // Bundled rather than taken from the IDE: pinned to the version the platform ships, so the
    // plugin behaves the same whichever copy a future build resolves.
    // error_prone_annotations comes along otherwise, and nothing reads it at runtime.
    implementation("com.google.code.gson:gson:2.13.2") { isTransitive = false }
    // Bundled rather than taken from the IDE: the platform ships its own copy for its own use and
    // does not publish it as API, so relying on that would break on a version we do not control.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.gillesbergerp.reviewrelay"
        name = "Review Relay"
        version = project.version.toString()
        description = """
            Review what your coding agent wrote the way you review a pull request.
            Leave inline comments on lines and ranges in the editor or the local-changes diff,
            then hand the whole review to your agent: every comment arrives with the exact lines it
            refers to, so the agent reads the code and your note together. Ask an agent to review the
            changes and it proposes comments back, for you to add to the review or dismiss; a
            finished review can also be posted to its GitHub pull request. Drives OpenCode and Claude
            Code directly, and any MCP client through the tools it publishes to the IDE.
            <br/><br/>
            Forked from <a href="https://github.com/jspdown/code-review-annotator">code-review-annotator</a> by Harold Ozouf (MIT).
        """.trimIndent()
        changeNotes = """
            <b>0.13.2</b> — 2026-09-08
            <br/>
            First public release, and a <b>proposal now belongs beside the code</b>. What an agent
            proposes is drawn in the editor at the lines it is about, on the same terms as your own
            comment, and can be taken to its code and filed against wherever that code has since
            moved to. Reading a proposal in the column while the code it concerns sits on screen was
            always the harder way round.
            <br/><br/>
            Fixes throughout, most of them things you would have met in the first minutes.
            <b>Hovering a comment</b> read the document without a read action, which on 2026.1 the
            platform reports as an error naming this plugin - fifty times over in one sitting.
            <b>A comment's text</b> could not be selected or copied at all: the pane kept a caret for
            it and then refused the focus the caret needs. The shading marked whatever the pointer
            rested on rather than the comment you were on, and clicking a comment's text left the
            arrow keys scrolling instead of stepping between comments.
            <br/><br/>
            The <b>changed files</b> beside a review were a snapshot taken when the pane was opened,
            so a file that stopped being changed sat there until you switched away and back; the list
            is the IDE's own now, and keeps up as you type. A pull request that <b>refused a review</b>
            answered with the bare words "Unprocessable Entity" - the reason and the field it came
            from are now said. And the MCP endpoint is served under the prefix its handler expects,
            so an MCP client reaches the review rather than a 404.
            <br/><br/>
            <b>Posted comments read as they were written.</b> The type is for the review and for the
            agent, and neither it nor a line naming this plugin had any business in someone else's
            pull request.
            <br/><br/>
            <b>0.13.1</b> — 2026-09-07
            <br/>
            An OpenCode session's own directory is read again, so a session standing in another
            checkout is named as such and asks before a review is sent to it. <b>Reconnect</b> no
            longer freezes the IDE for the length of discovery, an ended event stream is replaced
            rather than kept, and a session backing off after a rate limit reads as working rather
            than finished. A comment keeps its place through the agent rewriting the lines it is
            about, and opening a commit's diff no longer moves a working-tree comment's anchor into
            that commit. In a <b>unified diff</b>, comments go through the viewer's own line
            mapping, where they used to name lines the file does not have. <b>Post to the Pull
            Request</b> reads every page of a pull request's files, where one of more than thirty
            files failed outright, and a second click cannot post the same review twice.
            <br/><br/>
            <b>0.13.0</b> — 2026-09-06
            <br/>
            A review now goes both ways. An agent can <b>propose</b> a comment of its own through a
            new <code>review_propose</code> tool, and <b>Request a Review</b> asks the session your
            review is worked in to go over the changes and report what it finds. Proposals land in a
            quiet inbox under your comments, to be added, reworded or dismissed. Nothing leaves the
            IDE that you have not added to the review: a proposal is never sent, copied or posted.
            <br/>
            <b>Post to the Pull Request</b> submits your open comments as one GitHub review through
            the <code>gh</code> CLI, so no credential is stored here. Lines are checked against the
            pull request's diff first; anything that cannot be placed arrives on the file instead,
            and you are told how many before posting rather than after.
            <br/>
            A review's tab says where it stands in one icon, and a comment's type can be set from
            the keyboard with <b>Alt+F</b>, <b>Alt+C</b>, <b>Alt+Q</b> and <b>Alt+T</b>.
            <br/><br/>
            <b>0.12.1</b> — 2026-09-06
            <br/>
            Naming a control for a screen reader threw while the pane was being built, so 0.12.0
            came up with no tabs at all. What the agent is sent was rewritten: a review is explained
            to a session once and recapped after that, rather than restating the conventions every
            round, and an agent working in another checkout is told which one.
            <br/><br/>
            <b>0.12.0</b> — 2026-09-06
            <br/>
            The comment list gained its own toolbar, <b>Group By</b>, keyboard navigation and
            screen-reader names. Comment cards lead with the file and line, and a commented line is
            marked in the gutter and on the scrollbar.
        """.trimIndent()
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
        vendor {
            name = "Paul Gillesberger"
            email = "paulgillesberger@live.com"
            url = "https://github.com/gillesbergerp/review-relay"
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        ides {
            recommended()
        }
        // Everything that says the plugin would actually break, and one that says an API it leans on
        // is going away. Internal-API use is deliberate: embedding the IDE's own log pane needs
        // VcsLogProjectTabsProperties, which the platform publishes no equivalent of.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
    }
}

tasks {
    test {
        useJUnit()
    }

    // The MIT terms this inherits from code-review-annotator ask that the notice travel with the
    // copies, and the distributed zip is the only copy a user of the plugin ever gets.
    jar {
        from(rootDir) {
            include("LICENSE")
            into("META-INF")
        }
    }

    register("printVersion") {
        val version = project.version.toString()
        doLast { println(version) }
    }
}
