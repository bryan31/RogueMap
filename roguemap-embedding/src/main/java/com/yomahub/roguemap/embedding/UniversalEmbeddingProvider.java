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
 * Zero external dependencies -- uses HttpURLConnection and hand-written JSON parsing.
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
    private volatile int dimension;
    private volatile boolean dimensionDetected = false;
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int readTimeout = DEFAULT_READ_TIMEOUT;

    /**
     * OpenAI default -- only needs apiKey
     */
    public UniversalEmbeddingProvider(String apiKey) {
        this(DEFAULT_BASE_URL, apiKey, DEFAULT_MODEL, dimensionForModel(DEFAULT_MODEL));
    }

    /**
     * Specify model, auto-infer dimension
     */
    public UniversalEmbeddingProvider(String apiKey, String model) {
        this(DEFAULT_BASE_URL, apiKey, model, dimensionForModel(model));
    }

    /**
     * Full constructor -- works with any OpenAI-compatible service
     *
     * @param baseUrl   service endpoint (e.g. "https://api.mistral.ai/v1")
     * @param apiKey    API key
     * @param model     model name (e.g. "mistral-embed")
     * @param dimension vector dimension; pass 0 to auto-detect on first embed() call
     */
    public UniversalEmbeddingProvider(String baseUrl, String apiKey, String model, int dimension) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
        this.dimensionDetected = dimension > 0;
    }

    /** Chainable connect timeout config (ms) */
    public UniversalEmbeddingProvider connectTimeout(int ms) {
        this.connectTimeout = ms;
        return this;
    }

    /** Chainable read timeout config (ms) */
    public UniversalEmbeddingProvider readTimeout(int ms) {
        this.readTimeout = ms;
        return this;
    }

    @Override
    public float[] embed(String text) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/embeddings");
            conn = (HttpURLConnection) url.openConnection();
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
        } finally {
            if (conn != null) conn.disconnect();
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
     */
    private float[] parseEmbeddingFromJson(String json) {
        int start = json.indexOf("\"embedding\":[");
        if (start < 0) {
            throw new RuntimeException("Embedding field not found in API response: " +
                json.substring(0, Math.min(200, json.length())));
        }
        start += "\"embedding\":[".length();

        // Find matching ]
        int depth = 1;
        int end = start;
        while (end < json.length() && depth > 0) {
            char c = json.charAt(end);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            end++;
        }

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

    /** Minimal JSON string escaping */
    private String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }
}
