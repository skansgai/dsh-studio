package com.deepseek.dshstudio.runtime;

import com.deepseek.dshstudio.util.DshUtil;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link DshRuntimeManager#nodeCanReadRuntime} 的测试：让 node 自己判断一份运行时能不能读。
 * <p>
 * 关键：企业透明加密（DLP）按进程白名单工作，java 写出来的文件会被加密，于是「明文 fixture」
 * 用 JVM 写出来在本机其实是密文。所以 fixture 一律用 node 写（node 写出来的文件 node 自己能读），
 * 这样才测得到「可读」的那条分支；「不可读」分支则直接喂一个带 E-SafeNet 头的文件。
 */
public class DshRuntimeNodeCheckTest {

    private Path dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("dsh-nodecheck");
        DshRuntimeManager.setNodeExecutableForTest(null);
    }

    @After
    public void tearDown() {
        DshRuntimeManager.setNodeExecutableForTest(null);
        deleteQuietly(dir);
    }

    private static void deleteQuietly(Path p) {
        try {
            if (p != null && Files.exists(p)) {
                List<Path> all = new ArrayList<>();
                try (Stream<Path> s = Files.walk(p)) {
                    s.forEach(all::add);
                }
                all.sort((a, b) -> b.getNameCount() - a.getNameCount());
                for (Path f : all) {
                    try { Files.deleteIfExists(f); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    /** 没有 node 的机器上跳过，避免误报。 */
    private static String requireNode() {
        String node = DshUtil.resolveNodeExecutable();
        Assume.assumeTrue("本机未检测到 node，跳过 node 可读性自检测试",
                node != null && !node.trim().isEmpty());
        return node;
    }

    @Test
    public void plaintextRuntimeIsReadable() throws Exception {
        String node = requireNode();
        // 用 node 写 fixture：node 写的文件 node 自己能读（不受本机 DLP 影响）
        writeFixtureViaNode(node, dir);
        assertTrue("node 应当能读取 node 写出来的运行时", DshRuntimeManager.nodeCanReadRuntime(dir));
    }

    @Test
    public void ciphertextRuntimeIsNotReadable() throws Exception {
        requireNode();
        Path pkg = dir.resolve(DshRuntimeManager.DSH_PACKAGE);
        Path entry = dir.resolve(DshRuntimeManager.DSH_ENTRY);
        Files.createDirectories(pkg.getParent());
        Files.createDirectories(entry.getParent());
        // 直接塞一个 E-SafeNet 头：无论 DLP 是否再加密，node 都能识别成密文
        // （node 只检查前 64 字节里的 E-SafeNet 头，后面写什么无所谓，用不带引号的字符串避开转义坑）
        Files.write(pkg, "E-SafeNet-placeholder-package-json-corrupt"
                .getBytes(StandardCharsets.UTF_8));
        Files.write(entry, "E-SafeNet-placeholder-entry-corrupt"
                .getBytes(StandardCharsets.UTF_8));
        assertFalse("node 应当拒绝读取被加密的运行时", DshRuntimeManager.nodeCanReadRuntime(dir));
    }

    /** 让 node 在 dir 下写出一份最小但合法的 dsh 运行时（package.json + 入口脚本）。 */
    private static void writeFixtureViaNode(String node, Path dir) throws Exception {
        String script =
                "const fs=require('fs');const path=require('path');"
                + "const base=process.argv[1];"
                + "fs.mkdirSync(path.join(base,'node_modules/@deepseek-ai/dsh/lib'),{recursive:true});"
                + "fs.writeFileSync(path.join(base,'node_modules/@deepseek-ai/dsh/package.json'),JSON.stringify({name:'x',version:'1.0.0'}));"
                + "fs.writeFileSync(path.join(base,'node_modules/@deepseek-ai/dsh/lib/bin.js'),'#!/usr/bin/env node console.log(1);');";
        List<String> cmd = new ArrayList<>();
        cmd.add(node);
        cmd.add("-e");
        cmd.add(script);
        cmd.add(dir.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        boolean ok = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!ok || p.exitValue() != 0) {
            throw new IllegalStateException("node 写 fixture 失败（exit="
                    + (ok ? p.exitValue() : "timeout") + "）: " + sb);
        }
    }
}
