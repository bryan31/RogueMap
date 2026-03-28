# roguemap-embedding 模块设计

## 概述

新增 `roguemap-embedding` Maven 模块，提供开箱即用的 `EmbeddingProvider` 实现。基于 OpenAI `/v1/embeddings` 兼容协议，一个类覆盖所有主流 embedding 服务。零依赖，Java 8+。

## 背景

当前 `roguemap-memory` 中有两个 Provider：
- `OpenAIEmbeddingProvider` — 只支持 OpenAI 格式
- `OllamaEmbeddingProvider` — 只支持 Ollama 原生格式

用户每次都要自己实现 `EmbeddingProvider`，或选择上述有限的实现。而现实中，几乎所有 embedding 服务（OpenAI、Mistral、Jina、Voyage、Together、Fireworks、vLLM、LocalAI、Ollama 新版等）都已兼容 OpenAI 的 `/v1/embeddings` 格式。一个通用的 Provider 即可覆盖 95%+ 的场景。

## 变更清单

### 1. `EmbeddingProvider` 接口移到 `roguemap-core`

从 `com.yomahub.roguemap.memory.embedding.EmbeddingProvider` 移到 `com.yomahub.roguemap.embedding.EmbeddingProvider`。

接口不变：
```java
public interface EmbeddingProvider {
    float[] embed(String text);
    int getDimension();
}
```

`roguemap-memory` 和 `roguemap-memory-pro` 中的 `EmbeddingProvider` 改为重新导出（re-export）或直接 import 新包路径。现有 `OpenAIEmbeddingProvider` 和 `OllamaEmbeddingProvider` 保留但标记 `@deprecated`。

### 2. 新增 `roguemap-embedding` 模块

#### pom.xml
- groupId: `com.yomahub`
- artifactId: `roguemap-embedding`
- Java 8+
- 仅依赖 `roguemap-core`（通过它获取 `EmbeddingProvider` 接口）
- 零额外依赖

#### 核心类：`UniversalEmbeddingProvider`

包路径：`com.yomahub.roguemap.embedding.UniversalEmbeddingProvider`

```java
public class UniversalEmbeddingProvider implements EmbeddingProvider {

    // 构造函数
    public UniversalEmbeddingProvider(String apiKey);
    public UniversalEmbeddingProvider(String apiKey, String model);
    public UniversalEmbeddingProvider(String baseUrl, String apiKey, String model, int dimension);

    // 链式配置
    public UniversalEmbeddingProvider connectTimeout(int ms);
    public UniversalEmbeddingProvider readTimeout(int ms);
}
```

#### 构造函数行为

| 构造函数 | baseUrl | model | dimension |
|----------|---------|-------|-----------|
| `(apiKey)` | `https://api.openai.com/v1` | `text-embedding-3-small` | 1536 |
| `(apiKey, model)` | `https://api.openai.com/v1` | 用户指定 | 内置映射 / 自动检测 |
| `(baseUrl, apiKey, model, dimension)` | 用户指定 | 用户指定 | 用户指定 |

#### dimension 自动推断

内置已知模型的默认维度表：
```java
static Map<String, Integer> KNOWN_MODELS = {
    "text-embedding-3-small": 1536,
    "text-embedding-3-large": 3072,
    "text-embedding-ada-002": 1536,
    "mistral-embed": 1024,
    "nomic-embed-text": 768,
    ...
};
```

- 已知模型 → 使用内置维度
- 未知模型 + 构造时未指定 dimension → 首次 `embed()` 时从响应的 `data[0].embedding` 数组长度自动检测，缓存
- 显式指定 dimension → 直接使用

#### HTTP 调用逻辑

```
POST {baseUrl}/embeddings
Headers:
  Authorization: Bearer {apiKey}
  Content-Type: application/json
Body:
  {"model": "{model}", "input": "{text}"}
```

- 使用 `HttpURLConnection`（Java 8 原生，零依赖）
- 默认 connectTimeout=10s, readTimeout=30s，可配置
- 非 200 响应 → 抛出 RuntimeException，包含状态码和响应体

#### JSON 解析

不引入 JSON 库，使用轻量手写解析：
- 定位 `data` 数组中第一个对象的 `embedding` 字段
- 正确处理转义字符和嵌套结构
- 同时提取 `data[0].embedding` 数组长度用于 dimension 自动检测

### 3. 依赖关系

```
roguemap-core (EmbeddingProvider 接口)
    ↑
roguemap-embedding (UniversalEmbeddingProvider, 零额外依赖)
    ↑ (可选)
roguemap-memory (RogueMemory, HnswVectorIndex, 保留旧 Provider 标 @deprecated)
roguemap-memory-pro (RogueMemory, JVectorIndex, 保留旧 Provider 标 @deprecated)
```

`roguemap-memory` / `roguemap-memory-pro` 不依赖 `roguemap-embedding`。用户在项目中同时引入 memory + embedding 模块即可。

### 4. 现有模块变更

#### `roguemap-memory` / `roguemap-memory-pro`

- 删除 `embedding/EmbeddingProvider.java`（接口已移到 core）
- `OpenAIEmbeddingProvider.java` 和 `OllamaEmbeddingProvider.java` 保留，修改 import 路径，添加 `@deprecated` 注解，Javadoc 指向 `UniversalEmbeddingProvider`
- `RogueMemory.java` 中的 import 路径更新

#### `roguemap-core`

- 新增 `com.yomahub.roguemap.embedding.EmbeddingProvider` 接口

### 5. 用户使用示例

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap-embedding</artifactId>
    <version>1.1.0</version>
</dependency>
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap-memory</artifactId>
    <version>1.1.0</version>
</dependency>
```

```java
import com.yomahub.roguemap.embedding.EmbeddingProvider;
import com.yomahub.roguemap.embedding.UniversalEmbeddingProvider;
import com.yomahub.roguemap.memory.RogueMemory;

// OpenAI
EmbeddingProvider provider = new UniversalEmbeddingProvider("sk-xxx");

// Mistral
EmbeddingProvider provider = new UniversalEmbeddingProvider(
    "https://api.mistral.ai/v1", "your-key", "mistral-embed", 1024);

// 本地 Ollama（OpenAI 兼容模式）
EmbeddingProvider provider = new UniversalEmbeddingProvider(
    "http://localhost:11434/v1", "unused", "nomic-embed-text", 768);

RogueMemory mem = RogueMemory.builder()
    .path("data/mem")
    .embeddingProvider(provider)
    .build();
```

### 6. 模块目录结构

```
roguemap-embedding/
├── pom.xml
└── src/
    └── main/java/com/yomahub/roguemap/embedding/
        └── UniversalEmbeddingProvider.java
```

## 不做的事

- 不引入任何第三方依赖（JSON 库、HTTP 客户端等）
- 不做批量化 embed（`embed(List<String>)`），当前接口是单条的，未来可扩展
- 不做重试/熔断逻辑，保持简单
- 不删除旧 Provider，只标记 deprecated
