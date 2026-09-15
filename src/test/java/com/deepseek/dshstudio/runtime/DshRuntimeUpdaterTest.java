package com.deepseek.dshstudio.runtime;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link DshRuntimeUpdater} 的纯逻辑测试。
 * <p>
 * 只测不需要网络、也不需要 IDE 运行时的部分：sha256 前缀（必须与构建脚本写进 meta 的
 * stamp 同一算法，否则每次下载都会被判成「校验失败」）与 meta 解析。
 */
public class DshRuntimeUpdaterTest {

    /**
     * sha256 的标准测试向量。
     * <p>
     * 这里刻意用公开向量而不是「用 MessageDigest 再算一遍对比」——后者是拿实现验实现，
     * 改错算法也照样通过。
     */
    @Test
    public void sha256PrefixMatchesStandardVectors() throws Exception {
        Path empty = Files.createTempFile("dsh-updater-empty", ".bin");
        Path abc = Files.createTempFile("dsh-updater-abc", ".bin");
        try {
            Files.write(empty, new byte[0]);
            Files.write(abc, "abc".getBytes(StandardCharsets.UTF_8));

            // sha256("")  = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
            assertEquals("e3b0c442", DshRuntimeUpdater.sha256Prefix(empty));
            assertEquals("ba7816bf", DshRuntimeUpdater.sha256Prefix(abc));
        } finally {
            Files.deleteIfExists(empty);
            Files.deleteIfExists(abc);
        }
    }

    /** 大文件（超过一个读缓冲）也要算对——分块读取最容易在这里出错。 */
    @Test
    public void sha256PrefixHandlesFilesLargerThanBuffer() throws Exception {
        Path big = Files.createTempFile("dsh-updater-big", ".bin");
        try {
            byte[] data = new byte[300_000];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % 251);
            }
            Files.write(big, data);
            String prefix = DshRuntimeUpdater.sha256Prefix(big);
            assertEquals(8, prefix.length());
            // 同样的内容写两次必须得到同样的前缀（确定性）
            Path copy = Files.createTempFile("dsh-updater-big-copy", ".bin");
            try {
                Files.write(copy, data);
                assertEquals(prefix, DshRuntimeUpdater.sha256Prefix(copy));
            } finally {
                Files.deleteIfExists(copy);
            }
        } finally {
            Files.deleteIfExists(big);
        }
    }

    @Test
    public void metaParsingReadsFieldsAndToleratesJunk() throws Exception {
        Path meta = Files.createTempFile("dsh-updater-meta", ".txt");
        try {
            Files.writeString(meta, "{\n  \"dshVersion\": \"0.1.5-rc.1\",\n"
                    + "  \"stamp\": \"ba7816bf\",\n  \"entries\": 18390,\n"
                    + "  \"unpackedBytes\": 251000000\n}\n", StandardCharsets.UTF_8);
            assertEquals("0.1.5-rc.1", DshRuntimeUpdater.metaString(meta, "dshVersion"));
            assertEquals("ba7816bf", DshRuntimeUpdater.metaString(meta, "stamp"));
            assertEquals(18390, DshRuntimeUpdater.metaInt(meta, "entries"));
            // 缺字段不该抛异常，返回 null / 0 让调用方自己决定怎么办
            assertNull(DshRuntimeUpdater.metaString(meta, "不存在的字段"));
            assertEquals(0, DshRuntimeUpdater.metaInt(meta, "不存在的字段"));

            // 内容坏掉（例如被 DLP 加密过）也不能抛异常
            Path broken = Files.createTempFile("dsh-updater-broken", ".txt");
            try {
                Files.write(broken, "E-SafeNet 这不是 JSON".getBytes(StandardCharsets.UTF_8));
                assertNull(DshRuntimeUpdater.metaString(broken, "stamp"));
                assertEquals(0, DshRuntimeUpdater.metaInt(broken, "entries"));
            } finally {
                Files.deleteIfExists(broken);
            }
        } finally {
            Files.deleteIfExists(meta);
        }
    }
}
