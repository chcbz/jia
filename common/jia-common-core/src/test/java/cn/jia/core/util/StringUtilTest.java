package cn.jia.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringUtilTest {

    @Test
    void isBlank() {
        assertTrue(StringUtil.isBlank(" "));
    }

    @Test
    void stripNonValidXmlCharacters() {
        char nonValidChar = 0xffff;
        assertEquals(StringUtil.stripNonValidXmlCharacters("abcd" + nonValidChar), "abcd");
    }

    @Test
    void levenshtein() {
        //要比较的两个字符串
        String str1 = "真正努力过得人才知道，天赋有多重要。";
        String str2 = "大部分成功靠得既不是厚积薄发的努力，也不是戏剧化的机遇，而是早就定好的出身和天赋。";
        assertEquals(StringUtil.levenshtein(str1.toLowerCase(), str2.toLowerCase()), 0.097561f);
    }

    @Test
    void removeBlank() {
        assertEquals(StringUtil.removeBlank("　 abc  \nd "), "abcd");
    }
}