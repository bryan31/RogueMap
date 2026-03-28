package com.yomahub.roguemap.memory.embedding;

import com.yomahub.roguemap.embedding.EmbeddingProvider;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * @deprecated Use {@link com.yomahub.roguemap.embedding.UniversalEmbeddingProvider} instead.
 *             This class is retained for backwards compatibility only.
 */
@Deprecated
public class OpenAIEmbeddingProvider implements EmbeddingProvider {

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int dimension;

    /**
     * @param apiKey    API 密钥
     * @param model     模型名，如 "text-embedding-3-small"（1536维）或 "text-embedding-3-large"（3072维）
     */
    public OpenAIEmbeddingProvider(String apiKey, String model) {
        this(apiKey, model, "https://api.openai.com/v1", dimensionForModel(model));
    }

    /**
     * @param apiKey    API 密钥
     * @param model     模型名
     * @param baseUrl   自定义端点（如 Azure: "https://xxx.openai.azure.com/openai/deployments/xxx"）
     * @param dimension 向量维度（与所选模型一致）
     */
    public OpenAIEmbeddingProvider(String apiKey, String model, String baseUrl, int dimension) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.dimension = dimension;
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
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);

            String body = "{\"model\":\"" + model + "\",\"input\":" + jsonString(text) + "}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                throw new RuntimeException("OpenAI API 返回错误状态码: " + status);
            }

            String response = readResponse(conn.getInputStream());
            return parseEmbeddingFromJson(response);
        } catch (IOException e) {
            throw new RuntimeException("调用 OpenAI Embeddings API 失败", e);
        }
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    private static int dimensionForModel(String model) {
        if (model.contains("3-large")) return 3072;
        if (model.contains("ada-002")) return 1536;
        return 1536; // text-embedding-3-small 默认
    }

    private String readResponse(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /** 简单提取 JSON 中 data[0].embedding 数组，无需 JSON 库 */
    private float[] parseEmbeddingFromJson(String json) {
        int start = json.indexOf("\"embedding\":[");
        if (start < 0) throw new RuntimeException("响应中未找到 embedding 字段");
        start += "\"embedding\":[".length();
        int end = json.indexOf("]", start);
        String[] parts = json.substring(start, end).split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }

    /** 简单 JSON 字符串转义 */
    private String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
