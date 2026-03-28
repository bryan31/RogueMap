# roguemap-embedding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a new `roguemap-embedding` Maven module providing a single `UniversalEmbeddingProvider` that works with any OpenAI-compatible embedding service.

**Architecture:** Move `EmbeddingProvider` interface from `roguemap-memory` to `roguemap-core`. Create `roguemap-embedding` module with zero extra dependencies, using `HttpURLConnection` for HTTP and hand-written JSON parsing. Mark old providers as `@deprecated`.

**Tech Stack:** Java 8+, `HttpURLConnection`, Maven multi-module

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `roguemap-core/src/main/java/com/yomahub/roguemap/embedding/EmbeddingProvider.java` | SPI interface (moved from memory module) |
| Create | `roguemap-embedding/pom.xml` | Module POM, depends only on roguemap-core |
| Create | `roguemap-embedding/src/main/java/com/yomahub/roguemap/embedding/UniversalEmbeddingProvider.java` | Universal provider implementation |
| Create | `roguemap-embedding/src/test/java/com/yomahub/roguemap/embedding/UniversalEmbeddingProviderTest.java` | Unit tests |
| Modify | `pom.xml` (root) | Add `roguemap-embedding` to `<modules>` |
| Delete | `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/EmbeddingProvider.java` | Interface moved to core |
| Modify | `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/OpenAIEmbeddingProvider.java` | Update import, add `@deprecated` |
| Modify | `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/OllamaEmbeddingProvider.java` | Update import, add `@deprecated` |
| Modify | `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java` | Update import path |
| Modify | `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/MockEmbeddingProvider.java` | Update import path |
| Delete | `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/embedding/EmbeddingProvider.java` | Interface moved to core (if exists) |
| Modify | `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java` | Update import path |
| Modify | `roguemap-memory-pro/src/test/java/com/yomahub/roguemap/memory/MockEmbeddingProvider.java` | Update import path |

---

### Task 1: Move EmbeddingProvider interface to roguemap-core

**Files:**
- Create: `roguemap-core/src/main/java/com/yomahub/roguemap/embedding/EmbeddingProvider.java`
- Delete: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/EmbeddingProvider.java`
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java:3`
- Modify: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/MockEmbeddingProvider.java:3`
- Delete: `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/embedding/EmbeddingProvider.java` (if exists)
- Modify: `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java:3`
- Modify: `roguemap-memory-pro/src/test/java/com/yomahub/roguemap/memory/MockEmbeddingProvider.java:3`

- [ ] **Step 1: Create the interface in roguemap-core**

```java
// file: roguemap-core/src/main/java/com/yomahub/roguemap/embedding/EmbeddingProvider.java
package com.yomahub.roguemap.embedding;

public interface EmbeddingProvider {
    /**
     * Convert text to embedding vector
     * @param text input text
     * @return embedding vector (float array)
     */
    float[] embed(String text);

    /**
     * Return vector dimension, used when building HNSW index
     */
    int getDimension();
}
```

- [ ] **Step 2: Delete old EmbeddingProvider from roguemap-memory**

Delete the file `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/EmbeddingProvider.java`.

- [ ] **Step 3: Update imports in roguemap-memory RogueMemory.java**

Change line 3 from:
```java
import com.yomahub.roguemap.memory.embedding.EmbeddingProvider;
```
to:
```java
import com.yomahub.roguemap.embedding.EmbeddingProvider;
```

- [ ] **Step 4: Update imports in roguemap-memory MockEmbeddingProvider.java**

Change line 3 from:
```java
import com.yomahub.roguemap.memory.embedding.EmbeddingProvider;
```
to:
```java
import com.yomahub.roguemap.embedding.EmbeddingProvider;
```

- [ ] **Step 5: Update roguemap-memory-pro — delete EmbeddingProvider if it exists, update imports**

Check if `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/embedding/EmbeddingProvider.java` exists. If so, delete it.

Update `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java` line 3:
```java
import com.yomahub.roguemap.embedding.EmbeddingProvider;
```

Update `roguemap-memory-pro/src/test/java/com/yomahub/roguemap/memory/MockEmbeddingProvider.java` line 3:
```java
import com.yomahub.roguemap.embedding.EmbeddingProvider;
```

- [ ] **Step 6: Compile all modules to verify**

Run: `mvn clean compile -pl roguemap-core,roguemap-memory,roguemap-memory-pro`
Expected: BUILD SUCCESS

- [ ] **Step 7: Run existing tests to verify nothing broke**

Run: `mvn test -pl roguemap-core,roguemap-memory,roguemap-memory-pro`
Expected: All tests pass

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: move EmbeddingProvider interface from roguemap-memory to roguemap-core"
```

---

### Task 2: Create roguemap-embedding module skeleton

**Files:**
- Create: `roguemap-embedding/pom.xml`
- Modify: `pom.xml` (root) — add module

- [ ] **Step 1: Create roguemap-embedding/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.yomahub</groupId>
        <artifactId>roguemap-parent</artifactId>
        <version>1.1.0</version>
    </parent>

    <artifactId>roguemap-embedding</artifactId>
    <packaging>jar</packaging>
    <name>RogueMap Embedding (Universal Embedding Provider)</name>

    <properties>
        <maven.compiler.source>8</maven.compiler.source>
        <maven.compiler.target>8</maven.compiler.target>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.yomahub</groupId>
            <artifactId>roguemap-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <source>8</source>
                    <target>8</target>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Add module to root pom.xml**

In `pom.xml` root, add `<module>roguemap-embedding</module>` after `roguemap-memory-pro` in the `<modules>` section:

```xml
    <modules>
        <module>roguemap-core</module>
        <module>roguemap-memory</module>
        <module>roguemap-memory-pro</module>
        <module>roguemap-embedding</module>
    </modules>
```

- [ ] **Step 3: Verify module builds**

Run: `mvn clean compile -pl roguemap-embedding`
Expected: BUILD SUCCESS (empty module compiles)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(embedding): create roguemap-embedding module skeleton"
```

---

### Task 3: Implement UniversalEmbeddingProvider

**Files:**
- Create: `roguemap-embedding/src/main/java/com/yomahub/roguemap/embedding/UniversalEmbeddingProvider.java`

- [ ] **Step 1: Write UniversalEmbeddingProvider.java**

```java
package com.yomahub.roguemap.embedding;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Universal EmbeddingProvider that works with any OpenAI /v1/embeddings compatible service.
 *
 * Supports: OpenAI, Mistral, Jina, Voyage, Together, Fireworks, vLLM, LocalAI,
 * Ollama (OpenAI-compat mode), Alibaba DashScope, Zhipu GLM, Moonshot/Kimi, etc.
 *
 * Zero external dependencies — uses HttpURLConnection and hand-written JSON parsing.
 */
public class UniversalEmbeddingProvider implements EmbeddingProvider {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "text-embedding-3-small";
    private static final int DEFAULT_CONNECT_TIMEOUT = 10_000;
    private static final int DEFAULT_READ_TIMEOUT = 30_000;

    private static final Map<String, Integer> KNOWN_MODELS = new HashMap<>();
    static {
        // OpenAI
        KNOWN_MODELS.put("text-embedding-3-small", 1536);
        KNOWN_MODELS.put("text-embedding-3-large", 3072);
        KNOWN_MODELS.put("text-embedding-ada-002", 1536);
        // Mistral
        KNOWN_MODELS.put("mistral-embed", 1024);
        // Nomic (Ollama)
        KNOWN_MODELS.put("nomic-embed-text", 768);
        // Alibaba DashScope
        KNOWN_MODELS.put("text-embedding-v3", 1024);
        KNOWN_MODELS.put("text-embedding-v2", 1536);
        KNOWN_MODELS.put("text-embedding-v1", 1536);
        // Zhipu GLM
        KNOWN_MODELS.put("embedding-3", 2048);
        KNOWN_MODELS.put("embedding-2", 1024);
        // Jina
        KNOWN_MODELS.put("jina-embeddings-v3", 1024);
        KNOWN_MODELS.put("jina-embeddings-v2-base-en", 768);
        // Voyage
        KNOWN_MODELS.put("voyage-3", 1024);
        KNOWN_MODELS.put("voyage-3-lite", 512);
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private int dimension;
    private volatile boolean dimensionDetected = false;
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int readTimeout = DEFAULT_READ_TIMEOUT;

    /**
     * OpenAI default —只需要 apiKey
     */
    public UniversalEmbeddingProvider(String apiKey) {
        this(DEFAULT_BASE_URL, apiKey, DEFAULT_MODEL, dimensionForModel(DEFAULT_MODEL));
    }

    /**
     * 指定 model，自动推断 dimension
     */
    public UniversalEmbeddingProvider(String apiKey, String model) {
        this(DEFAULT_BASE_URL, apiKey, model, dimensionForModel(model));
    }

    /**
     * 完整参数构造 — 适用于任何 OpenAI 兼容服务
     *
     * @param baseUrl   服务端点（如 "https://api.mistral.ai/v1"）
     * @param apiKey    API 密钥
     * @param model     模型名（如 "mistral-embed"）
     * @param dimension 向量维度；传 0 则在首次 embed() 时自动检测
     */
    public UniversalEmbeddingProvider(String baseUrl, String apiKey, String model, int dimension) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
        this.dimensionDetected = dimension > 0;
    }

    /** 链式配置 connectTimeout（毫秒） */
    public UniversalEmbeddingProvider connectTimeout(int ms) {
        this.connectTimeout = ms;
        return this;
    }

    /** 链式配置 readTimeout（毫秒） */
    public UniversalEmbeddingProvider readTimeout(int ms) {
        this.readTimeout = ms;
        return this;
    }

    @Override
    public float[] embed(String text) {
        try {
            URL url = new URL(baseUrl + "/embeddings");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);

            String body = "{\"model\":\"" + model + "\",\"input\":" + jsonString(text) + "}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            String response = readResponse(status >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (status != 200) {
                throw new RuntimeException("Embedding API error (HTTP " + status + "): " + response);
            }

            return parseEmbeddingFromJson(response);
        } catch (IOException e) {
            throw new RuntimeException("Failed to call Embedding API: " + baseUrl + "/embeddings", e);
        }
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    // ---- internal helpers ----

    private static int dimensionForModel(String model) {
        Integer d = KNOWN_MODELS.get(model);
        return d != null ? d : 0; // 0 means auto-detect on first embed()
    }

    private String readResponse(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /**
     * Extract data[0].embedding float array from JSON response.
     * Also auto-detects dimension from array length on first call.
     *
     * Response format:
     * {"object":"list","data":[{"object":"embedding","embedding":[0.1,0.2,...],"index":0}],...}
     */
    private float[] parseEmbeddingFromJson(String json) {
        // Find "embedding":[ ... ]
        int start = json.indexOf("\"embedding\":[");
        if (start < 0) {
            throw new RuntimeException("Embedding field not found in API response: " +
                json.substring(0, Math.min(200, json.length())));
        }
        start += "\"embedding\":[".length();

        // Find matching ] — handle nested arrays (shouldn't occur but be safe)
        int depth = 1;
        int end = start;
        while (end < json.length() && depth > 0) {
            char c = json.charAt(end);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            end++;
        }
        // end now points to one past the closing ]

        String arrayContent = json.substring(start, end - 1);
        String[] parts = arrayContent.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }

        // Auto-detect dimension on first successful call
        if (!dimensionDetected) {
            synchronized (this) {
                if (!dimensionDetected) {
                    this.dimension = vector.length;
                    this.dimensionDetected = true;
                }
            }
        }

        return vector;
    }

    /** Minimal JSON string escaping — handles the most common special characters */
    private String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }
}
```

- [ ] **Step 2: Compile to verify no syntax errors**

Run: `mvn clean compile -pl roguemap-embedding`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add roguemap-embedding/src/main/java/com/yomahub/roguemap/embedding/UniversalEmbeddingProvider.java
git commit -m "feat(embedding): implement UniversalEmbeddingProvider with zero dependencies"
```

---

### Task 4: Write unit tests for UniversalEmbeddingProvider

**Files:**
- Create: `roguemap-embedding/src/test/java/com/yomahub/roguemap/embedding/UniversalEmbeddingProviderTest.java`

- [ ] **Step 1: Write the test file**

```java
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
        // dimension=0 means auto-detect
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

    // ---- JSON parsing ----

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
    void testParseEmbeddingMissingField() {
        String json = "{\"data\":[{\"index\":0}]}";
        RuntimeException ex = assertThrows(RuntimeException.class, () -> invokeParseEmbedding(json));
        assertTrue(ex.getMessage().contains("Embedding field not found"));
    }

    @Test
    void testParseEmbeddingAutoDetectDimension() throws Exception {
        UniversalEmbeddingProvider p = new UniversalEmbeddingProvider(
            "http://localhost:1/v1", "key", "unknown", 0);
        assertEquals(0, p.getDimension());

        // Simulate parse via reflection
        String json = "{\"data\":[{\"embedding\":[0.1,0.2,0.3,0.4],\"index\":0}]}";
        float[] result = invokeParseOnProvider(p, json);
        assertEquals(4, result.length);
        // After parsing, dimension should be auto-detected
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
        // Start a simple mock server that returns 401
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();

        Thread server = new Thread(() -> {
            try {
                Socket client = ss.accept();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                // Read HTTP request
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
        // Create a provider just to call parseEmbeddingFromJson via reflection
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
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `mvn test -pl roguemap-embedding`
Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add roguemap-embedding/src/test/java/com/yomahub/roguemap/embedding/UniversalEmbeddingProviderTest.java
git commit -m "test(embedding): add unit tests for UniversalEmbeddingProvider"
```

---

### Task 5: Mark old providers as @deprecated

**Files:**
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/OpenAIEmbeddingProvider.java`
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/OllamaEmbeddingProvider.java`

- [ ] **Step 1: Update OpenAIEmbeddingProvider.java**

Change line 1-2 (package + new import):
```java
package com.yomahub.roguemap.memory.embedding;

import com.yomahub.roguemap.embedding.EmbeddingProvider;
```

Remove the existing `import` that was already changed in Task 1 (if it still imports the old path, update to new path).

Add `@deprecated` Javadoc to the class:
```java
/**
 * @deprecated Use {@link com.yomahub.roguemap.embedding.UniversalEmbeddingProvider} instead.
 *             This class is retained for backwards compatibility only.
 */
@Deprecated
public class OpenAIEmbeddingProvider implements EmbeddingProvider {
```

- [ ] **Step 2: Update OllamaEmbeddingProvider.java**

Same pattern — update import and add `@deprecated`:
```java
package com.yomahub.roguemap.memory.embedding;

import com.yomahub.roguemap.embedding.EmbeddingProvider;

/**
 * @deprecated Use {@link com.yomahub.roguemap.embedding.UniversalEmbeddingProvider} instead.
 *             Point it at Ollama's OpenAI-compatible endpoint: http://localhost:11434/v1
 *             This class is retained for backwards compatibility only.
 */
@Deprecated
public class OllamaEmbeddingProvider implements EmbeddingProvider {
```

- [ ] **Step 3: Compile to verify**

Run: `mvn clean compile -pl roguemap-memory`
Expected: BUILD SUCCESS (deprecation warnings are fine)

- [ ] **Step 4: Run tests**

Run: `mvn test -pl roguemap-memory`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/OpenAIEmbeddingProvider.java \
        roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/OllamaEmbeddingProvider.java
git commit -m "refactor(embedding): mark old OpenAI/Ollama providers as @deprecated"
```

---

### Task 6: Full build and final verification

- [ ] **Step 1: Run full project build + all tests**

Run: `mvn clean test`
Expected: BUILD SUCCESS, all tests pass across all modules

- [ ] **Step 2: Verify module structure**

Run: `mvn dependency:tree -pl roguemap-embedding`
Expected: Only depends on `roguemap-core`, no transitive dependencies

- [ ] **Step 3: Final commit (if any CLAUDE.md updates needed)**

If the CLAUDE.md needs updating to reflect the new module (it already documents embedding providers), update it now. Otherwise skip.
