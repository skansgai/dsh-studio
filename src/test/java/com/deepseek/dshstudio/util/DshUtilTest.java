package com.deepseek.dshstudio.util;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.settings.DshSettingsState;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * DshUtil 纯逻辑单元测试（不依赖 IDE 运行环境）。
 */
public class DshUtilTest {

    @Test
    public void tokenizeHandlesQuotes() {
        List<String> tokens = DshUtil.tokenize("npx --yes \"@deepseek-ai/dsh web\" --port 3080");
        assertEquals(5, tokens.size());
        assertEquals("npx", tokens.get(0));
        assertEquals("@deepseek-ai/dsh web", tokens.get(2));
        assertEquals("3080", tokens.get(4));
    }

    @Test
    public void tokenizeEmptyLine() {
        assertTrue(DshUtil.tokenize("   ").isEmpty());
    }

    @Test
    public void hostOfParsesUrl() {
        assertEquals("127.0.0.1", DshUtil.hostOf("http://127.0.0.1:3080/"));
        assertEquals("example.com", DshUtil.hostOf("https://example.com:8080/x"));
        assertEquals("127.0.0.1", DshUtil.hostOf("not a url"));
    }

    @Test
    public void resolveCommandLineUsesDefaultTemplate() {
        DshSettingsState settings = new DshSettingsState();
        settings.serverUrl = "http://127.0.0.1:8080";
        settings.startPort = 8080;
        settings.serverCommand = "";

        List<String> command = DshUtil.resolveCommandLine(settings, null);
        assertFalse(command.isEmpty());
        // 默认模板应包含 dsh 子命令与端口
        String joined = String.join(" ", command);
        assertTrue(joined.contains("@deepseek-ai/dsh"));
        assertTrue(joined.contains("--port"));
        assertTrue(joined.contains("8080"));
        assertTrue(joined.contains("--host"));
        assertTrue(joined.contains("127.0.0.1"));
    }

    @Test
    public void resolveCommandLineSubstitutesPlaceholders() {
        DshSettingsState settings = new DshSettingsState();
        settings.serverCommand = "echo {host} {port} {dshHome}";
        settings.serverUrl = "http://192.168.1.5:9999";
        settings.startPort = 9999;
        settings.dshHome = "D:/dsh-home";

        List<String> command = DshUtil.resolveCommandLine(settings, null);
        assertEquals("echo", command.get(0));
        assertEquals("192.168.1.5", command.get(1));
        assertEquals("9999", command.get(2));
        assertEquals("D:/dsh-home", command.get(3));
    }

    @Test
    public void defaultUrlConstantIsReachableFormat() {
        assertEquals("http://127.0.0.1:3080", DshStudioConstants.DEFAULT_SERVER_URL);
        assertEquals(3080, DshStudioConstants.DEFAULT_PORT);
    }

    // ── 0.2.0 新增工具方法 ─────────────────────────────────────────────────

    @Test
    public void extractLaunchTokenFromServerOutput() {
        assertEquals("abc123", DshUtil.extractLaunchToken(
                "DeepSeek Harness listening at http://127.0.0.1:3080/?token=abc123"));
        assertEquals("XYZ_9-0.4~", DshUtil.extractLaunchToken(
                "http://localhost:3999/?token=XYZ_9-0.4~&other=1"));
        // 带 & 前缀（拼接场景）
        assertEquals("tok", DshUtil.extractLaunchToken("http://x/?a=1&token=tok"));
        assertNull(DshUtil.extractLaunchToken("no token here"));
        assertNull(DshUtil.extractLaunchToken(null));
    }

    @Test
    public void buildCodePromptContainsLocationAndFence() {
        String prompt = DshUtil.buildCodePrompt(
                "解释这段代码", "src/Main.java", 10, 20, "int x = 1;", "java", null);
        assertTrue(prompt.startsWith("解释这段代码"));
        assertTrue(prompt.contains("文件: src/Main.java（第 10-20 行）"));
        assertTrue(prompt.contains("```java\nint x = 1;\n```"));
        // 无定位信息时不应出现"文件:"
        String bare = DshUtil.buildCodePrompt("指令", null, 0, 0, "code", null, null);
        assertFalse(bare.contains("文件:"));
        assertTrue(bare.contains("```\ncode\n```"));
        // 补充说明追加在末尾
        String withNote = DshUtil.buildCodePrompt("指令", null, 0, 0, "code", null, "补充说明");
        assertTrue(withNote.trim().endsWith("补充说明"));
    }

    @Test
    public void relativeTimeDescribesCommonRanges() {
        long now = System.currentTimeMillis();
        assertEquals("未知时间", DshUtil.relativeTime(0));
        assertEquals("刚刚", DshUtil.relativeTime(now - 5_000));
        assertEquals("3 分钟前", DshUtil.relativeTime(now - 3 * 60_000 - 5_000));
        assertEquals("2 小时前", DshUtil.relativeTime(now - 2 * 3_600_000 - 5_000));
        assertEquals("4 天前", DshUtil.relativeTime(now - 4 * 86_400_000 - 5_000));
        assertEquals("刚刚", DshUtil.relativeTime(now + 60_000));
    }
}
