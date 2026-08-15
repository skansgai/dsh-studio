package com.deepseek.dshstudio.util;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.settings.DshSettingsState;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
