package com.deepseek.dshstudio.runtime;

import com.deepseek.dshstudio.util.DshUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 运行时热更新：检查新版、下载、解包、切换、回滚。
 * <p>
 * <b>为什么从我们自己的发布渠道下，而不是从 npm 拉依赖：</b>dsh 的 72 个直接依赖里 69 个是
 * 一方包，但全部用 {@code ^x.y.z} 范围声明（还带 prerelease）。要在 Java 里正确地解析
 * semver 范围 + 递归依赖 + 平台过滤，等于重写半个 npm，而且会与构建链路形成两套实现。
 * 这里改为复用**同一条** Gradle 链路（{@code bundleDshRuntime}）的产物 —— 由
 * {@code .github/workflows/runtime-release.yml} 定时构建并挂到 GitHub Release 上，
 * 插件只负责下载一个已经裁剪、自检过、按平台拆好的包。
 * <p>
 * 发布约定（与 CI 工作流一一对应）：
 * <ul>
 *   <li>标签 {@code runtime-<dshVersion>}，例如 {@code runtime-0.1.5-rc.1}；</li>
 *   <li>资产 {@code dsh-runtime-<target>.zip} 与 {@code runtime-meta-<target>.txt}
 *       （{@code target} 形如 {@code win32-x64}）。</li>
 * </ul>
 * <p>
 * 下载的包解到 {@code <运行时根>/<dshVersion>/}，与内置基线（{@code baseline-<stamp>/}）
 * 并列。{@link DshRuntimeManager#resolve} 在自动模式下优先用热更新版本，而「仅内置」模式
 * 就是它的回滚出口 —— 所以回滚不需要任何额外机制，改一下设置即可。
 */
public final class DshRuntimeUpdater {

    /** 发布渠道：本仓库的 GitHub Releases。 */
    private static final String RELEASES_API =
            "https://api.github.com/repos/skansgai/dsh-studio/releases?per_page=30";

    /** 运行时 Release 的标签前缀。同一个仓库里还有插件自身的 Release，靠它区分。 */
    private static final String TAG_PREFIX = "runtime-";

    private static final String ZIP_ASSET = "dsh-runtime-%s.zip";
    private static final String META_ASSET = "runtime-meta-%s.txt";

    /** 下载与解包的中间产物前缀；以点开头，所以不会被当成已安装的版本目录。 */
    private static final String TEMP_PREFIX = ".incoming-";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int COPY_BUFFER = 1 << 16;

    public static DshRuntimeUpdater getInstance() {
        return ApplicationManager.getApplication().getService(DshRuntimeUpdater.class);
    }

    // ── 检查 ──────────────────────────────────────────────────────────────

    /** 一个可用的新版本。 */
    public static final class UpdateInfo {
        /** 新版本的 dsh 版本号，同时用作安装目录名。 */
        public final String dshVersion;
        public final String tag;
        public final String zipUrl;
        public final String metaUrl;
        /** zip 的字节数（GitHub 给的，用于在对话框里写清体积）。 */
        public final long zipBytes;
        /** Release 页面地址，供用户手动查看。 */
        public final String pageUrl;
        /** 当前生效的版本；无法确定时为 {@code null}。 */
        @Nullable
        public final String currentVersion;

        UpdateInfo(@NotNull String dshVersion, @NotNull String tag, @NotNull String zipUrl,
                   @NotNull String metaUrl, long zipBytes, @NotNull String pageUrl,
                   @Nullable String currentVersion) {
            this.dshVersion = dshVersion;
            this.tag = tag;
            this.zipUrl = zipUrl;
            this.metaUrl = metaUrl;
            this.zipBytes = zipBytes;
            this.pageUrl = pageUrl;
            this.currentVersion = currentVersion;
        }

        /** 面向用户的体积描述，如「37.2 MB」。 */
        @NotNull
        public String humanSize() {
            return zipBytes > 0 ? DshRuntimeManager.humanSize(zipBytes) : "未知大小";
        }
    }

    /**
     * 查发布渠道上有没有比当前生效版本更新的运行时。
     * <p>
     * 网络异常、解析失败、没有本平台的资产、或没有更新时一律返回 {@code null} ——
     * 这个调用在后台跑，不该因为网络问题打扰用户。
     */
    @Nullable
    public UpdateInfo checkForUpdate() {
        String json = DshUtil.httpGetText(RELEASES_API, CONNECT_TIMEOUT_MS);
        if (json == null || json.isEmpty()) {
            return null;
        }
        JsonArray releases;
        try {
            releases = JsonParser.parseString(json).getAsJsonArray();
        } catch (Exception e) {
            return null;
        }

        String target = DshUtil.hostTarget();
        String zipName = String.format(ZIP_ASSET, target);
        String metaName = String.format(META_ASSET, target);

        UpdateInfo best = null;
        for (JsonElement element : releases) {
            JsonObject release = element.getAsJsonObject();
            String tag = str(release, "tag_name");
            if (!tag.startsWith(TAG_PREFIX)) {
                continue;
            }
            String version = tag.substring(TAG_PREFIX.length());
            if (version.isEmpty() || !isSafeVersion(version)) {
                continue;
            }
            String zipUrl = null;
            String metaUrl = null;
            long zipBytes = 0;
            JsonElement assets = release.get("assets");
            if (assets != null && assets.isJsonArray()) {
                for (JsonElement a : assets.getAsJsonArray()) {
                    JsonObject asset = a.getAsJsonObject();
                    String name = str(asset, "name");
                    if (name.equals(zipName)) {
                        zipUrl = str(asset, "browser_download_url");
                        zipBytes = num(asset, "size");
                    } else if (name.equals(metaName)) {
                        metaUrl = str(asset, "browser_download_url");
                    }
                }
            }
            // 该版本没有本平台的资产（例如目标平台列表变了）——跳过，继续找更旧的
            if (zipUrl == null || zipUrl.isEmpty() || metaUrl == null || metaUrl.isEmpty()) {
                continue;
            }
            if (best == null || DshUtil.compareVersion(best.dshVersion, version) < 0) {
                best = new UpdateInfo(version, tag, zipUrl, metaUrl, zipBytes,
                        str(release, "html_url"), null);
            }
        }
        if (best == null) {
            return null;
        }

        String current = currentVersion();
        // 不比当前新就不打扰用户。当前版本未知（系统 dsh / 未解包）时仍然提示 ——
        // 那种情况下热更新本来就是用户想要的。
        if (current != null && DshUtil.compareVersion(best.dshVersion, current) <= 0) {
            return null;
        }
        return new UpdateInfo(best.dshVersion, best.tag, best.zipUrl, best.metaUrl,
                best.zipBytes, best.pageUrl, current);
    }

    /** 当前实际会用到的那份运行时的版本号；解析不出来时为 {@code null}。 */
    @Nullable
    public String currentVersion() {
        try {
            DshRuntimeManager.Launch launch =
                    DshRuntimeManager.getInstance().resolve(DshRuntimeMode.AUTO);
            return launch.version;
        } catch (Exception e) {
            return null;
        }
    }

    // ── 下载与安装 ────────────────────────────────────────────────────────

    /**
     * 下载并安装一个新版本，返回它的运行时目录。
     * <p>
     * 全过程写在临时文件里，最后一步才原子改名到位 —— 中途取消或断网不会留下半个版本目录
     * 让插件误以为装好了。
     *
     * @throws ProcessCanceledException 用户取消
     */
    @NotNull
    public Path downloadAndInstall(@NotNull UpdateInfo info,
                                   @Nullable ProgressIndicator indicator) throws IOException {
        DshRuntimeManager rt = DshRuntimeManager.getInstance();
        Path root = rt.runtimeRoot();
        Files.createDirectories(root);
        Path target = root.resolve(info.dshVersion);

        // 已经装好了就直接复用（重复点击、上次装完还没重启等）
        if (Files.isRegularFile(target.resolve(DshRuntimeManager.UNPACK_MARKER))
                && Files.isRegularFile(target.resolve(DshRuntimeManager.DSH_ENTRY))) {
            return target;
        }

        Path zip = root.resolve(TEMP_PREFIX + info.dshVersion + ".zip");
        Path meta = root.resolve(TEMP_PREFIX + info.dshVersion + ".txt");
        Path staging = root.resolve(TEMP_PREFIX + info.dshVersion);
        try {
            if (indicator != null) {
                indicator.setText("正在下载运行时元数据…");
            }
            download(info.metaUrl, meta, 0, 0, indicator);

            if (indicator != null) {
                indicator.setText("正在下载 dsh 运行时 " + info.dshVersion + "（" + info.humanSize() + "）…");
            }
            download(info.zipUrl, zip, info.zipBytes, 0, indicator);

            String stamp = metaString(meta, "stamp");
            String actual = sha256Prefix(zip);
            // 校验能同时挡住「下载被截断」和「中途被代理塞了别的东西」两种情况。
            if (stamp != null && !stamp.isEmpty() && !stamp.equals(actual)) {
                throw new IOException("下载的运行时包校验失败：元数据里的 stamp 是 "
                        + stamp + "，实际算出来是 " + actual);
            }

            if (indicator != null) {
                indicator.setText("正在解包 dsh 运行时…");
            }
            // 上次被强杀（IDE 崩溃 / 断电）可能留下半个暂存目录，先清掉再解，
            // 否则会把脏树改名成正式版本目录，而它可能缺文件。
            DshRuntimeManager.deleteRecursivelyQuietly(staging);
            Files.createDirectories(staging);
            try (InputStream in = Files.newInputStream(zip)) {
                DshRuntimeManager.extractZip(in, staging, metaInt(meta, "entries"), indicator);
            }
            // 和内置基线走同一套自检：入口存在、不是 DLP 密文、是合法 UTF-8、以 #! 开头
            DshRuntimeManager.verifyExtractedTree(staging);
            Files.writeString(staging.resolve(DshRuntimeManager.UNPACK_MARKER), actual,
                    StandardCharsets.UTF_8);
            DshRuntimeManager.applyExecutableBits(staging);
            DshRuntimeManager.moveInto(staging, target);
            return target;
        } finally {
            DshRuntimeManager.deleteRecursivelyQuietly(staging);
            deleteQuietly(zip);
            deleteQuietly(meta);
        }
    }

    // ── 已安装版本 / 回滚 ─────────────────────────────────────────────────

    /** 已安装的热更新版本，新的在前。 */
    @NotNull
    public List<String> installedVersions() {
        Path root = DshRuntimeManager.getInstance().runtimeRoot();
        List<String> versions = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return versions;
        }
        try (Stream<Path> stream = Files.list(root)) {
            for (Path dir : stream.toList()) {
                String name = dir.getFileName().toString();
                if (name.startsWith("baseline-") || name.startsWith(".") || !Files.isDirectory(dir)) {
                    continue;
                }
                if (!Files.isRegularFile(dir.resolve(DshRuntimeManager.DSH_ENTRY))) {
                    continue;
                }
                String version = DshRuntimeManager.readVersion(dir);
                if (version != null) {
                    versions.add(version);
                }
            }
        } catch (IOException ignored) {
            // 扫描失败就当作没有
        }
        versions.sort((a, b) -> DshUtil.compareVersion(b, a));
        return versions;
    }

    /** 删除一个已安装的热更新版本（回滚用）；不存在时什么也不做。 */
    public void removeVersion(@NotNull String version) throws IOException {
        if (!isSafeVersion(version)) {
            throw new IOException("非法的版本号：" + version);
        }
        Path dir = DshRuntimeManager.getInstance().runtimeRoot().resolve(version);
        if (Files.isDirectory(dir)) {
            DshRuntimeManager.deleteRecursively(dir);
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────

    /**
     * 版本号会直接当作目录名，必须挡住路径穿越。
     * 它来自我们自己发布的 tag，但这里不该依赖上游的自觉。
     */
    private static boolean isSafeVersion(@NotNull String version) {
        return !version.contains("/") && !version.contains("\\")
                && !version.contains("..") && !version.startsWith(".");
    }

    private static void download(@NotNull String url, @NotNull Path dest, long expectedBytes,
                                 long alreadyDone, @Nullable ProgressIndicator indicator)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("User-Agent", "DshStudio-RuntimeUpdater");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("下载失败：HTTP " + code + "（" + url + "）");
            }
            long total = expectedBytes > 0 ? expectedBytes : connection.getContentLengthLong();
            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(dest)) {
                byte[] buffer = new byte[COPY_BUFFER];
                long done = alreadyDone;
                while (true) {
                    if (indicator != null && indicator.isCanceled()) {
                        throw new ProcessCanceledException();
                    }
                    int n = in.read(buffer);
                    if (n < 0) {
                        break;
                    }
                    out.write(buffer, 0, n);
                    done += n;
                    if (indicator != null && total > 0) {
                        indicator.setFraction(Math.min(0.99, (double) done / total));
                    }
                }
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** zip 内容的 sha256 前 8 位；与构建脚本写进 meta 的 stamp 同一算法。 */
    @NotNull
    static String sha256Prefix(@NotNull Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("找不到 SHA-256 实现", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[COPY_BUFFER];
            while (true) {
                int n = in.read(buffer);
                if (n < 0) {
                    break;
                }
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.substring(0, Math.min(8, sb.length()));
    }

    /** 从 meta（内容仍是 JSON，扩展名是 .txt 以避开 DLP）里取一个字符串字段。 */
    @Nullable
    static String metaString(@NotNull Path meta, @NotNull String key) {
        JsonObject o = readMeta(meta);
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    static int metaInt(@NotNull Path meta, @NotNull String key) {
        JsonObject o = readMeta(meta);
        if (o == null || !o.has(key)) {
            return 0;
        }
        try {
            return o.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    @Nullable
    private static JsonObject readMeta(@NotNull Path meta) {
        try {
            return JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteQuietly(@NotNull Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响使用
        }
    }

    private static String str(@NotNull JsonObject o, @NotNull String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static long num(@NotNull JsonObject o, @NotNull String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return 0;
        }
        try {
            return e.getAsLong();
        } catch (Exception ex) {
            return 0;
        }
    }
}
