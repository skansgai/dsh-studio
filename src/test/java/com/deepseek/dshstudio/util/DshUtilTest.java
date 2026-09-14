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

        // 默认模板里的 {dsh} 由运行时服务展开，这里注入一个固定的系统前缀，
        // 避免单元测试依赖 IDE 应用环境。
        List<String> command = DshUtil.resolveTemplate(
                settings.normalizedServerCommand(), settings, null,
                List.of("npx", "--yes", "@deepseek-ai/dsh"));
        assertFalse(command.isEmpty());
        // 默认模板应包含 dsh 子命令与端口
        String joined = String.join(" ", command);
        assertTrue(joined.contains("@deepseek-ai/dsh"));
        assertTrue(joined.contains("--port"));
        assertTrue(joined.contains("8080"));
        assertTrue(joined.contains("--host"));
        assertTrue(joined.contains("127.0.0.1"));
        assertTrue(joined.contains("--no-open"));
    }

    @Test
    public void defaultTemplatesUseDshPlaceholder() {
        assertTrue(DshStudioConstants.DEFAULT_SERVER_COMMAND.startsWith("{dsh} "));
        assertTrue(DshStudioConstants.DEFAULT_HEADLESS_COMMAND.startsWith("{dsh} "));
    }

    @Test
    public void dshPrefixKeepsPathsWithSpacesIntact() {
        DshSettingsState settings = new DshSettingsState();
        // 内置运行时的展开结果含带空格的路径，必须按 token 拼接而不是字符串替换
        List<String> command = DshUtil.resolveTemplate(
                "{dsh} web --port {port}", settings, null,
                List.of("C:\\Program Files\\nodejs\\node.exe",
                        "C:\\Users\\a b\\runtime\\node_modules\\@deepseek-ai\\dsh\\lib\\bin.js"));
        assertEquals(5, command.size());
        assertEquals("C:\\Program Files\\nodejs\\node.exe", command.get(0));
        assertEquals("C:\\Users\\a b\\runtime\\node_modules\\@deepseek-ai\\dsh\\lib\\bin.js", command.get(1));
        assertEquals("web", command.get(2));
        assertEquals("--port", command.get(3));
        assertEquals(String.valueOf(settings.startPort), command.get(4));
    }

    @Test
    public void templateWithoutDshPlaceholderIsUntouched() {
        DshSettingsState settings = new DshSettingsState();
        settings.serverCommand = "echo {host} {port} {dshHome}";
        settings.serverUrl = "http://192.168.1.5:9999";
        settings.startPort = 9999;
        settings.dshHome = "D:/dsh-home";

        // 不含 {dsh} 的模板不触碰运行时服务，纯文本替换
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
