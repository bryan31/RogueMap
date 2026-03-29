package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.memory.util.Tokenizer;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TokenizerTest {

    @Test
    void chineseTextUsesBigram() {
        List<String> tokens = Tokenizer.tokenize("我有一件红衣服");
        assertEquals(Arrays.asList("我有", "有一", "一件", "件红", "红衣", "衣服"), tokens);
    }

    @Test
    void englishTextUsesWhitespaceSplit() {
        List<String> tokens = Tokenizer.tokenize("red dress shirt");
        assertEquals(Arrays.asList("red", "dress", "shirt"), tokens);
    }

    @Test
    void mixedTextWithMajorityCjkUsesBigram() {
        // "用户中文搜索test" 中 CJK 字符占比 > 50% (6 CJK out of 11 chars = 54.5%)
        List<String> tokens = Tokenizer.tokenize("用户中文搜索test");
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
        assertEquals(Arrays.asList("red", "dress"), tokens);
    }
}

