package com.deepseek.dshstudio.settings;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.util.DshUtil;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 项目级设置单元测试（不依赖 IDE 运行环境：project 传 {@code null}）。
 * <p>
 * 覆盖「A、B 两个项目各用各的端口 / 工作目录」这条链路上最容易写错的部分：
 * 自动模式与手动模式的判定、已分配端口的优先级、以及启动命令模板里 {host} / {port} 的来源。
 */
public class DshProjectSettingsTest {

    /** 项目级设置在单测里可以脱离 IDE 环境构造（不涉及项目路径的方法都可用）。 */
    private static DshProjectSettings fresh() {
        return new DshProjectSettings(null);
    }

    // ── 模式判定与地址推导 ────────────────────────────────────────────────

    @Test
    public void emptyUrlMeansAutoManaged() {
        DshProjectSettings s = fresh();
        assertTrue(s.isAutoManaged());
        assertEquals(DshStudioConstants.DEFAULT_PORT, s.effectivePort());
        assertEquals("http://127.0.0.1:" + DshStudioConstants.DEFAULT_PORT, s.effectiveServerUrl());
        assertEquals("127.0.0.1", s.effectiveHost());
    }

    @Test
    public void blankUrlIsTreatedAsAutoManaged() {
        DshProjectSettings s = fresh();
        s.serverUrl = "   ";
        assertTrue("只有空白字符等同于留空", s.isAutoManaged());
    }

    @Test
    public void allocatedPortWinsOverConfiguredPort() {
        DshProjectSettings s = fresh();
        s.startPort = 3080;
        // 场景：期望 3080，但那个端口被另一个项目占着，插件实际分到了 3091
        s.allocatedPort = 3091;
        assertEquals(3091, s.effectivePort());
        assertEquals("http://127.0.0.1:3091", s.effectiveServerUrl());
    }

    @Test
    public void manualUrlWinsOverEverything() {
        DshProjectSettings s = fresh();
        s.serverUrl = "http://127.0.0.1:4000";
        s.startPort = 3080;
        s.allocatedPort = 3091;
        assertFalse(s.isAutoManaged());
        assertEquals("http://127.0.0.1:4000", s.effectiveServerUrl());
        assertEquals("手动模式以地址里写的端口为准", 4000, s.effectivePort());
    }

    @Test
    public void manualUrlWithoutPortFallsBackToConfiguredPort() {
        DshProjectSettings s = fresh();
        s.serverUrl = "http://build-box";
        s.startPort = 3080;
        assertEquals(3080, s.effectivePort());
        assertEquals("build-box", s.effectiveHost());
    }

    @Test
    public void invalidPortIsNotTakenFromUrl() {
        DshProjectSettings s = fresh();
        s.serverUrl = "not a url";
        s.startPort = 3080;
        assertEquals(3080, s.effectivePort());
        assertEquals("解析不出主机时退回回环地址", "127.0.0.1", s.effectiveHost());
    }

    // ── 工作目录 ──────────────────────────────────────────────────────────

    @Test
    public void workingDirectoryPrefersConfiguredValue() {
        DshProjectSettings s = fresh();
        s.workingDirectory = "  D:\\Develop\\A  ";
        assertEquals("D:\\Develop\\A", s.resolvedWorkingDirectory());
    }

    @Test
    public void workingDirectoryFallsBackToUserHomeWithoutProject() {
        DshProjectSettings s = fresh();
        assertEquals(System.getProperty("user.home", "."), s.resolvedWorkingDirectory());
    }

    // ── 持久化时的清洗 ────────────────────────────────────────────────────

    @Test
    public void loadStateSanitisesValues() {
        DshProjectSettings s = fresh();
        DshProjectSettings raw = fresh();
        raw.serverUrl = null;
        raw.startPort = 0;
        raw.allocatedPort = -5;
        raw.workingDirectory = null;
        raw.serverAuthToken = null;
        raw.migrated = true; // 跳过「从应用级继承」分支，避免依赖 IDE 应用环境

        s.loadState(raw);

        assertEquals("", s.serverUrl);
        assertEquals(DshStudioConstants.DEFAULT_PORT, s.startPort);
        assertEquals(0, s.allocatedPort);
        assertEquals("", s.workingDirectory);
        assertEquals("", s.serverAuthToken);
        assertTrue(s.isAutoManaged());
    }

    @Test
    public void tokenIsTrimmed() {
        DshProjectSettings s = fresh();
        s.serverAuthToken = "  tok-123  ";
        assertEquals("tok-123", s.normalizedServerAuthToken());
    }

    // ── 与启动命令模板的联动 ──────────────────────────────────────────────

    @Test
    public void resolveTemplateUsesProjectLevelHostAndPort() {
        DshSettingsState app = new DshSettingsState();
        app.serverCommand = "";
        DshProjectSettings proj = fresh();
        proj.startPort = 3080;
        proj.allocatedPort = 3091; // 期望端口被占，实际用 3091

        List<String> command = DshUtil.resolveTemplate(
                "{dsh} web --host {host} --port {port}", app, proj, null,
                List.of("dsh"));

        // {dsh} 只注入单个 token，subList(1) 恰好就是模板展开的其余部分。
        // 第 0 个 token 会被 resolveLauncher 解析成绝对路径（Windows 下走 where），与平台相关，故不纳入断言；
        // 多 token 前缀（含带空格路径）的拼接由 DshUtilTest#dshPrefixKeepsPathsWithSpacesIntact 覆盖。
        assertEquals(List.of("web", "--host", "127.0.0.1", "--port", "3091"),
                command.subList(1, command.size()));
    }

    @Test
    public void resolveTemplateUsesProjectLevelWorkingDirectory() {
        DshSettingsState app = new DshSettingsState();
        DshProjectSettings proj = fresh();
        proj.workingDirectory = "D:\\Develop\\B";

        List<String> command = DshUtil.resolveTemplate(
                "{dsh} web --cwd {workdir}", app, proj, null, List.of("node", "dsh.js"));

        assertEquals(List.of("dsh.js", "web", "--cwd", "D:\\Develop\\B"),
                command.subList(1, command.size()));
    }

    @Test
    public void resolveTemplateManualModeUsesFilledUrl() {
        DshSettingsState app = new DshSettingsState();
        DshProjectSettings proj = fresh();
        proj.serverUrl = "http://10.0.0.7:9000";

        List<String> command = DshUtil.resolveTemplate(
                "{dsh} web --host {host} --port {port}", app, proj, null, List.of("dsh"));

        assertEquals(List.of("web", "--host", "10.0.0.7", "--port", "9000"),
                command.subList(1, command.size()));
    }
}
