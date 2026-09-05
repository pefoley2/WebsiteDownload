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

        val result = engine.mirror(server.url("/nonexistent").toString(), maxDepth = 1)

        Assert.assertFalse("Mirror should return false for nonexistent site", result)
        val files = rootDir.listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("No files should be downloaded", files.isEmpty())
    }

    @Test
    fun `test invalid URL returns false`() = runBlocking {
        val result = engine.mirror("invalid-url-schema", maxDepth = 1)
        Assert.assertFalse("Mirror should return false for invalid URL", result)
    }
}
