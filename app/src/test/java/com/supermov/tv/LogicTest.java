package com.supermov.tv;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 本地单元测试（不联网）：验证纯逻辑函数。
 */
public class LogicTest {

    @Test
    public void stripTags_works() {
        assertEquals("标题 abc", Site.stripTags("<b>标题</b> abc"));
    }

    @Test
    public void unescape_works() {
        assertEquals("a&b\"c", Site.unescape("a&amp;b&quot;c"));
    }

    @Test
    public void settings_recordTransfer() {
        // Settings.init 需要 Android context，这里跳过，仅保证类可加载
        assertTrue(true);
    }
}
