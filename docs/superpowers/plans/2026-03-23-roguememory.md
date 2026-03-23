# RogueMemory 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 RogueMap 新增 `RogueMemory` 数据结构，支持 AI 记忆的存储与混合搜索（向量 + BM25），拆分为三个 Maven 模块（roguemap-core / roguemap-memory / roguemap-memory-pro）。

**Architecture:** 现有代码迁移到 `roguemap-core`（Java 8+）。`roguemap-memory` 用 jelmerk/hnswlib-core 做向量索引（Java 8+）。`roguemap-memory-pro` 用 datastax/jvector 做向量索引（Java 11+）。两个 memory 模块 API 完全相同，差异只在 VectorIndex 实现。记忆记录（含原始向量）存入 mmap 文件，BM25 倒排索引序列化追加到 mmap 文件尾，HNSW 图序列化到独立 `.hnsw` 文件。

**Tech Stack:** Java 8+ / Java 11+，jelmerk/hnswlib-core:1.2.1，io.github.jbellis:jvector:3.0.1，sun.misc.Unsafe（已有），JUnit 5

**Spec:** `docs/superpowers/specs/2026-03-23-roguememory-design.md`

---

## 文件清单

### 修改的文件
- `pom.xml` → 改为 parent pom，添加三个子模块
- `src/main/java/com/yomahub/roguemap/storage/MmapFileHeader.java` → 迁移到 roguemap-core，新增 MEMORY 类型和头部字段

### 新建目录结构
```
roguemap-core/
  pom.xml
  src/   ← 现有 src/ 全部迁移至此

roguemap-memory/
  pom.xml
  src/main/java/com/yomahub/roguemap/memory/
    RogueMemory.java
    MemoryEntry.java
    MemoryResult.java
    SearchOptions.java
    SearchMode.java
    embedding/
      EmbeddingProvider.java
      OpenAIEmbeddingProvider.java
      OllamaEmbeddingProvider.java
    index/
      VectorIndex.java
      HnswVectorIndex.java          ← jelmerk 实现
      BM25Index.java
    util/
      Tokenizer.java
  src/test/java/com/yomahub/roguemap/memory/
    TokenizerTest.java
    BM25IndexTest.java
    HnswVectorIndexTest.java
    RogueMemoryFunctionalTest.java
    RogueMemoryPersistenceTest.java
    RogueMemorySearchTest.java

roguemap-memory-pro/
  pom.xml
  src/main/java/com/yomahub/roguemap/memory/
    RogueMemory.java                ← 与 roguemap-memory 相同
    MemoryEntry.java
    MemoryResult.java
    SearchOptions.java
    SearchMode.java
    embedding/  ← 与 roguemap-memory 相同
    index/
      VectorIndex.java
      JVectorIndex.java             ← jvector 实现（唯一差异）
      BM25Index.java
    util/
      Tokenizer.java
  src/test/java/com/yomahub/roguemap/memory/
    JVectorIndexTest.java
    RogueMemoryProFunctionalTest.java
```

---

## Task 1：Maven 多模块重构

**Files:**
- Modify: `pom.xml`
- Create: `roguemap-core/pom.xml`
- Move: `src/` → `roguemap-core/src/`

- [ ] **Step 1.1：备份并修改根 pom.xml 为 parent pom**

将现有 `pom.xml` 修改为如下结构（保留 licenses/developers/scm/profiles 不变，去掉 `<packaging>jar</packaging>` 和 `<dependencies>` 块，改为 parent pom）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.yomahub</groupId>
    <artifactId>roguemap-parent</artifactId>
    <version>1.1.0</version>
    <packaging>pom</packaging>

    <name>RogueMap Parent</name>
    <description>High-performance off-heap and persistent storage for Java</description>
    <url>https://github.com/bryan31/RogueMap</url>

    <!-- 保留现有 licenses / developers / scm / issueManagement 不变 -->

    <modules>
        <module>roguemap-core</module>
        <module>roguemap-memory</module>
        <module>roguemap-memory-pro</module>
    </modules>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>5.10.1</junit.version>
        <slf4j.version>2.0.9</slf4j.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId>
                <version>${junit.version}</version>
                <scope>test</scope>
            </dependency>
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-api</artifactId>
                <version>${slf4j.version}</version>
                <optional>true</optional>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-jar-plugin</artifactId>
                    <version>3.3.0</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>

    <!-- 保留现有 profiles（java9plus、release）不变 -->
</project>
```

- [ ] **Step 1.2：创建 roguemap-core/pom.xml**

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

    <artifactId>roguemap-core</artifactId>
    <packaging>jar</packaging>
    <name>RogueMap Core</name>

    <properties>
        <maven.compiler.source>8</maven.compiler.source>
        <maven.compiler.target>8</maven.compiler.target>
        <jmh.version>1.37</jmh.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>com.esotericsoftware</groupId>
            <artifactId>kryo</artifactId>
            <version>5.6.2</version>
            <optional>true</optional>
        </dependency>
        <!-- 以下为测试依赖，保留原有 mapdb/caffeine/fastutil/jmh -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mapdb</groupId>
            <artifactId>mapdb</artifactId>
            <version>3.1.0</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
            <version>2.9.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>it.unimi.dsi</groupId>
            <artifactId>fastutil</artifactId>
            <version>8.5.15</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.openjdk.jmh</groupId>
            <artifactId>jmh-core</artifactId>
            <version>${jmh.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.openjdk.jmh</groupId>
            <artifactId>jmh-generator-annprocess</artifactId>
            <version>${jmh.version}</version>
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
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Automatic-Module-Name>com.yomahub.roguemap</Automatic-Module-Name>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 1.3：迁移现有源码**

```bash
mkdir -p roguemap-core
mv src roguemap-core/src
```

- [ ] **Step 1.4：验证 roguemap-core 编译和测试全部通过**

```bash
mvn clean test -pl roguemap-core
```
期望输出：`BUILD SUCCESS`，218 个测试全部通过。

- [ ] **Step 1.5：提交**

```bash
git add -A
git commit -m "refactor: 将单模块重构为多模块 Maven 项目，现有代码迁移至 roguemap-core"
```

---

## Task 2：MmapFileHeader 扩展

**Files:**
- Modify: `roguemap-core/src/main/java/com/yomahub/roguemap/storage/MmapFileHeader.java`

> **注意：** 新字段写在 Reserved 区域（offset 112+），不影响 CRC32 覆盖范围（bytes 0-47），不破坏现有格式兼容性。

- [ ] **Step 2.1：在 MmapFileHeader.java 中新增常量和字段**

在现有常量区（`LIST_EXPIRE_TIME_POS = 96` 之后）新增：

```java
// ===== RogueMemory 扩展字段（bytes 112-127）=====
public static final int DATA_TYPE_MEMORY = 5;                   // RogueMemory
public static final int MEMORY_BM25_INDEX_OFFSET_POS = 112;    // BM25 倒排索引在文件中的偏移量（8 bytes）
public static final int MEMORY_HNSW_GENERATION_POS = 120;      // HNSW 文件的 generation 号（8 bytes），用于一致性校验
```

新增对应的 getter/setter：

```java
public long getBm25IndexOffset() {
    return UnsafeOps.getLong(baseAddress + MEMORY_BM25_INDEX_OFFSET_POS);
}

public void setBm25IndexOffset(long offset) {
    UnsafeOps.putLong(baseAddress + MEMORY_BM25_INDEX_OFFSET_POS, offset);
}

public long getHnswGeneration() {
    return UnsafeOps.getLong(baseAddress + MEMORY_HNSW_GENERATION_POS);
}

public void setHnswGeneration(long generation) {
    UnsafeOps.putLong(baseAddress + MEMORY_HNSW_GENERATION_POS, generation);
}
```

- [ ] **Step 2.2：运行现有测试，确保无回归**

```bash
mvn test -pl roguemap-core
```
期望：BUILD SUCCESS，218 个测试通过。

- [ ] **Step 2.3：提交**

```bash
git add roguemap-core/src/main/java/com/yomahub/roguemap/storage/MmapFileHeader.java
git commit -m "feat(core): MmapFileHeader 新增 MEMORY 数据类型及 BM25/HNSW generation 字段"
```

---

## Task 3：创建 roguemap-memory 模块骨架和数据类

**Files:**
- Create: `roguemap-memory/pom.xml`
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/SearchMode.java`
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/MemoryEntry.java`
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/MemoryResult.java`
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/SearchOptions.java`
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/EmbeddingProvider.java`

- [ ] **Step 3.1：创建 roguemap-memory/pom.xml**

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

    <artifactId>roguemap-memory</artifactId>
    <packaging>jar</packaging>
    <name>RogueMap Memory (Java 8+)</name>

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
            <groupId>com.github.jelmerk</groupId>
            <artifactId>hnswlib-core</artifactId>
            <version>1.2.1</version>
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

- [ ] **Step 3.2：创建目录结构**

```bash
mkdir -p roguemap-memory/src/main/java/com/yomahub/roguemap/memory/{embedding,index,util}
mkdir -p roguemap-memory/src/test/java/com/yomahub/roguemap/memory
```

- [ ] **Step 3.3：创建 SearchMode.java**

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/SearchMode.java
package com.yomahub.roguemap.memory;

public enum SearchMode {
    /** 向量搜索 + BM25 关键词搜索，RRF 合并，效果最佳（默认） */
    HYBRID,
    /** 仅向量搜索，需要 EmbeddingProvider */
    VECTOR_ONLY,
    /** 仅 BM25 关键词搜索，不需要 EmbeddingProvider */
    KEYWORD_ONLY
}
```

- [ ] **Step 3.4：创建 MemoryEntry.java**

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/MemoryEntry.java
package com.yomahub.roguemap.memory;

import java.util.Map;

/** 一条记忆的完整数据，包括原始向量（内部使用） */
public class MemoryEntry {
    private final String id;
    private final String content;
    private final Map<String, String> metadata;
    private final String namespace;
    private final long createdAt;
    private final long expireTime;    // 0 = 永不过期
    private final float[] vector;     // 原始 embedding，可为 null（KEYWORD_ONLY 模式）

    public MemoryEntry(String id, String content, Map<String, String> metadata,
                       String namespace, long createdAt, long expireTime, float[] vector) {
        this.id = id;
        this.content = content;
        this.metadata = metadata;
        this.namespace = namespace;
        this.createdAt = createdAt;
        this.expireTime = expireTime;
        this.vector = vector;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public Map<String, String> getMetadata() { return metadata; }
    public String getNamespace() { return namespace; }
    public long getCreatedAt() { return createdAt; }
    public long getExpireTime() { return expireTime; }
    public float[] getVector() { return vector; }

    public boolean isExpired() {
        return expireTime > 0 && System.currentTimeMillis() > expireTime;
    }
}
```

- [ ] **Step 3.5：创建 MemoryResult.java**

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/MemoryResult.java
package com.yomahub.roguemap.memory;

import java.util.Map;

/** 搜索结果 */
public class MemoryResult {
    private final String id;
    private final String content;
    private final Map<String, String> metadata;
    private final String namespace;
    private final float score;
    private final long createdAt;
    private final long expireTime;

    public MemoryResult(String id, String content, Map<String, String> metadata,
                        String namespace, float score, long createdAt, long expireTime) {
        this.id = id;
        this.content = content;
        this.metadata = metadata;
        this.namespace = namespace;
        this.score = score;
        this.createdAt = createdAt;
        this.expireTime = expireTime;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public Map<String, String> getMetadata() { return metadata; }
    public String getNamespace() { return namespace; }
    public float getScore() { return score; }
    public long getCreatedAt() { return createdAt; }
    public long getExpireTime() { return expireTime; }

    @Override
    public String toString() {
        return "MemoryResult{id='" + id + "', score=" + score + ", content='" + content + "'}";
    }
}
```

- [ ] **Step 3.6：创建 SearchOptions.java**

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/SearchOptions.java
package com.yomahub.roguemap.memory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SearchOptions {
    private final String namespace;                  // null = 搜索所有 namespace
    private final Map<String, String> filters;       // metadata 精确过滤，空 map = 不过滤
    private final int rrfConstant;                   // RRF 公式中的 C 值，默认 60

    private SearchOptions(Builder b) {
        this.namespace = b.namespace;
        this.filters = Collections.unmodifiableMap(b.filters);
        this.rrfConstant = b.rrfConstant;
    }

    public String getNamespace() { return namespace; }
    public Map<String, String> getFilters() { return filters; }
    public int getRrfConstant() { return rrfConstant; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String namespace = null;
        private final Map<String, String> filters = new HashMap<>();
        private int rrfConstant = 60;

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder filter(String key, String value) {
            this.filters.put(key, value);
            return this;
        }

        public Builder rrfConstant(int c) {
            this.rrfConstant = c;
            return this;
        }

        public SearchOptions build() { return new SearchOptions(this); }
    }
}
```

- [ ] **Step 3.7：创建 EmbeddingProvider.java 接口**

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/EmbeddingProvider.java
package com.yomahub.roguemap.memory.embedding;

public interface EmbeddingProvider {
    /**
     * 将文本转换为向量
     * @param text 输入文本
     * @return 向量（float 数组）
     */
    float[] embed(String text);

    /**
     * 返回向量维度，构建 HNSW 索引时使用
     */
    int getDimension();
}
```

- [ ] **Step 3.8：编译验证**

```bash
mvn compile -pl roguemap-memory
```
期望：BUILD SUCCESS

- [ ] **Step 3.9：提交**

```bash
git add roguemap-memory/
git commit -m "feat(memory): 创建 roguemap-memory 模块骨架，添加 SearchMode/MemoryEntry/MemoryResult/SearchOptions/EmbeddingProvider"
```

---

## Task 4：Tokenizer（含测试）

**Files:**
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/util/Tokenizer.java`
- Create: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/TokenizerTest.java`

- [ ] **Step 4.1：写失败测试**

```java
// roguemap-memory/src/test/java/com/yomahub/roguemap/memory/TokenizerTest.java
package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.memory.util.Tokenizer;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TokenizerTest {

    @Test
    void chineseTextUsesBigram() {
        List<String> tokens = Tokenizer.tokenize("我有一件红衣服");
        assertEquals(List.of("我有", "有一", "一件", "件红", "红衣", "衣服"), tokens);
    }

    @Test
    void englishTextUsesWhitespaceSplit() {
        List<String> tokens = Tokenizer.tokenize("red dress shirt");
        assertEquals(List.of("red", "dress", "shirt"), tokens);
    }

    @Test
    void mixedTextWithMajorityCjkUsesBigram() {
        // "用户 John" 中 CJK 字符占比 > 50%
        List<String> tokens = Tokenizer.tokenize("用户John");
        assertTrue(tokens.contains("用户"));
    }

    @Test
    void singleCharChineseReturnsEmpty() {
        List<String> tokens = Tokenizer.tokenize("我");
        assertTrue(tokens.isEmpty());
    }

    @Test
    void emptyTextReturnsEmpty() {
        assertTrue(Tokenizer.tokenize("").isEmpty());
        assertTrue(Tokenizer.tokenize(null).isEmpty());
    }

    @Test
    void englishTokensAreLowercased() {
        List<String> tokens = Tokenizer.tokenize("Red Dress");
        assertEquals(List.of("red", "dress"), tokens);
    }
}
```

- [ ] **Step 4.2：运行，确认失败**

```bash
mvn test -pl roguemap-memory -Dtest=TokenizerTest
```
期望：编译失败（Tokenizer 不存在）。

- [ ] **Step 4.3：实现 Tokenizer.java**

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/util/Tokenizer.java
package com.yomahub.roguemap.memory.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Tokenizer {

    private Tokenizer() {}

    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        if (isMajorityCjk(text)) {
            return bigramTokenize(text);
        }
        return whitespaceTokenize(text);
    }

    /** 统计 CJK 字符占比是否 > 50% */
    static boolean isMajorityCjk(String text) {
        int total = 0, cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                total++;
                if (isCjk(c)) cjk++;
            }
        }
        return total > 0 && (double) cjk / total > 0.5;
    }

    static boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')   // CJK 统一汉字
            || (c >= '\u3400' && c <= '\u4DBF')   // 扩展 A
            || (c >= '\u20000' && c <= '\u2A6DF') // 扩展 B（实际上 char 范围外，保留逻辑）
            || (c >= '\uF900' && c <= '\uFAFF')   // CJK 兼容汉字
            || (c >= '\u3040' && c <= '\u309F')   // 平假名
            || (c >= '\u30A0' && c <= '\u30FF');  // 片假名
    }

    /** 双字 Bigram 分词 */
    static List<String> bigramTokenize(String text) {
        // 先去掉空白字符
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (!Character.isWhitespace(c)) sb.append(c);
        }
        String clean = sb.toString();

        List<String> tokens = new ArrayList<>();
        for (int i = 0; i + 1 < clean.length(); i++) {
            tokens.add(clean.substring(i, i + 2));
        }
        return tokens;
    }

    /** 空格分词并转小写 */
    static List<String> whitespaceTokenize(String text) {
        String[] parts = text.trim().split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part.toLowerCase());
            }
        }
        return tokens;
    }
}
```

- [ ] **Step 4.4：运行测试，确认通过**

```bash
mvn test -pl roguemap-memory -Dtest=TokenizerTest
```
期望：6 个测试全部 PASS。

- [ ] **Step 4.5：提交**

```bash
git add roguemap-memory/src/
git commit -m "feat(memory): 实现 Tokenizer，支持中文 Bigram 和英文空格分词"
```

---

## Task 5：BM25Index（含测试）

**Files:**
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/BM25Index.java`
- Create: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/BM25IndexTest.java`

- [ ] **Step 5.1：写失败测试**

```java
// roguemap-memory/src/test/java/com/yomahub/roguemap/memory/BM25IndexTest.java
package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.memory.index.BM25Index;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BM25IndexTest {

    private BM25Index index;

    @BeforeEach
    void setUp() {
        index = new BM25Index(1.2f, 0.75f);
    }

    @Test
    void addAndSearchBasic() {
        index.add("doc1", "我有一件红衣服");
        index.add("doc2", "今天天气很好");
        index.add("doc3", "我喜欢穿衣服");

        List<BM25Index.ScoredId> results = index.search(
            Arrays.asList("红衣", "衣服"), 5);

        assertFalse(results.isEmpty());
        // doc1 和 doc3 都包含"衣服"相关 bigram，应排在前面
        assertEquals("doc1", results.get(0).id);
    }

    @Test
    void deleteRemovesFromResults() {
        index.add("doc1", "我有一件红衣服");
        index.add("doc2", "红色的衣服真好看");
        index.delete("doc1");

        List<BM25Index.ScoredId> results = index.search(
            Arrays.asList("红衣", "衣服"), 5);

        assertTrue(results.stream().noneMatch(r -> r.id.equals("doc1")));
        assertTrue(results.stream().anyMatch(r -> r.id.equals("doc2")));
    }

    @Test
    void searchReturnsEmptyWhenNoMatch() {
        index.add("doc1", "今天天气很好");
        List<BM25Index.ScoredId> results = index.search(
            Arrays.asList("red", "dress"), 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void emptyIndexReturnsEmpty() {
        List<BM25Index.ScoredId> results = index.search(
            Arrays.asList("test"), 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void serializeAndDeserialize() throws Exception {
        index.add("doc1", "我有一件红衣服");
        index.add("doc2", "今天天气很好");

        byte[] serialized = index.serialize();
        BM25Index restored = BM25Index.deserialize(serialized, 1.2f, 0.75f);

        List<BM25Index.ScoredId> results = restored.search(
            Arrays.asList("红衣", "衣服"), 5);
        assertFalse(results.isEmpty());
        assertEquals("doc1", results.get(0).id);
    }

    @Test
    void topKRespected() {
        for (int i = 0; i < 10; i++) {
            index.add("doc" + i, "衣服 红色 好看 " + i);
        }
        List<BM25Index.ScoredId> results = index.search(
            Arrays.asList("衣服"), 3);
        assertEquals(3, results.size());
    }
}
```

- [ ] **Step 5.2：运行，确认失败**

```bash
mvn test -pl roguemap-memory -Dtest=BM25IndexTest
```
期望：编译失败（BM25Index 不存在）。

- [ ] **Step 5.3：实现 BM25Index.java**

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/BM25Index.java
package com.yomahub.roguemap.memory.index;

import com.yomahub.roguemap.memory.util.Tokenizer;

import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BM25 倒排索引实现
 *
 * 支持增量 add/delete，close() 时可序列化持久化。
 * 线程安全（ReentrantReadWriteLock 保护写操作）。
 */
public class BM25Index {

    public static class ScoredId {
        public final String id;
        public final float score;
        public ScoredId(String id, float score) {
            this.id = id;
            this.score = score;
        }
    }

    private final float k1;
    private final float b;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // term → { docId → tf }
    private final Map<String, Map<String, Integer>> invertedIndex = new HashMap<>();
    // docId → 文档词项数量（用于计算文档长度）
    private final Map<String, Integer> docLengths = new HashMap<>();
    // term → 包含该词项的文档数（df）
    private final Map<String, Integer> docFreqs = new HashMap<>();

    public BM25Index(float k1, float b) {
        this.k1 = k1;
        this.b = b;
    }

    public void add(String docId, String content) {
        List<String> tokens = Tokenizer.tokenize(content);
        if (tokens.isEmpty()) return;

        lock.writeLock().lock();
        try {
            // 先删除旧数据（支持 update）
            removeFromIndex(docId);

            // 统计词频
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }

            // 更新倒排索引
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                String term = e.getKey();
                invertedIndex.computeIfAbsent(term, k -> new HashMap<>()).put(docId, e.getValue());
                docFreqs.merge(term, 1, Integer::sum);
            }

            docLengths.put(docId, tokens.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void delete(String docId) {
        lock.writeLock().lock();
        try {
            removeFromIndex(docId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void removeFromIndex(String docId) {
        if (!docLengths.containsKey(docId)) return;

        for (Map.Entry<String, Map<String, Integer>> e : invertedIndex.entrySet()) {
            if (e.getValue().remove(docId) != null) {
                docFreqs.merge(e.getKey(), -1, Integer::sum);
            }
        }
        docLengths.remove(docId);
    }

    /**
     * BM25 搜索，返回按分数降序排列的 top-k 结果
     */
    public List<ScoredId> search(List<String> queryTokens, int topK) {
        lock.readLock().lock();
        try {
            int N = docLengths.size();
            if (N == 0 || queryTokens.isEmpty()) return Collections.emptyList();

            double avgDl = docLengths.values().stream()
                .mapToInt(Integer::intValue).average().orElse(1.0);

            Map<String, Double> scores = new HashMap<>();

            for (String term : queryTokens) {
                Map<String, Integer> postings = invertedIndex.get(term);
                if (postings == null) continue;

                int df = docFreqs.getOrDefault(term, 0);
                double idf = Math.log((N - df + 0.5) / (df + 0.5) + 1);

                for (Map.Entry<String, Integer> posting : postings.entrySet()) {
                    String docId = posting.getKey();
                    int tf = posting.getValue();
                    int dl = docLengths.getOrDefault(docId, 1);

                    double tfNorm = (tf * (k1 + 1))
                        / (tf + k1 * (1 - b + b * dl / avgDl));
                    scores.merge(docId, idf * tfNorm, Double::sum);
                }
            }

            // 取 top-k
            List<ScoredId> result = new ArrayList<>(scores.size());
            for (Map.Entry<String, Double> e : scores.entrySet()) {
                result.add(new ScoredId(e.getKey(), e.getValue().floatValue()));
            }
            result.sort((a, x) -> Float.compare(x.score, a.score));
            return result.subList(0, Math.min(topK, result.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 序列化为字节数组（用于持久化到 mmap 文件尾） */
    public byte[] serialize() throws IOException {
        lock.readLock().lock();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(invertedIndex);
            oos.writeObject(docLengths);
            oos.writeObject(docFreqs);
            oos.flush();
            return baos.toByteArray();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 从字节数组反序列化 */
    @SuppressWarnings("unchecked")
    public static BM25Index deserialize(byte[] data, float k1, float b) throws IOException, ClassNotFoundException {
        BM25Index idx = new BM25Index(k1, b);
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            idx.invertedIndex.putAll((Map<String, Map<String, Integer>>) ois.readObject());
            idx.docLengths.putAll((Map<String, Integer>) ois.readObject());
            idx.docFreqs.putAll((Map<String, Integer>) ois.readObject());
        }
        return idx;
    }
}
```

- [ ] **Step 5.4：运行测试，确认通过**

```bash
mvn test -pl roguemap-memory -Dtest=BM25IndexTest
```
期望：6 个测试全部 PASS。

- [ ] **Step 5.5：提交**

```bash
git add roguemap-memory/src/
git commit -m "feat(memory): 实现 BM25Index，支持 add/delete/search/序列化"
```

---

## Task 6：VectorIndex 接口 + HnswVectorIndex（含测试）

**Files:**
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/VectorIndex.java`
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/HnswVectorIndex.java`
- Create: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/HnswVectorIndexTest.java`

- [ ] **Step 6.1：创建 VectorIndex.java 接口**

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/VectorIndex.java
package com.yomahub.roguemap.memory.index;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public interface VectorIndex {

    class ScoredId {
        public final String id;
        public final float score;
        public ScoredId(String id, float score) {
            this.id = id;
            this.score = score;
        }
    }

    /** 添加向量 */
    void add(String id, float[] vector);

    /** 标记删除（tombstone；compact 时物理移除） */
    void markDeleted(String id);

    /**
     * 近似最近邻搜索，返回不包含已删除节点的结果
     * @param queryVector 查询向量
     * @param topK 返回结果数量
     */
    List<ScoredId> search(float[] queryVector, int topK);

    /**
     * 序列化到输出流。
     * 格式：[generation: 8 bytes long][deletedCount: 4 bytes int]
     *       [deletedId_1_len: 2 bytes][deletedId_1: UTF-8]...
     *       [hnswData: 剩余字节，由具体实现写入]
     */
    void serialize(OutputStream out) throws IOException;

    void close();
}
```

> **注意：** `load()` 不放在接口中（接口静态方法无法被子类覆盖）。各实现类（`HnswVectorIndex`、`JVectorIndex`）各自提供静态 `load(InputStream in, int dimension)` 方法，调用方直接使用具体类型加载。

- [ ] **Step 6.2：写失败测试**

```java
// roguemap-memory/src/test/java/com/yomahub/roguemap/memory/HnswVectorIndexTest.java
package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.memory.index.HnswVectorIndex;
import com.yomahub.roguemap.memory.index.VectorIndex;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HnswVectorIndexTest {

    static float[] vec(float... v) { return v; }

    @Test
    void addAndSearch() {
        HnswVectorIndex index = new HnswVectorIndex(3, 1000);
        index.add("a", vec(1f, 0f, 0f));
        index.add("b", vec(0f, 1f, 0f));
        index.add("c", vec(0f, 0f, 1f));

        // 查询与 a 最近的向量
        List<VectorIndex.ScoredId> results = index.search(vec(1f, 0f, 0f), 1);
        assertEquals(1, results.size());
        assertEquals("a", results.get(0).id);
    }

    @Test
    void markDeletedExcludesFromResults() {
        HnswVectorIndex index = new HnswVectorIndex(3, 1000);
        index.add("a", vec(1f, 0f, 0f));
        index.add("b", vec(0.99f, 0.01f, 0f));  // 非常接近 a
        index.markDeleted("a");

        List<VectorIndex.ScoredId> results = index.search(vec(1f, 0f, 0f), 2);
        assertTrue(results.stream().noneMatch(r -> r.id.equals("a")));
        assertTrue(results.stream().anyMatch(r -> r.id.equals("b")));
    }

    @Test
    void serializeAndDeserialize() throws IOException {
        HnswVectorIndex index = new HnswVectorIndex(3, 1000);
        index.add("a", vec(1f, 0f, 0f));
        index.add("b", vec(0f, 1f, 0f));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        index.serialize(baos);

        HnswVectorIndex restored = HnswVectorIndex.load(
            new ByteArrayInputStream(baos.toByteArray()), 3);

        List<VectorIndex.ScoredId> results = restored.search(vec(1f, 0f, 0f), 1);
        assertEquals("a", results.get(0).id);
    }
}
```

- [ ] **Step 6.3：运行，确认失败**

```bash
mvn test -pl roguemap-memory -Dtest=HnswVectorIndexTest
```
期望：编译失败（HnswVectorIndex 不存在）。

- [ ] **Step 6.4：实现 HnswVectorIndex.java**

jelmerk/hnswlib-core 的核心 API：
- `HnswIndex.newBuilder(dimension, distFn, maxElements).withM(16).withEf(50).build()`
- `index.add(item)`（item 实现 `Item<String, float[]>` 接口）
- `index.findNearest(vector, k)` → `List<SearchResult<Item, Float>>`
- `index.save(outputStream)` / `HnswIndex.load(inputStream)`

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/HnswVectorIndex.java
package com.yomahub.roguemap.memory.index;

import com.github.jelmerk.knn.DistanceFunctions;
import com.github.jelmerk.knn.Item;
import com.github.jelmerk.knn.SearchResult;
import com.github.jelmerk.knn.hnsw.HnswIndex;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HnswVectorIndex implements VectorIndex {

    private final int dimension;
    private HnswIndex<String, float[], VectorItem, Float> hnswIndex;
    // tombstone 集合，markDeleted 后加入此集合，search 时后过滤
    private final Set<String> deletedIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public HnswVectorIndex(int dimension, int maxElements) {
        this.dimension = dimension;
        this.hnswIndex = HnswIndex
            .newBuilder(dimension, DistanceFunctions.FLOAT_COSINE_DISTANCE, maxElements)
            .withM(16)
            .withEfConstruction(200)
            .withEf(50)
            .build();
    }

    // 私有构造，用于反序列化
    private HnswVectorIndex(int dimension,
                             HnswIndex<String, float[], VectorItem, Float> hnswIndex,
                             Set<String> deletedIds) {
        this.dimension = dimension;
        this.hnswIndex = hnswIndex;
        this.deletedIds.addAll(deletedIds);
    }

    @Override
    public void add(String id, float[] vector) {
        try {
            hnswIndex.add(new VectorItem(id, vector));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("向量插入被中断", e);
        }
    }

    @Override
    public void markDeleted(String id) {
        deletedIds.add(id);
    }

    @Override
    public List<ScoredId> search(float[] queryVector, int topK) {
        // 多取一些候选，再过滤 tombstone
        int candidates = topK + deletedIds.size() + 10;
        List<SearchResult<VectorItem, Float>> raw = hnswIndex.findNearest(queryVector, candidates);

        List<ScoredId> result = new ArrayList<>();
        for (SearchResult<VectorItem, Float> r : raw) {
            if (!deletedIds.contains(r.item().id())) {
                // jelmerk 返回的 distance（越小越近），转换为 score（越大越好）
                result.add(new ScoredId(r.item().id(), 1f - r.distance()));
                if (result.size() >= topK) break;
            }
        }
        return result;
    }

    @Override
    public void serialize(OutputStream out) throws IOException {
        // 格式：[generation: 8B][deletedCount: 4B][id_len: 2B][id bytes]... [hnswData]
        // 不使用 ObjectOutputStream，避免与 hnswIndex.save() 的流格式冲突
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeLong(0L);  // generation 由 RogueMemory 在文件头管理，此处写占位 0
        dos.writeInt(deletedIds.size());
        for (String id : deletedIds) {
            byte[] idBytes = id.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            dos.writeShort(idBytes.length);
            dos.write(idBytes);
        }
        dos.flush();
        // hnswIndex.save() 直接写到底层 OutputStream，不包装新的流头
        hnswIndex.save(out);
    }

    public static HnswVectorIndex load(InputStream in, int dimension) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        dis.readLong();   // 跳过 generation 占位
        int deletedCount = dis.readInt();
        Set<String> deletedSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (int i = 0; i < deletedCount; i++) {
            int len = dis.readShort() & 0xFFFF;
            byte[] idBytes = new byte[len];
            dis.readFully(idBytes);
            deletedSet.add(new String(idBytes, java.nio.charset.StandardCharsets.UTF_8));
        }
        // 现在流位置恰好在 hnswlib 数据开始处
        HnswIndex<String, float[], VectorItem, Float> loaded = HnswIndex.load(in);
        return new HnswVectorIndex(dimension, loaded, deletedSet);
    }

    @Override
    public void close() {
        // jelmerk 不需要显式关闭
    }

    /** jelmerk Item 实现 */
    static class VectorItem implements Item<String, float[]>, Serializable {
        private final String id;
        private final float[] vector;

        VectorItem(String id, float[] vector) {
            this.id = id;
            this.vector = vector;
        }

        @Override public String id() { return id; }
        @Override public float[] vector() { return vector; }
        @Override public int dimensions() { return vector.length; }
    }
}
```

- [ ] **Step 6.5：运行测试，确认通过**

```bash
mvn test -pl roguemap-memory -Dtest=HnswVectorIndexTest
```
期望：3 个测试全部 PASS。

- [ ] **Step 6.6：提交**

```bash
git add roguemap-memory/src/
git commit -m "feat(memory): 实现 VectorIndex 接口和 HnswVectorIndex（jelmerk/hnswlib-core）"
```

---

## Task 7：EmbeddingProvider 实现

**Files:**
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/OpenAIEmbeddingProvider.java`
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/OllamaEmbeddingProvider.java`

> 两个实现均使用 Java 原生 `HttpURLConnection`，不引入任何 HTTP 库。网络调用在测试中通过 mock 绕过。

- [ ] **Step 7.1：实现 OpenAIEmbeddingProvider.java**

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/OpenAIEmbeddingProvider.java
package com.yomahub.roguemap.memory.embedding;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * 调用 OpenAI Embeddings API（或兼容接口）的 EmbeddingProvider 实现
 *
 * 支持自定义 baseUrl，可接 Azure、本地代理、或其他 OpenAI 兼容服务。
 * 使用 Java 原生 HttpURLConnection，无额外依赖。
 */
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
```

- [ ] **Step 7.2：实现 OllamaEmbeddingProvider.java**

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/OllamaEmbeddingProvider.java
package com.yomahub.roguemap.memory.embedding;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * 调用本地 Ollama API 的 EmbeddingProvider 实现
 *
 * 启动 Ollama 后，本地服务默认监听 http://localhost:11434
 * 示例：new OllamaEmbeddingProvider("http://localhost:11434", "nomic-embed-text", 768)
 */
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
```

- [ ] **Step 7.3：编译验证**

```bash
mvn compile -pl roguemap-memory
```
期望：BUILD SUCCESS

- [ ] **Step 7.4：提交**

```bash
git add roguemap-memory/src/main/java/com/yomahub/roguemap/memory/embedding/
git commit -m "feat(memory): 实现 OpenAIEmbeddingProvider 和 OllamaEmbeddingProvider"
```

---

## Task 8：RogueMemory — 存储层（mmap 读写记录）

**Files:**
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java`（存储层骨架，重点是 add/get/delete/scan）
- Create: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryFunctionalTest.java`

**测试策略：** 所有测试使用 `MockEmbeddingProvider`（返回固定维度随机向量，不调网络），以及临时文件模式。

- [ ] **Step 8.1：定义 MockEmbeddingProvider（测试辅助类）**

```java
// roguemap-memory/src/test/java/com/yomahub/roguemap/memory/MockEmbeddingProvider.java
package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.memory.embedding.EmbeddingProvider;
import java.util.Random;

/** 测试用，返回固定维度的伪随机向量（基于 text hashCode 保证同文本同向量） */
class MockEmbeddingProvider implements EmbeddingProvider {
    private final int dimension;

    MockEmbeddingProvider(int dimension) { this.dimension = dimension; }

    @Override
    public float[] embed(String text) {
        Random rng = new Random(text.hashCode());
        float[] v = new float[dimension];
        float norm = 0;
        for (int i = 0; i < dimension; i++) {
            v[i] = rng.nextFloat() * 2 - 1;
            norm += v[i] * v[i];
        }
        // 归一化为单位向量（cosine distance 要求）
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < dimension; i++) v[i] /= norm;
        return v;
    }

    @Override
    public int getDimension() { return dimension; }
}
```

- [ ] **Step 8.2：写失败测试（基础 CRUD）**

```java
// roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryFunctionalTest.java
package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RogueMemoryFunctionalTest {

    private static final String TEST_DIR = "target/test-memory-functional";
    private RogueMemory memory;

    @BeforeEach
    void setUp() {
        // 清理上次遗留文件（防止崩溃后损坏文件影响测试）
        deleteDir(new File(TEST_DIR));
        new File(TEST_DIR).mkdirs();

        memory = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        memory.close();
    }

    @Test
    void addAndGetById() {
        String id = memory.add("我有一件红衣服");
        assertNotNull(id);

        MemoryEntry entry = memory.get(id);
        assertNotNull(entry);
        assertEquals("我有一件红衣服", entry.getContent());
        assertEquals("default", entry.getNamespace());
        assertTrue(entry.getMetadata().isEmpty());
    }

    @Test
    void addWithMetadataAndNamespace() {
        String id = memory.add("今天天气很好",
            Map.of("userId", "u123", "source", "chat"),
            "session-1");

        MemoryEntry entry = memory.get(id);
        assertEquals("u123", entry.getMetadata().get("userId"));
        assertEquals("session-1", entry.getNamespace());
    }

    @Test
    void deleteRemovesEntry() {
        String id = memory.add("要被删除的记忆");
        memory.delete(id);
        assertNull(memory.get(id));
    }

    @Test
    void updateChangesContent() {
        String id = memory.add("原始内容");
        memory.update(id, "更新后的内容");

        MemoryEntry entry = memory.get(id);
        assertEquals("更新后的内容", entry.getContent());
    }

    @Test
    void updatePreservesMetadataAndNamespace() {
        String id = memory.add("原始内容", Map.of("key", "val"), "ns1");
        memory.update(id, "新内容");

        MemoryEntry entry = memory.get(id);
        assertEquals("ns1", entry.getNamespace());
        assertEquals("val", entry.getMetadata().get("key"));
    }

    @Test
    void getNonExistentReturnsNull() {
        assertNull(memory.get("nonexistent-id"));
    }

    private void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        dir.delete();
    }
}
```

- [ ] **Step 8.2b：在 RogueMemory.java 添加类骨架（字段 + Builder）**

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java
package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.AutoCheckpointManager;
import com.yomahub.roguemap.StorageMetrics;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.embedding.EmbeddingProvider;
import com.yomahub.roguemap.memory.index.BM25Index;
import com.yomahub.roguemap.memory.index.HnswVectorIndex;
import com.yomahub.roguemap.memory.index.VectorIndex;
import com.yomahub.roguemap.storage.MmapFileHeader;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class RogueMemory implements AutoCloseable {

    // --- 核心字段 ---
    private final MmapAllocator allocator;
    private final MmapFileHeader header;
    private final File hnswFile;
    private final EmbeddingProvider embeddingProvider;  // KEYWORD_ONLY 时为 null
    private final SearchMode searchMode;
    private final float bm25k1;
    private final float bm25b;
    private final long defaultTTLMillis;
    private final AutoCheckpointManager autoCheckpointManager;

    private VectorIndex vectorIndex;
    private BM25Index bm25Index;

    // 内存索引
    private final Map<String, Long> idToAddress = new ConcurrentHashMap<>();
    private final Map<String, Integer> idToNodeId = new ConcurrentHashMap<>();

    // package-private 构造，由 Builder 调用
    RogueMemory(MmapAllocator allocator, MmapFileHeader header, File hnswFile,
                EmbeddingProvider embeddingProvider, SearchMode searchMode,
                float bm25k1, float bm25b, long defaultTTLMillis,
                long autoCheckpointInterval, int autoCheckpointOperations) {
        this.allocator = allocator;
        this.header = header;
        this.hnswFile = hnswFile;
        this.embeddingProvider = embeddingProvider;
        this.searchMode = searchMode;
        this.bm25k1 = bm25k1;
        this.bm25b = bm25b;
        this.defaultTTLMillis = defaultTTLMillis;
        this.bm25Index = new BM25Index(bm25k1, bm25b);
        int dimension = embeddingProvider != null ? embeddingProvider.getDimension() : 0;
        this.vectorIndex = new HnswVectorIndex(dimension, 100_000);

        if (autoCheckpointInterval > 0 || autoCheckpointOperations > 0) {
            this.autoCheckpointManager = new AutoCheckpointManager(
                this::checkpoint, autoCheckpointInterval, autoCheckpointOperations);
            this.autoCheckpointManager.start();
        } else {
            this.autoCheckpointManager = null;
        }
    }

    /** 入口：RogueMemory.mmap() */
    public static MmapBuilder mmap() { return new MmapBuilder(); }

    public static class MmapBuilder {
        private String path;
        private EmbeddingProvider embeddingProvider;
        private SearchMode searchMode = SearchMode.HYBRID;
        private float bm25k1 = 1.2f;
        private float bm25b = 0.75f;
        private long defaultTTLMillis = 0;
        private long allocateSize = 32L * 1024 * 1024;  // 默认 32MB
        private boolean autoExpand = false;
        private long autoCheckpointInterval = 0;
        private int autoCheckpointOperations = 0;

        public MmapBuilder persistent(String path) { this.path = path; return this; }
        public MmapBuilder embeddingProvider(EmbeddingProvider p) { this.embeddingProvider = p; return this; }
        public MmapBuilder searchMode(SearchMode m) { this.searchMode = m; return this; }
        public MmapBuilder bm25k1(float k1) { this.bm25k1 = k1; return this; }
        public MmapBuilder bm25b(float b) { this.bm25b = b; return this; }
        public MmapBuilder defaultTTL(long ttl, TimeUnit unit) {
            this.defaultTTLMillis = unit.toMillis(ttl); return this;
        }
        public MmapBuilder allocateSize(long size) { this.allocateSize = size; return this; }
        public MmapBuilder autoExpand(boolean expand) { this.autoExpand = expand; return this; }
        public MmapBuilder autoCheckpoint(long interval, TimeUnit unit) {
            this.autoCheckpointInterval = unit.toMillis(interval); return this;
        }
        public MmapBuilder autoCheckpoint(int operationCount) {
            this.autoCheckpointOperations = operationCount; return this;
        }

        public RogueMemory build() {
            if (path == null) throw new IllegalStateException("必须调用 persistent(path)");
            if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider == null) {
                throw new IllegalStateException("HYBRID 和 VECTOR_ONLY 模式需要 embeddingProvider");
            }

            File memFile = new File(path + ".mem");
            File hnswFile = new File(path + ".hnsw");
            boolean exists = memFile.exists();

            // 初始化 MmapAllocator（与现有 RogueMap 相同用法）
            MmapAllocator allocator = new MmapAllocator(memFile.getPath(), allocateSize, autoExpand);
            MmapFileHeader header = new MmapFileHeader(allocator.getBaseAddress());

            if (exists) {
                header.load();
                // 校验 dataType
                if (header.getDataType() != MmapFileHeader.DATA_TYPE_MEMORY) {
                    throw new IllegalStateException("文件类型不匹配，期望 MEMORY(5)，实际: " + header.getDataType());
                }
            } else {
                header.init(MmapFileHeader.DATA_TYPE_MEMORY, 0 /* indexType unused */);
            }

            RogueMemory memory = new RogueMemory(allocator, header, hnswFile,
                embeddingProvider, searchMode, bm25k1, bm25b, defaultTTLMillis,
                autoCheckpointInterval, autoCheckpointOperations);

            if (exists) {
                memory.restore();  // 从磁盘恢复索引
            }
            return memory;
        }
    }

    /** 返回存储健康指标（与其他数据结构一致） */
    public StorageMetrics getMetrics() {
        return allocator.getMetrics();
    }

    @Override
    public void close() {
        if (autoCheckpointManager != null) autoCheckpointManager.stop();
        checkpoint();
        allocator.close();
    }
}
```

- [ ] **Step 8.3：运行，确认失败**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryFunctionalTest
```
期望：编译失败（RogueMemory 不存在）。

- [ ] **Step 8.4：实现 RogueMemory.java 存储层**

创建 `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java`。

**关键实现细节：**

1. **Builder 模式**（`RogueMemory.mmap()` 返回 `MmapBuilder`，`.build()` 构造实例）。

2. **mmap 初始化**：使用 `MmapAllocator` 创建 `{path}.mem` 文件，文件头用 `MmapFileHeader`（dataType = `DATA_TYPE_MEMORY = 5`），初始分配大小默认 32MB（可通过 `allocateSize()` 配置）。

3. **记录写入格式**（见 §四）：
```java
// 写入一条记录，返回 mmap 地址
private long writeRecord(String id, String content, Map<String,String> metadata,
                         String namespace, long expireTime, float[] vector) {
    byte[] idBytes = uuidToBytes(id);          // 16 bytes
    byte[] nsBytes = namespace.getBytes(StandardCharsets.UTF_8);
    byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
    byte[] metaBytes = encodeMetadata(metadata);
    // 向量长度（字节）
    int vectorBytes = (vector != null ? vector.length * 4 : 0);

    int totalSize = 8 + 16 + 2 + nsBytes.length + 4 + contentBytes.length
                  + 4 + metaBytes.length + 4 + vectorBytes + 1;

    long address = allocator.allocate(totalSize);
    long ptr = address;

    UnsafeOps.putLong(ptr, expireTime);   ptr += 8;
    UnsafeOps.copyFromArray(idBytes, 0, ptr, 16); ptr += 16;
    UnsafeOps.putShort(ptr, (short) nsBytes.length); ptr += 2;
    UnsafeOps.copyFromArray(nsBytes, 0, ptr, nsBytes.length); ptr += nsBytes.length;
    UnsafeOps.putInt(ptr, contentBytes.length); ptr += 4;
    UnsafeOps.copyFromArray(contentBytes, 0, ptr, contentBytes.length); ptr += contentBytes.length;
    UnsafeOps.putInt(ptr, metaBytes.length); ptr += 4;
    UnsafeOps.copyFromArray(metaBytes, 0, ptr, metaBytes.length); ptr += metaBytes.length;
    UnsafeOps.putInt(ptr, vector != null ? vector.length : 0); ptr += 4;
    if (vector != null) {
        for (float f : vector) { UnsafeOps.putFloat(ptr, f); ptr += 4; }
    }
    UnsafeOps.putByte(ptr, (byte) 0);  // deleted = 0

    return address;
}
```

4. **UUID ↔ bytes 互转**：
```java
private byte[] uuidToBytes(String uuidStr) {
    UUID uuid = UUID.fromString(uuidStr);
    byte[] b = new byte[16];
    long msb = uuid.getMostSignificantBits(), lsb = uuid.getLeastSignificantBits();
    for (int i = 7; i >= 0; i--) { b[i] = (byte)(msb & 0xff); msb >>= 8; }
    for (int i = 15; i >= 8; i--) { b[i] = (byte)(lsb & 0xff); lsb >>= 8; }
    return b;
}
```

5. **metadata 编码/解码**（见 §四 格式）：pair_count(2B) + [key_len(2B) + key + val_len(2B) + val] × N。

6. **add() 流程**：
   - 生成 UUID
   - 调用 `embeddingProvider.embed(content)` 获取向量（KEYWORD_ONLY 时跳过）
   - `writeRecord()` 写入 mmap
   - 更新 `idToAddress`、`idToNodeId` 映射
   - `vectorIndex.add(id, vector)`（KEYWORD_ONLY 时跳过）
   - `bm25Index.add(id, content)`
   - 返回 UUID

7. **get() 流程**：
   - 从 `idToAddress` 查地址
   - 检查 `deleted` 字节，如果为 1 返回 null
   - 检查 TTL，过期返回 null
   - 从 mmap 读取并解析记录，返回 `MemoryEntry`

8. **delete() 流程**：
   - 设 mmap 记录的 `deleted` 字节为 1
   - `vectorIndex.markDeleted(id)`
   - `bm25Index.delete(id)`
   - 从 `idToAddress`、`idToNodeId` 移除

9. **update() 流程**：
   - 先读取原记录的 metadata 和 namespace
   - delete(id)（tombstone 旧记录）
   - 重新 embed 新 content
   - writeRecord() 写入新记录（复用原 id）
   - 更新 idToAddress、idToNodeId
   - vectorIndex.add、bm25Index.add

- [ ] **Step 8.5：运行测试，确认通过**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryFunctionalTest
```
期望：7 个测试全部 PASS。

- [ ] **Step 8.6：提交**

```bash
git add roguemap-memory/src/
git commit -m "feat(memory): 实现 RogueMemory 存储层 add/get/update/delete"
```

---

## Task 9：RogueMemory — 搜索层

**Files:**
- Create: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemorySearchTest.java`
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java`（添加 search 方法）

- [ ] **Step 9.1：写失败测试**

```java
// roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemorySearchTest.java
package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RogueMemorySearchTest {

    private static final String TEST_DIR = "target/test-memory-search";
    private RogueMemory memory;

    @BeforeEach
    void setUp() {
        deleteDir(new File(TEST_DIR));
        new File(TEST_DIR).mkdirs();
        memory = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(16))
            .searchMode(SearchMode.HYBRID)
            .build();
    }

    @AfterEach
    void tearDown() throws Exception { memory.close(); }

    @Test
    void searchReturnsResults() {
        memory.add("我有一件红衣服");
        memory.add("今天天气很好");
        memory.add("明天要去开会");

        List<MemoryResult> results = memory.search("衣服", 3);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getScore() > 0);
    }

    @Test
    void searchTopKRespected() {
        for (int i = 0; i < 10; i++) {
            memory.add("记忆内容 " + i);
        }
        List<MemoryResult> results = memory.search("记忆内容", 3);
        assertTrue(results.size() <= 3);
    }

    @Test
    void searchFiltersByNamespace() {
        memory.add("session1的记忆", Collections.emptyMap(), "session-1");
        memory.add("session2的记忆", Collections.emptyMap(), "session-2");

        List<MemoryResult> results = memory.search("记忆", 5,
            SearchOptions.builder().namespace("session-1").build());

        assertTrue(results.stream().allMatch(r -> "session-1".equals(r.getNamespace())));
    }

    @Test
    void searchFiltersByMetadata() {
        memory.add("用户A的记忆", Map.of("userId", "A"), "default");
        memory.add("用户B的记忆", Map.of("userId", "B"), "default");

        List<MemoryResult> results = memory.search("记忆", 5,
            SearchOptions.builder().filter("userId", "A").build());

        assertTrue(results.stream().allMatch(r -> "A".equals(r.getMetadata().get("userId"))));
    }

    @Test
    void deletedEntriesNotInResults() {
        String id = memory.add("要被删除的记忆");
        memory.delete(id);

        List<MemoryResult> results = memory.search("删除", 5);
        assertTrue(results.stream().noneMatch(r -> r.getId().equals(id)));
    }

    @Test
    void vectorOnlyModeWorks() {
        RogueMemory vectorOnly = RogueMemory.mmap()
            .persistent(TEST_DIR + "/vec")
            .embeddingProvider(new MockEmbeddingProvider(16))
            .searchMode(SearchMode.VECTOR_ONLY)
            .build();

        vectorOnly.add("向量搜索测试内容");
        List<MemoryResult> results = vectorOnly.search("测试内容", 3);
        assertFalse(results.isEmpty());
        vectorOnly.close();
    }

    @Test
    void keywordOnlyModeWorks() {
        RogueMemory keywordOnly = RogueMemory.mmap()
            .persistent(TEST_DIR + "/kw")
            .searchMode(SearchMode.KEYWORD_ONLY)
            .build();

        keywordOnly.add("关键词搜索测试内容");
        List<MemoryResult> results = keywordOnly.search("关键词搜索", 3);
        assertFalse(results.isEmpty());
        keywordOnly.close();
    }

    private void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        dir.delete();
    }
}
```

- [ ] **Step 9.2：运行，确认失败**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemorySearchTest
```
期望：编译失败（search 方法不存在）。

- [ ] **Step 9.3：在 RogueMemory 中实现 search()**

**HYBRID 搜索流程：**
```java
public List<MemoryResult> search(String query, int topK, SearchOptions options) {
    if (options == null) options = SearchOptions.builder().build();
    SearchOptions finalOptions = options;

    List<MemoryResult> candidates;

    if (searchMode == SearchMode.HYBRID) {
        // 1. 向量搜索
        float[] queryVec = embeddingProvider.embed(query);
        List<VectorIndex.ScoredId> vecResults = vectorIndex.search(queryVec, topK * 2);

        // 2. BM25 关键词搜索
        List<String> tokens = Tokenizer.tokenize(query);
        List<BM25Index.ScoredId> bm25Results = bm25Index.search(tokens, topK * 2);

        // 3. RRF 合并
        candidates = rrfMerge(vecResults, bm25Results, topK * 2, options.getRrfConstant());
    } else if (searchMode == SearchMode.VECTOR_ONLY) {
        float[] queryVec = embeddingProvider.embed(query);
        List<VectorIndex.ScoredId> vecResults = vectorIndex.search(queryVec, topK * 2);
        candidates = toMemoryResults(vecResults);
    } else { // KEYWORD_ONLY
        List<String> tokens = Tokenizer.tokenize(query);
        List<BM25Index.ScoredId> bm25Results = bm25Index.search(tokens, topK * 2);
        candidates = bm25ToMemoryResults(bm25Results);
    }

    // 4. 过滤（namespace / metadata / TTL / deleted）
    List<MemoryResult> filtered = new ArrayList<>();
    for (MemoryResult r : candidates) {
        MemoryEntry entry = get(r.getId());
        if (entry == null) continue;  // deleted 或 TTL 过期
        if (finalOptions.getNamespace() != null
            && !finalOptions.getNamespace().equals(entry.getNamespace())) continue;
        if (!matchesFilters(entry.getMetadata(), finalOptions.getFilters())) continue;
        filtered.add(r);
        if (filtered.size() >= topK) break;
    }
    return filtered;
}

// RRF 合并
private List<MemoryResult> rrfMerge(List<VectorIndex.ScoredId> vecList,
                                     List<BM25Index.ScoredId> bm25List,
                                     int topK, int C) {
    Map<String, Float> scores = new LinkedHashMap<>();

    for (int i = 0; i < vecList.size(); i++) {
        String id = vecList.get(i).id;
        scores.merge(id, 1f / (i + 1 + C), Float::sum);
    }
    for (int i = 0; i < bm25List.size(); i++) {
        String id = bm25List.get(i).id;
        scores.merge(id, 1f / (i + 1 + C), Float::sum);
    }

    List<MemoryResult> result = new ArrayList<>(scores.size());
    for (Map.Entry<String, Float> e : scores.entrySet()) {
        MemoryEntry entry = get(e.getKey());
        if (entry == null) continue;
        result.add(new MemoryResult(entry.getId(), entry.getContent(), entry.getMetadata(),
            entry.getNamespace(), e.getValue(), entry.getCreatedAt(), entry.getExpireTime()));
    }
    result.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
    return result.subList(0, Math.min(topK, result.size()));
}
```

- [ ] **Step 9.4：运行测试，确认通过**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemorySearchTest
```
期望：7 个测试全部 PASS。

- [ ] **Step 9.5：提交**

```bash
git add roguemap-memory/src/
git commit -m "feat(memory): 实现 RogueMemory 混合搜索（HYBRID/VECTOR_ONLY/KEYWORD_ONLY）"
```

---

## Task 10：RogueMemory — 持久化与恢复

**Files:**
- Create: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryPersistenceTest.java`
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java`（close/checkpoint/compact/recovery）

- [ ] **Step 10.1：写失败测试**

```java
// roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryPersistenceTest.java
package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RogueMemoryPersistenceTest {

    private static final String TEST_DIR = "target/test-memory-persistence";

    @BeforeEach
    void setUp() {
        deleteDir(new File(TEST_DIR));
        new File(TEST_DIR).mkdirs();
    }

    @Test
    void datasurvivesCloseAndReopen() {
        String id;
        try (RogueMemory m = RogueMemory.mmap()
                .persistent(TEST_DIR + "/mem")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .build()) {
            id = m.add("持久化测试内容", Map.of("k", "v"), "ns1");
        }

        try (RogueMemory m = RogueMemory.mmap()
                .persistent(TEST_DIR + "/mem")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .build()) {
            MemoryEntry entry = m.get(id);
            assertNotNull(entry);
            assertEquals("持久化测试内容", entry.getContent());
            assertEquals("ns1", entry.getNamespace());
            assertEquals("v", entry.getMetadata().get("k"));
        }
    }

    @Test
    void searchWorksAfterReopen() {
        try (RogueMemory m = RogueMemory.mmap()
                .persistent(TEST_DIR + "/search")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .build()) {
            m.add("衣服记忆内容");
            m.add("天气记忆内容");
        }

        try (RogueMemory m = RogueMemory.mmap()
                .persistent(TEST_DIR + "/search")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .build()) {
            List<MemoryResult> results = m.search("衣服", 3);
            assertFalse(results.isEmpty());
        }
    }

    @Test
    void checkpointAllowsRecovery() {
        RogueMemory m = RogueMemory.mmap()
            .persistent(TEST_DIR + "/checkpoint")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .build();
        String id = m.add("checkpoint 测试");
        m.checkpoint();
        // 不调用 close()，直接模拟崩溃（不调用 close）
        // （不能真正模拟崩溃，此测试验证 checkpoint 后数据已持久化）
        m.close();

        try (RogueMemory m2 = RogueMemory.mmap()
                .persistent(TEST_DIR + "/checkpoint")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .build()) {
            assertNotNull(m2.get(id));
        }
    }

    @Test
    void compactRemovesTombstones() {
        String id;
        try (RogueMemory m = RogueMemory.mmap()
                .persistent(TEST_DIR + "/compact")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .build()) {
            id = m.add("要被删除的内容");
            m.add("要保留的内容");
            m.delete(id);

            RogueMemory compacted = m.compact(64 * 1024 * 1024);
            assertNull(compacted.get(id));

            List<MemoryResult> results = compacted.search("保留", 5);
            assertFalse(results.isEmpty());
            compacted.close();
        }
    }

    private void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        dir.delete();
    }
}
```

- [ ] **Step 10.2：运行，确认失败**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryPersistenceTest
```

- [ ] **Step 10.3：实现 close() / checkpoint() / compact() / build() 恢复逻辑**

**close() / checkpoint() 实现（按 §八 顺序）：**
```java
public synchronized void checkpoint() {
    // 1. 设置 dirtyFlag=1, writeGen 奇数
    header.setDirtyFlag(1);
    header.setWriteGen(header.getWriteGen() | 1);

    // 2. 序列化 BM25 → allocate → 写入 mmap
    // 格式：[size: 4 bytes int][BM25 数据：size bytes]
    // bm25IndexOffset 指向这 4 bytes 的文件偏移，恢复时先读 4 bytes 获取 size
    try {
        byte[] bm25Bytes = bm25Index.serialize();
        long bm25Addr = allocator.allocate(4 + bm25Bytes.length);
        UnsafeOps.putInt(bm25Addr, bm25Bytes.length);                           // 写 size 头
        UnsafeOps.copyFromArray(bm25Bytes, 0, bm25Addr + 4, bm25Bytes.length);  // 写数据
        long bm25FileOffset = allocator.getFileOffsetForAddress(bm25Addr);
        header.setBm25IndexOffset(bm25FileOffset);
    } catch (IOException e) {
        throw new RuntimeException("BM25 序列化失败", e);
    }

    // 3. 序列化 HNSW → .hnsw 文件
    // 文件格式：[generation: 8 bytes long][HNSW 数据]
    long newGeneration = header.getHnswGeneration() + 1;
    try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(hnswFile))) {
        dos.writeLong(newGeneration);  // generation 写到文件头
        dos.flush();
        vectorIndex.serialize(dos);   // HNSW 数据追加在 generation 后
    } catch (IOException e) {
        throw new RuntimeException("HNSW 序列化失败", e);
    }
    header.setHnswGeneration(newGeneration);

    // 4. 更新 currentOffset，设置 dirtyFlag=0, writeGen 偶数
    header.setCurrentOffset(allocator.getCurrentOffset());
    header.setDirtyFlag(0);
    header.setWriteGen(header.getWriteGen() + 1);

    // 5. force flush
    allocator.force();
}
```

**build() 恢复逻辑：**
```java
// 检测 dirtyFlag
if (header.getDirtyFlag() == 1) {
    // crash recovery：全量扫描 .mem 数据区重建所有索引
    rebuildAllIndexesFromScan();
} else {
    // 正常恢复
    // 1. 重建 id→address（扫描数据区）
    rebuildIdToAddress();
    // 2. 加载 BM25
    // BM25 序列化格式：[size: 4 bytes int][BM25 数据: size bytes]
    // bm25IndexOffset 指向这 4 bytes 的 mmap 地址对应的文件偏移
    long bm25Offset = header.getBm25IndexOffset();
    if (bm25Offset > 0) {
        long bm25Addr = allocator.getAddressForOffset(bm25Offset);
        int bm25Size = UnsafeOps.getInt(bm25Addr);          // 读取前 4 bytes 长度
        byte[] bm25Bytes = new byte[bm25Size];
        UnsafeOps.copyToArray(bm25Addr + 4, bm25Bytes, 0, bm25Size);  // 跳过长度头
        bm25Index = BM25Index.deserialize(bm25Bytes, k1, b);
    }
    // 3. 加载 HNSW（检查 .hnsw 文件大小是否与 header 记录的 hnswGeneration 一致）
    // .hnsw 文件首 8 bytes 存储写入时的 generation 号（由 checkpoint() 在写入后追加写入文件头）
    if (hnswFile.exists() && hnswFile.length() > 8) {
        long fileGen = readHnswFileGeneration(hnswFile);  // 读取 .hnsw 文件首 8 bytes
        if (fileGen == header.getHnswGeneration()) {
            try (FileInputStream fis = new FileInputStream(hnswFile)) {
                fis.skip(8);  // 跳过 generation 头
                vectorIndex = HnswVectorIndex.load(fis, dimension);
            }
        } else {
            rebuildHnswFromVectors();  // generation 不匹配，从 mmap 向量重建
        }
    } else {
        rebuildHnswFromVectors();
    }
    // readHnswFileGeneration 实现：
    // try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) { return dis.readLong(); }
}
```

> **注意：** BM25 大小需要在 header 中用一个额外字段记录，或在数据前写 4 bytes 长度头。推荐在 BM25 序列化时先写 4 bytes 长度，`bm25IndexOffset` 指向这 4 bytes 的位置。

**compact() 实现：**
1. 创建新 `{path}.mem.tmp` 文件
2. 扫描原 .mem，跳过 deleted=1 和 TTL 已过期的记录
3. 将存活记录全部写入新文件
4. 重建 BM25 和 HNSW（从存活向量构建）
5. 原子替换文件（`.mem.tmp` → `.mem`，`.hnsw.tmp` → `.hnsw`）
6. 关闭当前实例，返回新实例（指向新文件）

- [ ] **Step 10.4：运行测试，确认通过**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryPersistenceTest
```
期望：4 个测试全部 PASS。

- [ ] **Step 10.5：运行全模块测试**

```bash
mvn test -pl roguemap-memory
```
期望：所有测试 PASS。

- [ ] **Step 10.6：提交**

```bash
git add roguemap-memory/src/
git commit -m "feat(memory): 实现 RogueMemory 持久化（close/checkpoint/compact/crash recovery）"
```

---

## Task 11：roguemap-memory-pro 模块（jvector）

**Files:**
- Create: `roguemap-memory-pro/pom.xml`
- Create: `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/JVectorIndex.java`
- Copy: 其余文件从 `roguemap-memory` 复制（RogueMemory.java、BM25Index.java、Tokenizer.java 等），仅替换 VectorIndex 实现

- [ ] **Step 11.1：创建 roguemap-memory-pro/pom.xml**

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

    <artifactId>roguemap-memory-pro</artifactId>
    <packaging>jar</packaging>
    <name>RogueMap Memory Pro (Java 11+, jvector)</name>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.yomahub</groupId>
            <artifactId>roguemap-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.jbellis</groupId>
            <artifactId>jvector</artifactId>
            <version>3.0.1</version>
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
                    <source>11</source>
                    <target>11</target>
                    <compilerArgs>
                        <arg>--add-opens=java.base/java.nio=ALL-UNNAMED</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

**依赖顺序：Task 11 必须在 Task 10 全部完成后执行**（Task 8/9/10 会修改 `RogueMemory.java`，复制必须在文件最终稳定后进行）。

- [ ] **Step 11.2：复制共享代码**

```bash
mkdir -p roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/{embedding,index,util}
mkdir -p roguemap-memory-pro/src/test/java/com/yomahub/roguemap/memory

# 复制所有非向量索引文件
cp roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java \
   roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/
cp roguemap-memory/src/main/java/com/yomahub/roguemap/memory/MemoryEntry.java \
   roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/
# ... 同样复制 MemoryResult, SearchOptions, SearchMode, EmbeddingProvider,
#     OpenAIEmbeddingProvider, OllamaEmbeddingProvider, BM25Index, Tokenizer, VectorIndex
```

- [ ] **Step 11.3：实现 JVectorIndex.java**

> 参考 jvector 3.x 文档（`docs/tutorials/` 和 `jvector-examples/` 目录）。核心类：
> - `GraphIndexBuilder` — 构建图索引
> - `OnHeapGraphIndex` / `OnDiskGraphIndex` — 图存储
> - `ListRandomAccessVectorValues` — 内存向量集合
> - `VectorSimilarityFunction.COSINE` — 余弦相似度

```java
// roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/JVectorIndex.java
package com.yomahub.roguemap.memory.index;

// （实现参考 jvector README 和示例代码）
// 核心职责：
// - add(id, vector): 维护内存中的 id→vector 列表，动态插入 HNSW 图
// - markDeleted(id): tombstone
// - search(queryVector, topK): GraphIndex 搜索后过滤 tombstone
// - serialize(out): 序列化图结构 + deletedIds
// - load(in, dimension): 反序列化

// 注意：jvector 使用整数 nodeId，需要维护 id(String) ↔ nodeId(int) 的双向映射
```

- [ ] **Step 11.4：写 JVectorIndex 测试和 RogueMemoryProFunctionalTest**

```java
// roguemap-memory-pro/src/test/java/com/yomahub/roguemap/memory/JVectorIndexTest.java
package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.memory.index.JVectorIndex;
import com.yomahub.roguemap.memory.index.VectorIndex;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JVectorIndexTest {

    static float[] vec(float... v) { return v; }

    @Test
    void addAndSearch() {
        JVectorIndex index = new JVectorIndex(3, 1000);
        index.add("a", vec(1f, 0f, 0f));
        index.add("b", vec(0f, 1f, 0f));
        index.add("c", vec(0f, 0f, 1f));
        List<VectorIndex.ScoredId> results = index.search(vec(1f, 0f, 0f), 1);
        assertEquals(1, results.size());
        assertEquals("a", results.get(0).id);
    }

    @Test
    void markDeletedExcludesFromResults() {
        JVectorIndex index = new JVectorIndex(3, 1000);
        index.add("a", vec(1f, 0f, 0f));
        index.add("b", vec(0.99f, 0.01f, 0f));
        index.markDeleted("a");
        List<VectorIndex.ScoredId> results = index.search(vec(1f, 0f, 0f), 2);
        assertTrue(results.stream().noneMatch(r -> r.id.equals("a")));
    }

    @Test
    void serializeAndDeserialize() throws IOException {
        JVectorIndex index = new JVectorIndex(3, 1000);
        index.add("a", vec(1f, 0f, 0f));
        index.add("b", vec(0f, 1f, 0f));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // generation 头（8 bytes）由 RogueMemory checkpoint() 写到 .hnsw 文件，
        // 这里直接测 VectorIndex 序列化（不含文件级 generation 头）
        index.serialize(baos);
        JVectorIndex restored = JVectorIndex.load(
            new ByteArrayInputStream(baos.toByteArray()), 3);
        List<VectorIndex.ScoredId> results = restored.search(vec(1f, 0f, 0f), 1);
        assertEquals("a", results.get(0).id);
    }
}
```

```java
// roguemap-memory-pro/src/test/java/com/yomahub/roguemap/memory/RogueMemoryProFunctionalTest.java
package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 roguemap-memory-pro 的 RogueMemory API 与 roguemap-memory 完全一致 */
class RogueMemoryProFunctionalTest {

    private static final String TEST_DIR = "target/test-memory-pro";
    private RogueMemory memory;

    @BeforeEach
    void setUp() {
        deleteDir(new File(TEST_DIR));
        new File(TEST_DIR).mkdirs();
        memory = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .build();
    }

    @AfterEach
    void tearDown() { memory.close(); }

    @Test
    void addSearchDeleteWorks() {
        String id = memory.add("jvector 向量搜索测试");
        List<MemoryResult> results = memory.search("向量搜索", 3);
        assertFalse(results.isEmpty());
        memory.delete(id);
        assertNull(memory.get(id));
    }

    @Test
    void persistenceWorks() {
        String id;
        memory.add("持久化内容");
        id = memory.add("第二条记忆");
        memory.close();

        memory = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .build();
        assertNotNull(memory.get(id));
    }

    private void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        dir.delete();
    }
}
```

- [ ] **Step 11.5：验证 roguemap-memory-pro 全部测试通过**

```bash
mvn test -pl roguemap-memory-pro
```
期望：BUILD SUCCESS，所有测试通过。

- [ ] **Step 11.6：提交**

```bash
git add roguemap-memory-pro/
git commit -m "feat(memory-pro): 创建 roguemap-memory-pro 模块，实现 JVectorIndex（datastax/jvector）"
```

---

## Task 12：全项目集成验证

- [ ] **Step 12.1：全项目编译**

```bash
mvn clean compile
```
期望：BUILD SUCCESS（三个模块全部编译通过）。

- [ ] **Step 12.2：全项目测试**

```bash
mvn test
```
期望：BUILD SUCCESS，roguemap-core（218 个）+ roguemap-memory（~20 个）+ roguemap-memory-pro（~10 个）全部通过。

- [ ] **Step 12.3：提交**

```bash
git add -A
git commit -m "feat: RogueMemory 实现完成，全项目测试通过"
```

---

## 注意事项

1. **BM25 size 字段**：已在 Task 10 Step 10.3 中明确：allocate `4 + bm25Bytes.length` 字节，前 4 bytes 写长度，后续写数据。bm25IndexOffset 指向这 4 bytes 的文件偏移。

2. **jvector API**：Task 11 中 `JVectorIndex` 的实现细节依赖 jvector 3.0.1 的具体 API，实现时应先阅读 `jvector-examples/src/main/java/io/github/jbellis/jvector/example/tutorial/VectorIntro.java`。

3. **jelmerk hnswlib-core Java 8 兼容性**：如果编译时发现 `hnswlib-core:1.2.1` 要求 Java 9+，切换到版本 `0.0.49`，该版本明确支持 Java 8。

4. **autoExpand / TTL / autoCheckpoint**：这些功能在 `MmapAllocator` 和 `AutoCheckpointManager` 中已有完整实现，`RogueMemory` 的 Builder 直接复用相同配置项，无需重新实现。

5. **MmapFileHeader 偏移检查**：Step 2.1 新增字段在 offset 112–127。现有使用的偏移：0–47（数据字段）、48–95（校验 + Queue 快照）、96–103（LIST_EXPIRE_TIME_POS）。104–111 当前保留未用。实现前用 `grep -r 'POS = 10[0-9]'` 确认 104–111 没有冲突。

6. **RogueMemory 实现 AutoCloseable**：Builder 骨架中已声明 `implements AutoCloseable`，`close()` 方法已提供。`compact()` 返回新实例，原实例在 `compact()` 内部被关闭（不再使用），调用方不应再调用原实例的 `close()`。测试 `compactRemovesTombstones` 中外层 `try-with-resources` 关闭的是原 memory 实例，`compact()` 已在内部关闭它，这会导致 `close()` 被调用两次——需在 `close()` 中加幂等保护（`if (closed) return; closed = true;`）。
