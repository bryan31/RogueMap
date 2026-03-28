package com.yomahub.roguemap.embedding;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

class UniversalEmbeddingProviderTest {

    // ---- Constructor tests ----

    @Test
    void testConstructorApiKeyOnly() {
        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider("sk-test");
        assertEquals("https://api.openai.com/v1", getField(p, "baseUrl"));
        assertEquals("text-embedding-3-small", getField(p, "model"));
        assertEquals(1536, p.getDimension());
    }

    @Test
    void testConstructorApiKeyAndModel() {
        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider("sk-test", "text-embedding-3-large");
        assertEquals(3072, p.getDimension());
        assertEquals("text-embedding-3-large", getField(p, "model"));
    }

    @Test
    void testConstructorFull() {
        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider(
            "https://api.mistral.ai/v1", "key", "mistral-embed", 1024);
        assertEquals("https://api.mistral.ai/v1", getField(p, "baseUrl"));
        assertEquals(1024, p.getDimension());
    }

    @Test
    void testConstructorAutoDetectDimension() {
        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider(
            "http://localhost:8080/v1", "key", "unknown-model", 0);
        assertEquals(0, p.getDimension());
    }

    @Test
    void testBaseUrlTrailingSlashStripped() {
        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider(
            "https://api.openai.com/v1/", "key", "model", 128);
        assertEquals("https://api.openai.com/v1", getField(p, "baseUrl"));
    }

    @Test
    void testChainableTimeouts() {
        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider("key")
            .connectTimeout(5000)
            .readTimeout(15000);
        assertEquals(5000, getField(p, "connectTimeout"));
        assertEquals(15000, getField(p, "readTimeout"));
    }

    // ---- Known models dimension table ----

    @Test
    void testKnownModelDimensions() {
        assertEquals(1536, new UniversalEmbeddingProvider("k", "text-embedding-3-small").getDimension());
        assertEquals(3072, new UniversalEmbeddingProvider("k", "text-embedding-3-large").getDimension());
        assertEquals(1536, new UniversalEmbeddingProvider("k", "text-embedding-ada-002").getDimension());
        assertEquals(1024, new UniversalEmbeddingProvider("k", "mistral-embed").getDimension());
        assertEquals(768, new UniversalEmbeddingProvider("k", "nomic-embed-text").getDimension());
        assertEquals(1024, new UniversalEmbeddingProvider("k", "text-embedding-v3").getDimension());
        assertEquals(2048, new UniversalEmbeddingProvider("k", "embedding-3").getDimension());
        assertEquals(1024, new UniversalEmbeddingProvider("k", "jina-embeddings-v3").getDimension());
        assertEquals(1024, new UniversalEmbeddingProvider("k", "voyage-3").getDimension());
    }

    @Test
    void testUnknownModelReturnsZeroDimension() {
        assertEquals(0, new UniversalEmbeddingProvider("k", "my-custom-model").getDimension());
    }

    // ---- JSON parsing via reflection ----

    @Test
    void testParseEmbeddingFromJson() throws Exception {
        String json = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1,0.2,0.3],\"index\":0}],\"model\":\"test\"}";
        float[] result = invokeParseEmbedding(json);
        assertEquals(3, result.length);
        assertEquals(0.1f, result[0], 0.001f);
        assertEquals(0.2f, result[1], 0.001f);
        assertEquals(0.3f, result[2], 0.001f);
    }

    @Test
    void testParseEmbeddingSingleElement() throws Exception {
        String json = "{\"data\":[{\"embedding\":[0.99],\"index\":0}]}";
        float[] result = invokeParseEmbedding(json);
        assertEquals(1, result.length);
        assertEquals(0.99f, result[0], 0.001f);
    }

    @Test
    void testParseEmbeddingNegativeValues() throws Exception {
        String json = "{\"data\":[{\"embedding\":[-0.5,0.0,0.5],\"index\":0}]}";
        float[] result = invokeParseEmbedding(json);
        assertEquals(3, result.length);
        assertEquals(-0.5f, result[0], 0.001f);
        assertEquals(0.0f, result[1], 0.001f);
        assertEquals(0.5f, result[2], 0.001f);
    }

    @Test
    void testParseEmbeddingLargeVector() throws Exception {
        StringBuilder sb = new StringBuilder("{\"data\":[{\"embedding\":[");
        for (int i = 0; i < 1536; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", Math.sin(i) * 0.5));
        }
        sb.append("],\"index\":0}]}");
        float[] result = invokeParseEmbedding(sb.toString());
        assertEquals(1536, result.length);
    }

    @Test
    void testParseEmbeddingMissingField() throws Exception {
        String json = "{\"data\":[{\"index\":0}]}";
        Exception ex = assertThrows(Exception.class, () -> invokeParseEmbedding(json));
        // Reflection wraps in InvocationTargetException; unwrap to check cause
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        assertTrue(cause instanceof RuntimeException);
        assertTrue(cause.getMessage().contains("Embedding field not found"));
    }

    @Test
    void testParseEmbeddingAutoDetectDimension() throws Exception {
        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider(
            "http://localhost:1/v1", "key", "unknown", 0);
        assertEquals(0, p.getDimension());

        String json = "{\"data\":[{\"embedding\":[0.1,0.2,0.3,0.4],\"index\":0}]}";
        float[] result = invokeParseOnProvider(p, json);
        assertEquals(4, result.length);
        assertEquals(4, p.getDimension());
    }

    // ---- JSON string escaping ----

    @Test
    void testJsonStringEscaping() throws Exception {
        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider("key");
        String result = invokeJsonString(p, "hello\"world\nline2\ttab");
        assertEquals("\"hello\\\"world\\nline2\\ttab\"", result);
    }

    @Test
    void testJsonStringBackslash() throws Exception {
        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider("key");
        String result = invokeJsonString(p, "path\\to\\file");
        assertEquals("\"path\\\\to\\\\file\"", result);
    }

    // ---- HTTP error handling (mock server) ----

    @Test
    void testEmbedApiError() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();

        Thread server = new Thread(() -> {
            try {
                Socket client = ss.accept();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) { /* consume */ }

                String body = "{\"error\":{\"message\":\"Invalid API key\"}}";
                String response = "HTTP/1.1 401 Unauthorized\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: " + body.length() + "\r\n\r\n" + body;
                client.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                client.close();
                ss.close();
            } catch (IOException e) { /* ignore */ }
        });
        server.setDaemon(true);
        server.start();

        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider(
            "http://localhost:" + port + "/v1", "bad-key", "test-model", 3);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> p.embed("hello"));
        assertTrue(ex.getMessage().contains("HTTP 401"));

        server.join(2000);
    }

    @Test
    void testEmbedSuccessWithMockServer() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();

        Thread server = new Thread(() -> {
            try {
                Socket client = ss.accept();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) { /* consume */ }

                String body = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1,0.2,0.3],\"index\":0}],\"model\":\"test-model\",\"usage\":{\"prompt_tokens\":5,\"total_tokens\":5}}";
                String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: " + body.length() + "\r\n\r\n" + body;
                client.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                client.close();
                ss.close();
            } catch (IOException e) { /* ignore */ }
        });
        server.setDaemon(true);
        server.start();

        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider(
            "http://localhost:" + port + "/v1", "test-key", "test-model", 3);
        float[] result = p.embed("hello world");
        assertEquals(3, result.length);
        assertEquals(0.1f, result[0], 0.001f);

        server.join(2000);
    }

    // ---- Reflection helpers ----

    private static Object getField(UniversalEmbeddingProvider p, String field) {
        try {
            java.lang.reflect.Field f = UniversalEmbeddingProvider.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static float[] invokeParseEmbedding(String json) throws Exception {
        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider("key");
        return invokeParseOnProvider(p, json);
    }

    private static float[] invokeParseOnProvider(UniversalEmbeddingProvider p, String json) throws Exception {
        java.lang.reflect.Method m = UniversalEmbeddingProvider.class.getDeclaredMethod("parseEmbeddingFromJson", String.class);
        m.setAccessible(true);
        return (float[]) m.invoke(p, json);
    }

    private static String invokeJsonString(UniversalEmbeddingProvider p, String text) throws Exception {
        java.lang.reflect.Method m = UniversalEmbeddingProvider.class.getDeclaredMethod("jsonString", String.class);
        m.setAccessible(true);
        return (String) m.invoke(p, text);
    }
}
