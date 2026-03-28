package com.yomahub.roguemap.memory.embedding;

import com.yomahub.roguemap.embedding.EmbeddingProvider;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * @deprecated Use {@link com.yomahub.roguemap.embedding.UniversalEmbeddingProvider} instead.
 *             Point it at Ollama's OpenAI-compatible endpoint: http://localhost:11434/v1
 *             This class is retained for backwards compatibility only.
 */
@Deprecated
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private final String baseUrl;
    private final String model;
    private final int dimension;

    public OllamaEmbeddingProvider(String baseUrl, String model, int dimension) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.dimension = dimension;
    }

    @Override
    public float[] embed(String text) {
        try {
            URL url = new URL(baseUrl + "/api/embeddings");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);

            String body = "{\"model\":\"" + model + "\",\"prompt\":" + jsonString(text) + "}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                throw new RuntimeException("Ollama API 返回错误状态码: " + status);
            }

            String response = readResponse(conn.getInputStream());
            return parseEmbeddingFromJson(response);
        } catch (IOException e) {
            throw new RuntimeException("调用 Ollama Embeddings API 失败", e);
        }
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    private String readResponse(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /** 提取 Ollama 响应中的 embedding 数组 */
    private float[] parseEmbeddingFromJson(String json) {
        int start = json.indexOf("\"embedding\":[");
        if (start < 0) throw new RuntimeException("Ollama 响应中未找到 embedding 字段");
        start += "\"embedding\":[".length();
        int end = json.indexOf("]", start);
        String[] parts = json.substring(start, end).split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }

    private String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
