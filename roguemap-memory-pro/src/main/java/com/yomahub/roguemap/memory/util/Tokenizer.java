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
