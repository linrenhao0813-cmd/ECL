package com.ecl.modrinth.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChineseDescriptionServiceTest {
    @Test
    void parsesGoogleSegments() {
        assertEquals("提高渲染性能。", ChineseDescriptionService.parseGoogle(
                "[[[\"提高渲染性能。\",\"Improves rendering performance.\",null,null,10]],null,\"en\"]"));
    }

    @Test
    void parsesMyMemoryResponse() {
        assertEquals("提高渲染性能。", ChineseDescriptionService.parseMyMemory(
                "{\"responseData\":{\"translatedText\":\"提高渲染性能。\"}}"));
    }

    @Test
    void parsesTencentResponse() {
        assertEquals("Minecraft的高性能渲染引擎替代品。", ChineseDescriptionService.parseTencent(
                "{\"header\":{\"ret_code\":\"succ\"},\"auto_translation\":[\"Minecraft的高性能渲染引擎替代品。\"],"
                        + "\"src_lang\":\"en\",\"tgt_lang\":\"zh\"}"));
    }

    @Test
    void tencentFailureReturnsBlank() {
        assertEquals("", ChineseDescriptionService.parseTencent(
                "{\"header\":{\"ret_code\":\"fail\"}}"));
        assertEquals("", ChineseDescriptionService.parseTencent(
                "{\"header\":{\"ret_code\":\"succ\"},\"auto_translation\":[]}"));
    }
}
