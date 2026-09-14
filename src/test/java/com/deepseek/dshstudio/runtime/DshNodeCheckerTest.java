package com.deepseek.dshstudio.runtime;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DshNodeChecker} 的纯逻辑测试。
 * <p>
 * 刻意不碰 {@code guideIfNeeded}（要弹 Swing 对话框，得跑在 IDE 里），
 * 只测不需要 IDE 运行时的部分。
 */
public class DshNodeCheckerTest {

    @Test
    public void commandNeedsNodeRecognizesNodePrograms() {
        // 系统方式
        assertTrue(DshNodeChecker.commandNeedsNode(List.of("npx", "--yes", "@deepseek-ai/dsh")));
        assertTrue(DshNodeChecker.commandNeedsNode(List.of("npx.cmd", "web")));
        assertTrue(DshNodeChecker.commandNeedsNode(List.of("npm", "exec", "dsh")));
        // 内置运行时：node <运行时>/node_modules/@deepseek-ai/dsh/lib/bin.js
        assertTrue(DshNodeChecker.commandNeedsNode(List.of("node", "lib/bin.js", "web")));
        assertTrue(DshNodeChecker.commandNeedsNode(List.of("node.exe", "lib/bin.js")));
        // 带路径时按文件名判断（用正斜杠，保证在 Windows / Unix 上行为一致）
        assertTrue(DshNodeChecker.commandNeedsNode(
                List.of("C:/Program Files/nodejs/node.exe", "bin.js")));
        // 全局安装
        assertTrue(DshNodeChecker.commandNeedsNode(List.of("dsh", "web")));
        assertTrue(DshNodeChecker.commandNeedsNode(List.of("dsh.cmd", "web")));
    }

    @Test
    public void commandNeedsNodeIgnoresNonNodeCommands() {
        // 用户在设置里把命令改成非 Node 程序时不该误报
        assertFalse(DshNodeChecker.commandNeedsNode(List.of("bash", "-c", "./run.sh")));
        assertFalse(DshNodeChecker.commandNeedsNode(List.of("python", "serve.py")));
        assertFalse(DshNodeChecker.commandNeedsNode(List.of("/usr/bin/env", "dsh")));
        assertFalse(DshNodeChecker.commandNeedsNode(Collections.emptyList()));
        assertFalse(DshNodeChecker.commandNeedsNode(null));
    }

    @Test
    public void checkNeverThrowsAndKeepsStatusConsistent() {
        // 探测结果与环境有关（本机可能没装 Node，版本也可能偏低），
        // 所以只断言「不抛异常 + 状态与 version 字段自洽」。
        DshNodeChecker.Report report = DshNodeChecker.check();
        assertNotNull(report.status);
        assertEquals(report.status == DshNodeChecker.Status.OK, report.isOk());
        if (report.status == DshNodeChecker.Status.MISSING) {
            assertNull(report.version);
        } else {
            assertNotNull(report.version);
        }
        assertNotNull(report.describe());
    }
}
