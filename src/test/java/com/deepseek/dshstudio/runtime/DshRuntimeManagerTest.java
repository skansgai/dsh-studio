package com.deepseek.dshstudio.runtime;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link DshRuntimeManager} 的纯逻辑测试（不依赖网络、不依赖 IDE 运行时）。
 * <p>
 * 早期版本把一份裁剪过的 dsh（约 80MB / 1.8 万个文件）打进插件包，这里的集成测试直接解包那份真
 * zip。0.4.2 起插件包不再内置运行时，所以改成：用内存里造的小 zip 测解包逻辑，用纯 Java 的
 * {@link DshRuntimeManager#verifyExtractedTree} 测 DLP 加密自检（不需要 node）。
 */
public class DshRuntimeManagerTest {

    @Test
    public void humanSizeIsReadable() {
        assertEquals("—", DshRuntimeManager.humanSize(0));
        assertEquals("512 B", DshRuntimeManager.humanSize(512));
        assertEquals("1.0 KB", DshRuntimeManager.humanSize(1024));
        assertEquals("1.5 MB", DshRuntimeManager.humanSize(1024 * 1024 * 3 / 2));
    }

    /**
     * 解包逻辑：普通条目照常解出，zip slip 条目（{@code ../}）不能写到目标目录外。
     * <p>
     * 不依赖被 DLP 加密的真 zip —— 造一个最小 zip 即可覆盖「解包 + 路径穿越防护」。
     */
    @Test
    public void extractZipSkipsEntriesThatEscapeTargetDirectory() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("../escaped.txt"));
            zos.write("pwned".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("ok.txt"));
            zos.write("ok".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(DshRuntimeManager.DSH_ENTRY));
            zos.write("#!/usr/bin/env node\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Path dest = Files.createTempDirectory("dsh-runtime-it");
        try {
            DshRuntimeManager.extractZip(new ByteArrayInputStream(bos.toByteArray()), dest, 3, null);
            assertTrue("正常条目照常解出", Files.isRegularFile(dest.resolve("ok.txt")));
            assertTrue("dsh 入口照常解出", Files.isRegularFile(dest.resolve(DshRuntimeManager.DSH_ENTRY)));
            Path escaped = dest.getParent().resolve("escaped.txt");
            try {
                assertTrue("zip slip 条目不能写到目标目录外: " + escaped, !Files.exists(escaped));
            } finally {
                Files.deleteIfExists(escaped);
            }
        } finally {
            deleteQuietly(dest);
        }
    }

    /**
     * 入口脚本合法（以 {@code #!} 开头、是合法 UTF-8）时自检通过。
     */
    @Test
    public void verifyExtractedTreeAcceptsValidEntry() throws Exception {
        Path dir = Files.createTempDirectory("dsh-verify-ok");
        try {
            Path entry = dir.resolve(DshRuntimeManager.DSH_ENTRY);
            Files.createDirectories(entry.getParent());
            Files.writeString(entry, "#!/usr/bin/env node\nconsole.log('dsh');\n",
                    StandardCharsets.UTF_8);
            DshRuntimeManager.verifyExtractedTree(dir); // 不应抛异常
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * 入口脚本被本机透明加密（DLP，文件头是 {@code E-SafeNet}）时，自检必须当场拒收并说清原因。
     * <p>
     * 这是 0.4.1 修过的场景：解包出的文件被加密后 node 读到的是密文，与其把一堆看不懂的解析错误
     * 甩给用户，不如在这里失败并指引出路。
     */
    @Test
    public void verifyExtractedTreeRejectsEncryptedEntry() throws Exception {
        Path dir = Files.createTempDirectory("dsh-verify-cipher");
        try {
            Path entry = dir.resolve(DshRuntimeManager.DSH_ENTRY);
            Files.createDirectories(entry.getParent());
            Files.writeString(entry, "E-SafeNet 这不是 JS 而是密文",
                    StandardCharsets.UTF_8);
            IOException e = assertThrows(IOException.class,
                    () -> DshRuntimeManager.verifyExtractedTree(dir));
            assertTrue("要指出是透明加密导致: " + e.getMessage(),
                    e.getMessage().contains("透明加密"));
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * 入口脚本缺失时，自检报「解包不完整」而不是默默继续。
     */
    @Test
    public void verifyExtractedTreeRejectsMissingEntry() throws Exception {
        Path dir = Files.createTempDirectory("dsh-verify-missing");
        try {
            IOException e = assertThrows(IOException.class,
                    () -> DshRuntimeManager.verifyExtractedTree(dir));
            assertTrue("要指出是解包不完整: " + e.getMessage(),
                    e.getMessage().contains("解包不完整"));
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 递归删除（不跟进符号链接），避免测试在临时目录里留下 300+ MB。 */
    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 尽力而为
                }
            }
        } catch (Exception ignored) {
            // 尽力而为
        }
    }
}
