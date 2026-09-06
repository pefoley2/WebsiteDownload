package com.pefoley.websitedownload.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MirrorEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var engine: MirrorEngine
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        rootDir = tempFolder.newFolder("mirror")
        engine = MirrorEngine(client, rootDir)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `test mirroring basic page`() = runBlocking {
        val baseUrl = server.url("/").toString()
        
        server.enqueue(MockResponse().setBody("""
            <html>
                <head><title>Test</title></head>
                <body>
                    <a href="page2.html">Page 2</a>
                    <img src="img/logo.png">
                </body>
            </html>
        """).addHeader("Content-Type", "text/html"))

        server.enqueue(MockResponse().setBody("""
            <html>
                <body>
                    <h1>Page 2</h1>
                </body>
            </html>
        """).addHeader("Content-Type", "text/html"))

        server.enqueue(MockResponse().setBody("fake-image-data").addHeader("Content-Type", "image/png"))

        engine.mirror(baseUrl, maxDepth = 1)

        // Check files exist
        val indexFile = File(rootDir, "index.html")
        val page2File = File(rootDir, "page2.html")
        val logoFile = File(rootDir, "img/logo.png")

        assertTrue("index.html should exist", indexFile.exists())
        assertTrue("page2.html should exist", page2File.exists())
        assertTrue("logo.png should exist", logoFile.exists())

        // Check remapping
        val indexHtml = indexFile.readText()
        assertTrue("Link should be remapped", indexHtml.contains("href=\"page2.html\""))
        assertTrue("Image src should be remapped", indexHtml.contains("src=\"img/logo.png\""))
    }

    @Test
    fun `test max depth`() = runBlocking {
        val baseUrl = server.url("/").toString()
        
        // Depth 0
        server.enqueue(MockResponse().setBody("<a href='d1.html'>D1</a>").addHeader("Content-Type", "text/html"))
        // Depth 1
        server.enqueue(MockResponse().setBody("<a href='d2.html'>D2</a>").addHeader("Content-Type", "text/html"))
        // Depth 2
        server.enqueue(MockResponse().setBody("<a href='d3.html'>D3</a>").addHeader("Content-Type", "text/html"))

        engine.mirror(baseUrl, maxDepth = 1)

        assertTrue("d1.html should exist", File(rootDir, "d1.html").exists())
        assertTrue("d2.html should NOT exist (depth 2)", !File(rootDir, "d2.html").exists())
    }

    @Test
    fun `test download failures are recorded`() = runBlocking {
        val baseUrl = server.url("/").toString()

        server.enqueue(MockResponse().setBody("""
            <html>
                <body>
                    <img src="missing.png">
                </body>
            </html>
        """).addHeader("Content-Type", "text/html"))

        // missing.png returns 404
        server.enqueue(MockResponse().setResponseCode(404))

        engine.mirror(baseUrl, maxDepth = 1)

        val missingUrl = server.url("/missing.png").toString()
        assertTrue("Failures map should contain failed resource URL", engine.failedUrls.containsKey(missingUrl))
    }

    @Test
    fun `test mirroring nonexistent site returns false and no files`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val targetUrl = server.url("/nonexistent").toString()
        val result = engine.mirror(targetUrl, maxDepth = 1)

        Assert.assertFalse("Mirror should return false for nonexistent site", result)
        val files = rootDir.listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("No files should be downloaded", files.isEmpty())
        assertTrue("Failures should record the failure error", engine.failedUrls.containsKey(targetUrl))
        assertTrue("Failures error should mention HTTP 404", engine.failedUrls[targetUrl]?.contains("404") == true)
    }

    @Test
    fun `test invalid URL returns false and failure is recorded`() = runBlocking {
        val result = engine.mirror("invalid-url-schema", maxDepth = 1)
        Assert.assertFalse("Mirror should return false for invalid URL", result)
        assertTrue("Failures should record invalid URL", engine.failedUrls.containsKey("invalid-url-schema"))
        assertTrue("Error message should mention invalid start URL", engine.failedUrls["invalid-url-schema"]?.contains("Invalid start URL") == true)
    }

    @Test
    fun `test incremental refresh with 304 not modified`() = runBlocking {
        val baseUrl = server.url("/").toString()

        // First mirror
        server.enqueue(
            MockResponse()
                .setBody("""
                    <html>
                        <head><title>Test</title></head>
                        <body>
                            <a href="page2.html">Page 2</a>
                        </body>
                    </html>
                """.trimIndent())
                .setHeader("Content-Type", "text/html")
                .setHeader("ETag", "\"etag-v1\"")
        )

        server.enqueue(
            MockResponse()
                .setBody("<html><body>Page 2 v1</body></html>")
                .setHeader("Content-Type", "text/html")
                .setHeader("ETag", "\"p2-etag-v1\"")
        )

        engine.mirror(baseUrl, maxDepth = 1)

        val indexFile = File(rootDir, "index.html")
        val page2File = File(rootDir, "page2.html")
        assertTrue(indexFile.exists())
        assertTrue(page2File.exists())
        assertTrue(page2File.readText().contains("Page 2 v1"))

        // Drain first batch of requests from server
        server.takeRequest() // index.html
        server.takeRequest() // page2.html

        // Refresh:
        // index.html returns 304 Not Modified
        server.enqueue(
            MockResponse()
                .setResponseCode(304)
                .setHeader("ETag", "\"etag-v1\"")
        )
        // page2.html returns 200 with updated content
        server.enqueue(
            MockResponse()
                .setBody("<html><body>Page 2 v2 Updated</body></html>")
                .setHeader("Content-Type", "text/html")
                .setHeader("ETag", "\"p2-etag-v2\"")
        )

        // Run mirror again with fresh engine instance on same rootDir (simulates app restart or subsequent refresh)
        val refreshEngine = MirrorEngine(client, rootDir)
        val refreshSuccess = refreshEngine.mirror(baseUrl, maxDepth = 1)

        assertTrue("Refresh should succeed", refreshSuccess)

        // Verify conditional headers were sent
        val req1 = server.takeRequest()
        Assert.assertEquals("\"etag-v1\"", req1.getHeader("If-None-Match"))

        val req2 = server.takeRequest()
        Assert.assertEquals("\"p2-etag-v1\"", req2.getHeader("If-None-Match"))

        // Verify page2 was updated and index was preserved
        assertTrue("index.html should still exist", indexFile.exists())
        assertTrue("page2.html should be updated to v2", page2File.readText().contains("Page 2 v2 Updated"))
    }

    @Test
    fun `test mirroring site with subpath index page`() = runBlocking {
        val startUrl = server.url("/docs/guide.html").toString()

        server.enqueue(
            MockResponse()
                .setBody("""
                    <html>
                        <head><title>Guide</title></head>
                        <body>
                            <h1>Docs Guide</h1>
                            <a href="chapter1.html">Chapter 1</a>
                            <a href="/about.html">About</a>
                        </body>
                    </html>
                """.trimIndent())
                .setHeader("Content-Type", "text/html")
        )

        server.enqueue(
            MockResponse()
                .setBody("<html><body>Chapter 1 Content</body></html>")
                .setHeader("Content-Type", "text/html")
        )

        server.enqueue(
            MockResponse()
                .setBody("<html><body>About Us</body></html>")
                .setHeader("Content-Type", "text/html")
        )

        val success = engine.mirror(startUrl, maxDepth = 1)
        assertTrue("Mirroring with subpath should succeed", success)

        val guideFile = engine.getLocalFile(startUrl)
        assertTrue("Subpath file docs/guide.html should exist", guideFile.exists())
        val guideRelative = guideFile.relativeTo(rootDir).path.replace('\\', '/')
        Assert.assertEquals("docs/guide.html", guideRelative)

        val chapter1File = engine.getLocalFile(server.url("/docs/chapter1.html").toString())
        assertTrue("Sibling docs/chapter1.html should exist", chapter1File.exists())

        val aboutFile = engine.getLocalFile(server.url("/about.html").toString())
        assertTrue("Root /about.html should exist", aboutFile.exists())
    }

    @Test
    fun `test progress callback reports failures during download`() = runBlocking {
        val baseUrl = server.url("/").toString()
        val progressList = mutableListOf<MirrorProgress>()
        val engineWithProgress = MirrorEngine(client, rootDir) { progress ->
            progressList.add(progress)
        }

        server.enqueue(
            MockResponse()
                .setBody("""
                    <html>
                        <body>
                            <a href="page2.html">Page 2</a>
                            <img src="missing.png">
                        </body>
                    </html>
                """.trimIndent())
                .setHeader("Content-Type", "text/html")
        )
        // page2.html succeeds
        server.enqueue(
            MockResponse()
                .setBody("<html><body>Page 2</body></html>")
                .setHeader("Content-Type", "text/html")
        )
        // missing.png fails with 404
        server.enqueue(MockResponse().setResponseCode(404))

        val success = engineWithProgress.mirror(baseUrl, maxDepth = 1)
        assertTrue(success)

        val failureReportingProgress = progressList.filter { it.recentFailure != null }
        println("Progress list failures: $failureReportingProgress")
        println("All failedUrls: ${engineWithProgress.failedUrls}")
        assertTrue("Should have recorded at least one recentFailure", failureReportingProgress.isNotEmpty())
        assertTrue(
            "Progress should include recentFailure info for missing.png",
            failureReportingProgress.any { it.recentFailure?.first?.contains("missing.png") == true }
        )
        val lastProgress = progressList.last()
        Assert.assertEquals(1, lastProgress.failedCount)
        assertTrue(
            "Progress should include failedUrls map",
            lastProgress.failedUrls.any { it.key.contains("missing.png") }
        )
    }

    @Test
    fun `test mirroring starting from subpath entry page`() = runBlocking {
        val startUrl = server.url("/docs/guide/index.html").toString()
        val rootPageUrl = server.url("/root.html").toString()

        server.enqueue(MockResponse().setBody("""
            <html>
                <head><title>Docs Guide</title></head>
                <body>
                    <a href="/root.html">Root Page</a>
                    <a href="chapter1.html">Chapter 1</a>
                </body>
            </html>
        """).addHeader("Content-Type", "text/html"))

        server.enqueue(MockResponse().setBody("""
            <html>
                <body><h1>Root</h1></body>
            </html>
        """).addHeader("Content-Type", "text/html"))

        server.enqueue(MockResponse().setBody("""
            <html>
                <body><h1>Chapter 1</h1></body>
            </html>
        """).addHeader("Content-Type", "text/html"))

        val success = engine.mirror(startUrl, maxDepth = 1)
        assertTrue("Mirror should succeed for subpath start URL", success)

        val entryFile = engine.getLocalFile(startUrl)
        assertTrue("Subpath index file should exist", entryFile.exists())
        Assert.assertEquals(File(rootDir, "docs/guide/index.html").absolutePath, entryFile.absolutePath)

        val rootPageFile = engine.getLocalFile(rootPageUrl)
        assertTrue("Root page across host should be mirrored", rootPageFile.exists())
        Assert.assertEquals(File(rootDir, "root.html").absolutePath, rootPageFile.absolutePath)

        val chapter1File = File(rootDir, "docs/guide/chapter1.html")
        assertTrue("Relative chapter 1 page should exist", chapter1File.exists())

        val entryHtml = entryFile.readText()
        assertTrue("Link to root page should be remapped relative to guide", entryHtml.contains("href=\"../../root.html\""))
        assertTrue("Link to chapter 1 should be remapped relative to guide", entryHtml.contains("href=\"chapter1.html\""))
    }

    @Test
    fun `test subpath with redirect and relative links resolution`() = runBlocking {
        // Request to /docs redirects (301) to /docs/
        val startUrl = server.url("/docs").toString()

        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .setHeader("Location", server.url("/docs/").toString())
        )

        // /docs/ contains relative links written for /docs/ base
        server.enqueue(
            MockResponse()
                .setBody("""
                    <html>
                        <head>
                            <base href="${server.url("/docs/")}">
                            <link rel="stylesheet" href="style.css">
                        </head>
                        <body>
                            <a href="page1.html">Page 1</a>
                            <a href="#section">Section</a>
                        </body>
                    </html>
                """.trimIndent())
                .setHeader("Content-Type", "text/html")
        )

        server.enqueue(MockResponse().setBody("body { color: red; }").setHeader("Content-Type", "text/css"))
        server.enqueue(MockResponse().setBody("<html><body>Page 1</body></html>").setHeader("Content-Type", "text/html"))

        val success = engine.mirror(startUrl, maxDepth = 1)
        assertTrue(success)

        val docsIndexFile = File(rootDir, "docs/index.html")
        assertTrue("docs/index.html should exist after following directory redirect", docsIndexFile.exists())

        val styleFile = File(rootDir, "docs/style.css")
        assertTrue("style.css should exist in docs folder, not root", styleFile.exists())

        val page1File = File(rootDir, "docs/page1.html")
        assertTrue("page1.html should exist in docs folder, not root", page1File.exists())

        val htmlContent = docsIndexFile.readText()
        assertTrue("Link to page 1 should be relative to docs", htmlContent.contains("href=\"page1.html\""))
        assertTrue("On-page anchor #section should remain #section", htmlContent.contains("href=\"#section\""))
        assertTrue("Base tag should be removed so local relative paths work offline", !htmlContent.contains("<base"))
    }

    @Test
    fun `test concurrent downloads do not perform duplicate work`() = runBlocking {
        val baseUrl = server.url("/").toString()

        // Page 1 and Page 2 both reference shared.css and each other
        server.enqueue(
            MockResponse()
                .setBody("""
                    <html>
                        <head><link rel="stylesheet" href="shared.css"></head>
                        <body>
                            <a href="page2.html">Page 2</a>
                        </body>
                    </html>
                """.trimIndent())
                .setHeader("Content-Type", "text/html")
        )

        // shared.css (slow response to test concurrency window)
        server.enqueue(
            MockResponse()
                .setBody("body { margin: 0; }")
                .setHeader("Content-Type", "text/css")
        )

        // page2.html
        server.enqueue(
            MockResponse()
                .setBody("""
                    <html>
                        <head><link rel="stylesheet" href="shared.css"></head>
                        <body>Page 2</body>
                    </html>
                """.trimIndent())
                .setHeader("Content-Type", "text/html")
        )

        val success = engine.mirror(baseUrl, maxDepth = 2)
        assertTrue(success)

        val recordedRequests = (1..server.requestCount).map { server.takeRequest().path }
        val sharedCssRequests = recordedRequests.filter { it == "/shared.css" }
        Assert.assertEquals("shared.css should only be requested once despite multiple references", 1, sharedCssRequests.size)
    }
}


