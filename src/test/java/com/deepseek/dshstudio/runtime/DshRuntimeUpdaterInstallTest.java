package com.deepseek.dshstudio.runtime;

import com.deepseek.dshstudio.runtime.DshRuntimeUpdater.UpdateInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 热更新安装链路的测试：下载 → stamp 校验 → 解包 → 自检 → 原子改名 → 清理。
 * <p>
 * 这一段是 {@link DshRuntimeUpdater} 里唯一会写盘、改目录名的部分，也恰恰是最难在用户机器上
 * 复现的部分（断网、包被截断、IDE 被强杀留下半个暂存目录）。所以这里不做 mock：真的把 zip
 * 写到临时目录，用 {@code file:} URL 冒充发布渠道，把整条链路跑完再断言磁盘上的结果。
 * <p>
 * 用 {@code file:} 而不是本地 HTTP 服务，是为了不依赖监听端口 —— 端口在 CI、沙箱和开发机上
 * 都不一定绑得上，那会让测试变成间歇性失败，而这条链路本身跟传输协议无关。
 * <p>
 * 断言全部落在「用户会看到什么」上：目录有没有建出来、标记文件里的 stamp 对不对、
 * 失败后有没有留下半个版本目录骗过下一次启动。
 */
public class DshRuntimeUpdaterInstallTest {

    /** 一个「真」入口脚本该有的样子：以 #! 开头、是合法 UTF-8。 */
    private static final String NODE_ENTRY = "#!/usr/bin/env node\nconsole.log('dsh');\n";

    /** 每个用例一个空运行时根（模拟用户的运行时目录）。 */
    private Path root;
    /** 冒充发布渠道：zip 与 meta 就放在这里。 */
    private Path assets;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("dsh-install-root");
        assets = Files.createTempDirectory("dsh-install-assets");
    }

    @After
    public void tearDown() {
        deleteQuietly(root);
        deleteQuietly(assets);
    }

    // ── 正常安装 ──────────────────────────────────────────────────────────

    @Test
    public void installsAndMarksUnpackedWithStamp() throws Exception {
        byte[] zip = runtimeZip("1.2.3", NODE_ENTRY, true);
        String stamp = sha256Prefix(zip);
        UpdateInfo info = publish("1.2.3", zip, stamp, 3);

        Path target = DshRuntimeUpdater.installInto(root, info, null);

        assertEquals("应解到 <运行时根>/<版本> 下", root.resolve("1.2.3"), target);
        assertTrue("入口脚本必须存在，否则启动命令无从下手",
                Files.isRegularFile(target.resolve(DshRuntimeManager.DSH_ENTRY)));
        assertEquals("解包标记必须记下 zip 的 stamp（下次启动靠它判断这份是不是完整）",
                stamp, Files.readString(target.resolve(DshRuntimeManager.UNPACK_MARKER)).trim());
        assertEquals("安装成功后不该留下任何中间产物（半个 zip 会白占 40MB）",
                List.of(), incoming(root));
    }

    /**
     * 已装好的版本必须直接复用。
     * <p>
     * 做法是把「发布渠道」整个删掉：如果第二次调用还去下载，就会以文件不存在失败。
     * 这比断言「没有网络请求」更硬 —— 用户重复点「立即更新」时不该重新下 40MB。
     */
    @Test
    public void reusesAlreadyInstalledVersionWithoutDownloading() throws Exception {
        byte[] zip = runtimeZip("1.2.3", NODE_ENTRY, true);
        UpdateInfo info = publish("1.2.3", zip, sha256Prefix(zip), 3);
        Path first = DshRuntimeUpdater.installInto(root, info, null);

        deleteQuietly(assets);
        Files.createDirectories(assets);

        Path second = DshRuntimeUpdater.installInto(root, info, null);
        assertEquals("已装好的版本应直接复用", first, second);
    }

    // ── 失败路径：不能留下「看起来装好了」的目录 ──────────────────────────

    @Test
    public void rejectsStampMismatchAndLeavesNoTrace() throws Exception {
        byte[] zip = runtimeZip("1.2.3", NODE_ENTRY, true);
        Path zipFile = writeAsset("dsh-runtime.zip", zip);
        Path meta = writeMeta("runtime-meta.txt", "1.2.3", "deadbeef", 3);
        // 前提校验：meta 本身必须读得出来。本机若被 DLP 加密过，stamp 会解析成 null，
        // 校验被跳过，这条用例就会「假通过」—— 先在这里把这种情况暴露出来。
        assertEquals("deadbeef", DshRuntimeUpdater.metaString(meta, "stamp"));
        UpdateInfo info = info("1.2.3", zipFile, meta);

        IOException e = assertThrows(IOException.class,
                () -> DshRuntimeUpdater.installInto(root, info, null));

        assertTrue("报错要说清是校验失败，而不是别的: " + e.getMessage(),
                e.getMessage().contains("校验失败"));
        assertFalse("校验失败不能留下版本目录（否则下次启动会拿它当完整的用）",
                Files.exists(root.resolve("1.2.3")));
        assertEquals("校验失败也要把中间产物清干净", List.of(), incoming(root));
    }

    /**
     * 上次被强杀（IDE 崩溃 / 断电）会留下半个暂存目录。
     * <p>
     * 如果解包前不清它，脏树会被直接改名成正式版本目录 —— 而它可能缺文件，
     * 用户看到的是「运行时起不来」这种没法自证的现象。
     */
    @Test
    public void clearsStaleStagingDirectoryFromKilledInstall() throws Exception {
        Path stale = root.resolve(".incoming-1.2.3");
        Files.createDirectories(stale.resolve(DshRuntimeManager.DSH_ENTRY).getParent());
        Files.writeString(stale.resolve("junk.txt"), "half written", StandardCharsets.UTF_8);
        Files.writeString(stale.resolve(DshRuntimeManager.DSH_ENTRY), "旧的半成品",
                StandardCharsets.UTF_8);

        byte[] zip = runtimeZip("1.2.3", NODE_ENTRY, true);
        UpdateInfo info = publish("1.2.3", zip, sha256Prefix(zip), 3);

        Path target = DshRuntimeUpdater.installInto(root, info, null);

        assertFalse("脏暂存目录里的文件不能被带进正式版本目录",
                Files.exists(target.resolve("junk.txt")));
        assertEquals("解出来的入口必须是新的那份",
                NODE_ENTRY, Files.readString(target.resolve(DshRuntimeManager.DSH_ENTRY)));
        assertFalse("暂存目录应已被清理", Files.exists(stale));
        assertEquals(List.of(), incoming(root));
    }

    /**
     * 入口脚本被本机透明加密（DLP）过的包必须当场拒收。
     * <p>
     * 这类包发到客户机器上，node 读到的是密文、原生模块加载不了，用户只会看到
     * 「插件坏了」；在安装这一步失败并说清原因和出路，代价小得多。
     */
    @Test
    public void rejectsEncryptedEntryWithActionableMessage() throws Exception {
        byte[] zip = runtimeZip("1.2.3", "E-SafeNet\u0000\u0000b#e_密文", true);
        UpdateInfo info = publish("1.2.3", zip, sha256Prefix(zip), 3);

        IOException e = assertThrows(IOException.class,
                () -> DshRuntimeUpdater.installInto(root, info, null));

        assertTrue("要指出是透明加密导致、并给出出路: " + e.getMessage(),
                e.getMessage().contains("透明加密"));
        assertFalse("自检失败的版本不能留下版本目录", Files.exists(root.resolve("1.2.3")));
        assertEquals(List.of(), incoming(root));
    }

    @Test
    public void rejectsZipMissingEntryScript() throws Exception {
        byte[] zip = runtimeZip("1.2.3", NODE_ENTRY, false);
        UpdateInfo info = publish("1.2.3", zip, sha256Prefix(zip), 2);

        IOException e = assertThrows(IOException.class,
                () -> DshRuntimeUpdater.installInto(root, info, null));

        assertTrue("要指出是解包不完整: " + e.getMessage(),
                e.getMessage().contains("解包不完整"));
        assertFalse(Files.exists(root.resolve("1.2.3")));
    }

    /**
     * 版本号会直接拼进路径，非法值必须在**任何下载之前**就被拒掉。
     * <p>
     * 其中空串最危险：{@code Path.resolve("")} 返回的是根目录本身，不是根下的子目录 ——
     * 放过去就会把整个运行时根当成版本目录来写。
     */
    @Test
    public void installRejectsUnsafeVersionBeforeAnyDownload() throws Exception {
        for (String bad : List.of("", ".", "..", "../evil", "a/b", "a\\b", ".hidden")) {
            UpdateInfo info = new UpdateInfo(bad, "runtime-" + bad, "file:///does-not-exist",
                    "file:///does-not-exist", 0, "", null);
            IOException e = assertThrows("版本号 [" + bad + "] 应被拒绝",
                    IOException.class, () -> DshRuntimeUpdater.installInto(root, info, null));
            assertTrue(e.getMessage().contains("非法的版本号"));
        }
        assertEquals("被拒绝的版本号不该在运行时根里留下任何东西",
                List.of(), listNames(root));
    }

    // ── 版本扫描与回滚 ────────────────────────────────────────────────────

    @Test
    public void scanInstalledVersionsSkipsBaselineHiddenAndIncomplete() throws Exception {
        writeInstalled(root.resolve("baseline-abc12345"), "9.9.9");
        writeInstalled(root.resolve(".incoming-3.0.0"), "3.0.0");
        writeInstalled(root.resolve("1.0.0"), "1.0.0");
        writeInstalled(root.resolve("2.0.0"), "2.0.0");
        // 半个安装：一个只有空目录，一个只有入口、没有 package.json
        Files.createDirectories(root.resolve("4.0.0"));
        writeEntryOnly(root.resolve("5.0.0"));

        assertEquals("只列真正装好的热更新版本，新的在前；内置基线和暂存目录不算",
                List.of("2.0.0", "1.0.0"), DshRuntimeUpdater.scanInstalledVersions(root));
    }

    @Test
    public void removeVersionDeletesOnlyThatVersionAndRejectsTraversal() throws Exception {
        writeInstalled(root.resolve("1.0.0"), "1.0.0");
        writeInstalled(root.resolve("2.0.0"), "2.0.0");
        // 运行时根外面放一个「受害者」，用来证明路径穿越真的被挡住了
        Path victim = root.getParent().resolve(root.getFileName() + "-victim");
        Files.createDirectories(victim);
        try {
            for (String bad : List.of("", ".", "..", "../" + victim.getFileName(),
                    "a/b", "a\\b", ".hidden")) {
                assertThrows("版本号 [" + bad + "] 应被拒绝", IOException.class,
                        () -> DshRuntimeUpdater.removeVersionAt(root, bad));
            }
            assertTrue("穿越尝试不能碰到运行时根外面的东西", Files.isDirectory(victim));
            assertTrue("穿越尝试不能误删别的版本", Files.isDirectory(root.resolve("2.0.0")));

            DshRuntimeUpdater.removeVersionAt(root, "1.0.0");
            assertFalse("指定的版本应被删掉", Files.exists(root.resolve("1.0.0")));
            assertTrue("其它版本不受影响", Files.isDirectory(root.resolve("2.0.0")));
            // 不存在的版本静默通过：回滚时重复点不该报错
            DshRuntimeUpdater.removeVersionAt(root, "9.9.9");
        } finally {
            deleteQuietly(victim);
        }
    }

    // ── 解包本身 ──────────────────────────────────────────────────────────

    /** zip 条目名里的 {@code ../} 不能写到目标目录外面去。 */
    @Test
    public void extractZipSkipsEntriesThatEscapeTargetDirectory() throws Exception {
        Path dest = Files.createTempDirectory("dsh-zipslip");
        try {
            String escapedName = dest.getFileName() + "-escaped.txt";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                zos.putNextEntry(new ZipEntry("../" + escapedName));
                zos.write("pwned".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("ok.txt"));
                zos.write("ok".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            DshRuntimeManager.extractZip(new ByteArrayInputStream(bos.toByteArray()), dest, 0, null);

            assertTrue("正常条目照常解出", Files.isRegularFile(dest.resolve("ok.txt")));
            Path escaped = dest.getParent().resolve(escapedName);
            try {
                assertFalse("zip slip 条目不能写到目标目录外: " + escaped, Files.exists(escaped));
            } finally {
                Files.deleteIfExists(escaped);
            }
        } finally {
            deleteQuietly(dest);
        }
    }

    // ── 造包 / 造发布渠道 ─────────────────────────────────────────────────

    /**
     * 造一个最小但「合法」的运行时 zip：入口脚本 + package.json + 可执行位清单。
     * <p>
     * 不用构建产物里的真 zip（80+ MB / 1.8 万个文件）：这里要验的是安装逻辑，
     * 不是包的内容，用小包才能把每个失败分支都跑到。真 zip 的解包由
     * {@code DshRuntimeManagerTest} 覆盖。
     */
    private static byte[] runtimeZip(String version, String entryContent, boolean includeEntry)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            if (includeEntry) {
                put(zos, DshRuntimeManager.DSH_ENTRY, entryContent);
            }
            put(zos, DshRuntimeManager.DSH_PACKAGE,
                    "{\"name\":\"@deepseek-ai/dsh\",\"version\":\"" + version + "\"}");
            put(zos, ".dsh-runtime-executables", "");
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /** 把 zip 与 meta 落到「发布渠道」目录，返回可用的 {@link DshRuntimeUpdater.UpdateInfo}。 */
    private UpdateInfo publish(String version, byte[] zip, String stamp, int entries)
            throws IOException {
        return info(version, writeAsset("dsh-runtime.zip", zip),
                writeMeta("runtime-meta.txt", version, stamp, entries));
    }

    private static UpdateInfo info(String version, Path zip, Path meta) throws IOException {
        return new UpdateInfo(version, "runtime-" + version,
                zip.toUri().toString(), meta.toUri().toString(),
                Files.size(zip), "https://example.invalid/releases", null);
    }

    private Path writeAsset(String name, byte[] content) throws IOException {
        Path p = assets.resolve(name);
        Files.write(p, content);
        return p;
    }

    private Path writeMeta(String name, String version, String stamp, int entries)
            throws IOException {
        Path p = assets.resolve(name);
        Files.writeString(p, "{\n  \"dshVersion\": \"" + version + "\",\n"
                + "  \"stamp\": \"" + stamp + "\",\n"
                + "  \"targets\": [\"win32-x64\"],\n"
                + "  \"entries\": " + entries + "\n}\n", StandardCharsets.UTF_8);
        return p;
    }

    /** 造一个「已装好」的版本目录：入口 + package.json 都在。 */
    private static void writeInstalled(Path dir, String version) throws IOException {
        writeEntryOnly(dir);
        Files.writeString(dir.resolve(DshRuntimeManager.DSH_PACKAGE),
                "{\"name\":\"@deepseek-ai/dsh\",\"version\":\"" + version + "\"}",
                StandardCharsets.UTF_8);
    }

    private static void writeEntryOnly(Path dir) throws IOException {
        Path entry = dir.resolve(DshRuntimeManager.DSH_ENTRY);
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, NODE_ENTRY, StandardCharsets.UTF_8);
    }

    // ── 断言辅助 ──────────────────────────────────────────────────────────

    /**
     * 独立算一遍 sha256 的前 8 位，**不复用被测实现**。
     * <p>
     * 复用就是拿实现验实现：算法写错了测试照样通过。
     */
    private static String sha256Prefix(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        return sb.toString();
    }

    /** 运行时根下遗留的下载 / 解包中间产物（都以 {@code .incoming-} 开头）。 */
    private static List<String> incoming(Path root) throws IOException {
        try (Stream<Path> stream = Files.list(root)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(".incoming-"))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> listNames(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
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
