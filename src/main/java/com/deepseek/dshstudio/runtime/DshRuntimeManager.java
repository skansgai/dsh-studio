package com.deepseek.dshstudio.runtime;

import com.deepseek.dshstudio.settings.DshSettingsState;
import com.deepseek.dshstudio.util.DshUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 内置 dsh 运行时的解包与解析。
 *
 * <h2>为什么要有它</h2>
 * 插件包里的 {@code dsh-runtime/dsh-runtime.zip} 是一棵裁剪过的 {@code node_modules}，
 * 里面含 dsh 及其全部依赖（含各平台原生模块）。它让用户「装上就能用」，不必先等
 * {@code npx} 下载十几分钟。zip 不能直接执行，所以首次使用时解包到用户目录。
 *
 * <h2>目录布局</h2>
 * <pre>
 * ~/.dshstudio/runtime/
 *   ├─ baseline-&lt;stamp&gt;/        内置基线（由插件包解出，只读语义）
 *   ├─ baseline-&lt;stamp&gt;.tmp/    解包中的临时目录，中断后下次自动清理
 *   └─ &lt;dshVersion&gt;/            运行时热更新下载的版本（优先级更高）
 * </pre>
 * {@code stamp} 来自构建产物 zip 的 sha256 前缀：dsh 版本、目标平台、sharp 模式、
 * 裁剪规则任一变化都会换一个新目录，因此「插件升级后要不要重新解包」是自动判断的，
 * 不需要额外记录状态。
 *
 * <h2>解析优先级</h2>
 * 热更新版本 → 内置基线 → 系统 dsh（见 {@link DshRuntimeMode}）。
 */
public final class DshRuntimeManager {

    private static final Logger LOG = Logger.getInstance(DshRuntimeManager.class);

    /** 插件包里的运行时压缩包。 */
    private static final String ZIP_RESOURCE = "/dsh-runtime/dsh-runtime.zip";

    /**
     * 插件包里的运行时元数据（由 Gradle 的 bundleDshRuntime 生成）。
     * <p>
     * 扩展名是 {@code .txt} 而不是 {@code .json}：内容就是 JSON，但企业里的透明加密
     * 客户端（DLP）会按扩展名把 {@code .json} 加密，构建产物一旦变密文就会被打进插件包，
     * 导致这里读不到自己的元数据。换成 {@code .txt} 绕开（构建期还有一道兜底校验）。
     */
    private static final String META_RESOURCE = "/dsh-runtime/runtime-meta.txt";

    /** 解包完成标记，内容为 stamp；存在即代表这棵树是完整的。 */
    static final String UNPACK_MARKER = ".unpacked-ok";

    /** 需要可执行位的文件清单（zip 不保留 Unix 权限位）。 */
    static final String EXEC_MANIFEST = ".dsh-runtime-executables";

    /** dsh 入口脚本（相对运行时根目录）。 */
    static final String DSH_ENTRY = "node_modules/@deepseek-ai/dsh/lib/bin.js";

    /** dsh 的 package.json（相对运行时根目录），用于读取版本号。 */
    static final String DSH_PACKAGE = "node_modules/@deepseek-ai/dsh/package.json";

    /** 内置基线目录的前缀；热更新目录用版本号命名，据此区分。 */
    private static final String BASELINE_PREFIX = "baseline-";

    /** 解包时的读缓冲；18k 个小文件，缓冲大一点能明显少几次系统调用。 */
    private static final int COPY_BUFFER = 1 << 16;

    /**
     * 安全软件（DLP）加密文件时写在文件头的明文标识。
     * <p>
     * 企业里的透明加密客户端（本机实测是 E-SafeNet）会按扩展名把 {@code .js} /
     * {@code .ts} / {@code .json} 就地加密。解包出来的文件读到这个头，说明拿到的是密文，
     * node 跑不起来。这里只用来给出可读的报错，不做任何解密。
     */
    private static final String CIPHERTEXT_MARKER = "E-SafeNet";

    /** 入口脚本的 shebang；正常解包出来必须以此开头。 */
    private static final String ENTRY_SHEBANG = "#!";

    /**
     * 用 node 自身校验一份解包好的运行时是否真的可读的内联脚本（经 {@code node -e} 传入，
     * 不落盘，因此不受本机透明加密影响）。详见 {@link #nodeCanReadRuntime}。
     * <p>
     * 刻意不用 {@code \n} 之外任何反斜杠，避免被各层字符串转义误伤；需要换行时用
     * {@code String.fromCharCode(10)}。
     */
    private static final String NODE_RUNTIME_CHECK_SCRIPT = """
        const fs = require('fs');
        const path = require('path');
        const MARK = Buffer.from('E-SafeNet');
        function head(p) {
          try { const fd = fs.openSync(p, 'r'); const b = Buffer.alloc(64);
            fs.readSync(fd, b, 0, 64, 0); fs.closeSync(fd); return b; }
          catch (e) { return null; }
        }
        function fail(msg) { process.stdout.write('CIPHERTEXT ' + msg + String.fromCharCode(10)); process.exit(3); }
        const pkg = process.argv[1], entry = process.argv[2], dir = process.argv[3];
        let h = head(pkg); if (!h) fail('no-package-json'); if (h.includes(MARK)) fail('package-json-ciphertext');
        try { JSON.parse(fs.readFileSync(pkg, 'utf8')); } catch (e) { fail('package-json-parse ' + e.message); }
        h = head(entry); if (!h) fail('no-entry'); if (h.includes(MARK)) fail('entry-ciphertext');
        if (!fs.readFileSync(entry, 'utf8').startsWith('#!')) fail('entry-no-shebang');
        let n = 0; const LIMIT = 400;
        function walk(d) {
          let ents; try { ents = fs.readdirSync(d, { withFileTypes: true }); } catch (e) { return; }
          for (const e of ents) { if (n >= LIMIT) return;
            const p = path.join(d, e.name);
            if (e.isDirectory()) { walk(p); continue; }
            const ext = path.extname(e.name).toLowerCase();
            if (ext !== '.js' && ext !== '.json' && ext !== '.ts') continue;
            n++;
            let b; const fd = fs.openSync(p, 'r');
            try { b = Buffer.alloc(64); fs.readSync(fd, b, 0, 64, 0); } finally { fs.closeSync(fd); }
            if (b.includes(MARK)) fail('scan ' + p);
          }
        }
        try { walk(dir); } catch (e) {}
        process.stdout.write('OK' + String.fromCharCode(10)); process.exit(0);
        """;

    /** 测试可注入的 node 可执行文件；为 {@code null} 时用 {@link DshUtil#resolveNodeExecutable()}。 */
    @Nullable
    private static String nodeExecutableOverride;

    /** 测试用：覆盖 node 可执行文件位置。 */
    static void setNodeExecutableForTest(@Nullable String exe) {
        nodeExecutableOverride = exe;
    }

    /** 最近一次 {@link #resolve} 留下的降级说明（如「内置运行时被 DLP 加密，已回退系统 dsh」）；消费后清空。 */
    private volatile String lastResolveNote;

    /** 读取并清空最近一次 resolve 留下的说明（{@code null} 表示没有）。 */
    @Nullable
    public String consumeLastResolveNote() {
        String note = lastResolveNote;
        lastResolveNote = null;
        return note;
    }

    /** 运行时可读性自检结果缓存（按目录），避免一次会话里反复拉起 node。 */
    private static final Map<Path, Boolean> NODE_READABLE_CACHE = new ConcurrentHashMap<>();

    private final Object metaLock = new Object();
    private volatile boolean metaResolved;
    @Nullable
    private volatile Meta metaCache;

    @NotNull
    public static DshRuntimeManager getInstance() {
        return ApplicationManager.getApplication().getService(DshRuntimeManager.class);
    }

    // ── 元数据 ────────────────────────────────────────────────────────────

    /** 插件包内置运行时的元数据。 */
    public static final class Meta {
        /** 内置的 dsh 版本，如 {@code 0.1.2-rc.1}。 */
        public final String dshVersion;
        /** 产物指纹（zip 的 sha256 前 8 位），同时用作解包目录名。 */
        public final String stamp;
        /** 构建时使用的 sharp 模式：native / hybrid / wasm。 */
        public final String sharp;
        /** 打包进去的目标平台，如 win32-x64。 */
        public final List<String> targets;
        /** 压缩包内的文件数（用于进度百分比）。 */
        public final int entries;
        /** 解包后的字节数（用于展示磁盘占用）。 */
        public final long unpackedBytes;
        /** 构建时间（ISO-8601）。 */
        public final String builtAt;

        Meta(String dshVersion, String stamp, String sharp, List<String> targets,
             int entries, long unpackedBytes, String builtAt) {
            this.dshVersion = dshVersion;
            this.stamp = stamp;
            this.sharp = sharp;
            this.targets = targets;
            this.entries = entries;
            this.unpackedBytes = unpackedBytes;
            this.builtAt = builtAt;
        }
    }

    /**
     * 读取内置运行时元数据；插件包里没有运行时（例如开发期用
     * {@code -Pdsh.runtime.skip=true} 构建）时返回 {@code null}。
     */
    @Nullable
    public Meta bundledMeta() {
        if (metaResolved) {
            return metaCache;
        }
        synchronized (metaLock) {
            if (metaResolved) {
                return metaCache;
            }
            metaCache = readMeta();
            metaResolved = true;
            return metaCache;
        }
    }

    @Nullable
    private static Meta readMeta() {
        try (InputStream in = DshRuntimeManager.class.getResourceAsStream(META_RESOURCE)) {
            if (in == null) {
                LOG.info("[dsh-runtime] 插件包中没有内置运行时（" + META_RESOURCE + " 不存在）");
                return null;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            List<String> targets = new ArrayList<>();
            JsonElement t = o.get("targets");
            if (t != null && t.isJsonArray()) {
                JsonArray arr = t.getAsJsonArray();
                for (JsonElement e : arr) {
                    targets.add(e.getAsString());
                }
            }
            return new Meta(
                    str(o, "dshVersion", ""),
                    str(o, "stamp", ""),
                    str(o, "sharp", ""),
                    targets,
                    num(o, "entries", 0),
                    num(o, "unpackedBytes", 0L),
                    str(o, "builtAt", ""));
        } catch (Exception e) {
            LOG.warn("[dsh-runtime] 内置运行时元数据解析失败", e);
            return null;
        }
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? def : e.getAsString();
    }

    private static int num(JsonObject o, String key, int def) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static long num(JsonObject o, String key, long def) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : def;
        } catch (Exception e) {
            return def;
        }
    }

    // ── 目录 ──────────────────────────────────────────────────────────────

    /**
     * 运行时根目录。
     * <p>
     * 默认（{@link DshRuntimeLocation#AUTO}）在 Windows 上落在系统临时目录，其余平台落在
     * 用户主目录。这不是随手定的 —— 实测企业安全软件（DLP）按路径范围做透明加密，
     * 用户目录 / 项目目录下写一个小文件要 ~85 ms（11 个/秒），而临时目录被排除在外
     * （2500 个/秒）。内置运行时 1.8 万个文件，两者的首次解包耗时相差 **27 分钟 vs 6 秒**。
     * 位置可在 设置 → DeepSeek Harness → 运行时 里改。
     */
    @NotNull
    public Path runtimeRoot() {
        return runtimeRootFor(DshRuntimeLocation.fromId(DshSettingsState.getInstance().runtimeLocation));
    }

    /** 指定位置策略下的运行时根目录（设置页预览用，避免必须点「应用」才刷新）。 */
    @NotNull
    public Path runtimeRootFor(@NotNull DshRuntimeLocation location) {
        DshRuntimeLocation effective = location;
        if (effective == DshRuntimeLocation.AUTO) {
            effective = DshUtil.isWindows() ? DshRuntimeLocation.TEMP : DshRuntimeLocation.HOME;
        }
        return effective == DshRuntimeLocation.TEMP ? tempRoot() : homeRoot();
    }

    /** 用户目录下的运行时根（更持久的选择）。 */
    @NotNull
    public static Path homeRoot() {
        return Paths.get(System.getProperty("user.home", "."), ".dshstudio", "runtime");
    }

    /** 系统临时目录下的运行时根；不可用时回退到用户目录。 */
    @NotNull
    private static Path tempRoot() {
        String tmp = System.getenv("TEMP");
        if (tmp == null || tmp.trim().isEmpty()) {
            tmp = System.getenv("TMP");
        }
        if (tmp == null || tmp.trim().isEmpty()) {
            tmp = System.getProperty("java.io.tmpdir");
        }
        if (tmp == null || tmp.trim().isEmpty()) {
            return homeRoot();
        }
        Path dir = Paths.get(tmp.trim()).resolve("dshstudio-runtime");
        if (Files.isDirectory(dir)) {
            return dir;
        }
        try {
            Files.createDirectories(dir);
            if (Files.isWritable(dir)) {
                return dir;
            }
        } catch (IOException e) {
            LOG.info("[dsh-runtime] 临时目录不可用（" + e.getMessage() + "），回退到用户目录");
        }
        return homeRoot();
    }

    /** 内置基线的解包目录（无论是否已解包）。 */
    @NotNull
    public Path baselineDir(@NotNull Meta meta) {
        return runtimeRoot().resolve(BASELINE_PREFIX + meta.stamp);
    }

    /** 当前生效的内置基线目录；未解包时返回 {@code null}。 */
    @Nullable
    public Path unpackedBaselineDir() {
        Meta meta = bundledMeta();
        if (meta == null || meta.stamp.isEmpty()) {
            return null;
        }
        Path dir = baselineDir(meta);
        return isUnpacked(dir, meta) ? dir : null;
    }

    /** 内置基线是否已解包完成。 */
    public boolean isBaselineUnpacked() {
        return unpackedBaselineDir() != null;
    }

    /** 内置运行时是否覆盖当前平台（决定能不能真的用它）。 */
    public boolean isHostPlatformBundled() {
        Meta meta = bundledMeta();
        return meta != null && meta.targets.contains(DshUtil.hostTarget());
    }

    private static boolean isUnpacked(@NotNull Path dir, @NotNull Meta meta) {
        Path marker = dir.resolve(UNPACK_MARKER);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            return Files.readString(marker, StandardCharsets.UTF_8).trim().equals(meta.stamp);
        } catch (IOException e) {
            return false;
        }
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    /** 运行时来源。 */
    public enum Source {
        /** 用户目录里的热更新版本。 */
        HOT_UPDATE("热更新版本"),
        /** 插件包内置的基线版本。 */
        BUNDLED("内置运行时"),
        /** 系统 PATH 上的 dsh（npx）。 */
        SYSTEM("系统 dsh");

        public final String label;

        Source(String label) {
            this.label = label;
        }
    }

    /** 一次启动方式的解析结果。 */
    public static final class Launch {
        public final Source source;
        /** 运行时版本；系统 dsh 无法在启动前得知，为 {@code null}。 */
        @Nullable
        public final String version;
        /** 运行时目录；系统 dsh 为 {@code null}。 */
        @Nullable
        public final Path dir;
        /** 启动命令前缀，例如 {@code [node, .../lib/bin.js]} 或 {@code [npx, --yes, @deepseek-ai/dsh]}。 */
        public final List<String> prefix;
        /** 解析时发生的「降级 / 告警」说明（例如内置运行时被加密后回退到系统 dsh）；正常为 {@code null}。 */
        @Nullable
        public final String warning;

        Launch(Source source, @Nullable String version, @Nullable Path dir, List<String> prefix,
               @Nullable String warning) {
            this.source = source;
            this.version = version;
            this.dir = dir;
            this.prefix = prefix;
            this.warning = warning;
        }
    }

    /**
     * 纯解析：决定这次启动用哪一份 dsh。
     * <p>
     * 需要内置运行时但尚未解包时，返回 {@link Source#SYSTEM} 之外的兜底结果会不准 ——
     * 所以调用方应先调 {@link #prepare(Project, DshRuntimeMode)} 保证运行时可用。
     * <p>
     * 选定内置 / 热更新运行时后，本方法会用 node 自身验证这份运行时能否被读取
     * （见 {@link #nodeCanReadRuntime}）——这是发现本机透明加密（DLP）把解包出的文件加密的
     * 唯一可靠手段（java 永远读到明文，靠 java 侧自检查不出）。验证失败时在「自动」模式下
     * 静默回退系统 dsh 并记下说明，在「仅内置」模式下直接抛出可操作的异常。
     */
    @NotNull
    public Launch resolve(@NotNull DshRuntimeMode mode) {
        if (mode == DshRuntimeMode.SYSTEM) {
            return systemLaunch(null);
        }

        Meta meta = bundledMeta();
        boolean usable = meta != null && isHostPlatformBundled();

        // 热更新版本只在「自动」模式下参与竞争：「仅内置」是它的回滚出口。
        if (mode == DshRuntimeMode.AUTO) {
            Path hot = hotUpdateDir();
            if (hot != null) {
                String version = readVersion(hot);
                if (version != null) {
                    if (nodeCanReadRuntime(hot)) {
                        return new Launch(Source.HOT_UPDATE, version, hot, nodePrefix(hot), null);
                    }
                    LOG.warn("[dsh-runtime] 热更新运行时无法被 node 读取（疑似本机透明加密），回退系统 dsh");
                    lastResolveNote = "热更新运行时无法被 node 读取（疑似本机透明加密软件加密），已改用系统 dsh（npx）启动。"
                            + "修复办法：在设置页把「运行时位置」改到未被加密的目录，或清理运行时后重试。";
                    return systemLaunch(lastResolveNote);
                }
            }
        }

        if (usable) {
            Path dir = baselineDir(meta);
            if (isUnpacked(dir, meta)) {
                if (nodeCanReadRuntime(dir)) {
                    return new Launch(Source.BUNDLED, meta.dshVersion, dir, nodePrefix(dir), null);
                }
                // 解包标记在、但 node 读不到：典型就是 DLP 把解包出的文件加密了。
                if (mode == DshRuntimeMode.BUNDLED) {
                    throw new IllegalStateException(
                            "内置运行时已解包，但其中的文件被本机透明加密软件（DLP）加密，node 无法读取，"
                                    + "运行时起不来。请在设置页把「运行时位置」改到未被加密的目录，"
                                    + "或点「清理运行时」后重试，或把运行时来源改为「自动 / 仅系统 dsh」。");
                }
                LOG.warn("[dsh-runtime] 内置运行时无法被 node 读取（疑似 DLP 加密），回退系统 dsh");
                lastResolveNote = "内置运行时已解包但无法被 node 读取（疑似本机透明加密软件加密），"
                        + "已改用系统 dsh（npx）启动。修复办法：在设置页把「运行时位置」改到未被加密的目录，"
                        + "或点「清理运行时」后重试。";
                return systemLaunch(lastResolveNote);
            }
        }

        if (mode == DshRuntimeMode.BUNDLED) {
            throw new IllegalStateException(bundledUnavailableReason(meta));
        }
        return systemLaunch(null);
    }

    /** 「仅内置」模式下不可用时的原因说明（要能指导用户下一步怎么做）。 */
    @NotNull
    public String bundledUnavailableReason(@Nullable Meta meta) {
        String reason;
        if (meta == null) {
            reason = "本插件的安装包中没有内置运行时（构建时跳过了 bundleDshRuntime）";
        } else if (!meta.targets.contains(DshUtil.hostTarget())) {
            reason = "内置运行时不含当前平台 " + DshUtil.hostTarget() + " 的原生模块"
                    + "（仅含 " + String.join("、", meta.targets) + "）";
        } else if (!isUnpacked(baselineDir(meta), meta)) {
            reason = "内置运行时尚未解包完成";
        } else {
            reason = "内置运行时不可用";
        }
        return reason + "。请在 设置 → Tools → DeepSeek Harness → 运行时 中改为「自动」或「仅系统 dsh」。";
    }

    @NotNull
    private static Launch systemLaunch(@Nullable String warning) {
        return new Launch(Source.SYSTEM, null, null,
                List.of("npx", "--yes", "@deepseek-ai/dsh"), warning);
    }

    @NotNull
    private static List<String> nodePrefix(@NotNull Path runtimeDir) {
        return List.of(DshUtil.resolveNodeExecutable(),
                runtimeDir.resolve(DSH_ENTRY).toAbsolutePath().toString());
    }

    /**
     * 用户目录里最新的热更新运行时；没有则返回 {@code null}。
     * <p>
     * 目录名即 dsh 版本号，按语义化版本取最大者。目录里没有 dsh 入口（下载中断的残骸）
     * 会被忽略，所以「下载中」的目录天然不会被选中。
     */
    @Nullable
    public Path hotUpdateDir() {
        Path root = runtimeRoot();
        if (!Files.isDirectory(root)) {
            return null;
        }
        Path best = null;
        String bestVersion = null;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path dir : stream.toList()) {
                String name = dir.getFileName().toString();
                if (name.startsWith(BASELINE_PREFIX) || name.startsWith(".")) {
                    continue;
                }
                if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve(DSH_ENTRY))) {
                    continue;
                }
                String version = readVersion(dir);
                if (version == null) {
                    continue;
                }
                if (best == null || DshUtil.compareVersion(bestVersion, version) < 0) {
                    best = dir;
                    bestVersion = version;
                }
            }
        } catch (IOException e) {
            LOG.warn("[dsh-runtime] 扫描热更新目录失败: " + root, e);
        }
        return best;
    }

    /** 读取某个运行时目录里的 dsh 版本号（来自它的 package.json）。 */
    @Nullable
    public static String readVersion(@NotNull Path runtimeDir) {
        Path pkg = runtimeDir.resolve(DSH_PACKAGE);
        if (!Files.isRegularFile(pkg)) {
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(pkg, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String v = str(o, "version", "");
            return v.isEmpty() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    // ── 解包 ──────────────────────────────────────────────────────────────

    /**
     * 保证运行时可用：需要内置基线且尚未解包时，弹一次带进度、可取消的解包过程。
     * <p>
     * 已解包 / 系统模式 / 平台不受支持（自动模式下）会立刻返回，不产生任何开销。
     *
     * @throws IOException 解包失败或被用户取消
     */
    public void prepare(@Nullable Project project, @NotNull DshRuntimeMode mode) throws IOException {
        if (mode == DshRuntimeMode.SYSTEM) {
            return;
        }
        Meta meta = bundledMeta();
        if (meta == null || !meta.targets.contains(DshUtil.hostTarget())) {
            if (mode == DshRuntimeMode.BUNDLED) {
                throw new IOException(bundledUnavailableReason(meta));
            }
            return; // 自动模式：回退到系统 dsh
        }
        if (isUnpacked(baselineDir(meta), meta)) {
            return;
        }
        // 自动模式下已有热更新版本可用时，没必要为了基线去解包 80 MB
        if (mode == DshRuntimeMode.AUTO && hotUpdateDir() != null) {
            return;
        }
        unpackWithProgress(project, meta);
    }

    /** 弹模态进度解包（在 EDT 上）；已在后台线程时直接内联执行，避免嵌套模态框。 */
    private void unpackWithProgress(@Nullable Project project, @NotNull Meta meta) throws IOException {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            AtomicReference<IOException> failure = new AtomicReference<>();
            boolean ok = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    () -> {
                        try {
                            ensureBaseline(ProgressManager.getInstance().getProgressIndicator());
                        } catch (IOException e) {
                            failure.set(e);
                        }
                    },
                    TITLE, true, project);
            if (failure.get() != null) {
                throw failure.get();
            }
            if (!ok) {
                throw new IOException("已取消解包内置 dsh 运行时");
            }
            return;
        }
        ensureBaseline(ProgressManager.getInstance().getProgressIndicator());
    }

    private static final String TITLE = "正在准备内置 dsh 运行时（仅首次）";

    /**
     * 解包内置基线到 {@code ~/.dshstudio/runtime/baseline-<stamp>}；已存在则立即返回。
     * <p>
     * 先解到 {@code .tmp} 再整体改名，所以中途被杀掉也不会留下「看起来完整」的树。
     *
     * @return 解包后的运行时目录
     */
    @NotNull
    public Path ensureBaseline(@Nullable ProgressIndicator indicator) throws IOException {
        Meta meta = bundledMeta();
        if (meta == null || meta.stamp.isEmpty()) {
            throw new IOException("插件包中没有内置 dsh 运行时");
        }
        Path target = baselineDir(meta);
        if (isUnpacked(target, meta)) {
            return target;
        }

        Path root = runtimeRoot();
        Files.createDirectories(root);

        Path tmp = root.resolve(BASELINE_PREFIX + meta.stamp + ".tmp");
        deleteRecursively(tmp);
        deleteRecursively(target); // 上次解包中途失败的半成品

        long started = System.currentTimeMillis();
        try {
            Files.createDirectories(tmp);
            try (InputStream in = DshRuntimeManager.class.getResourceAsStream(ZIP_RESOURCE)) {
                if (in == null) {
                    throw new IOException("插件包中缺少 " + ZIP_RESOURCE);
                }
                extractZip(in, tmp, meta.entries, indicator);
            }
            verifyExtractedTree(tmp);
            Files.writeString(tmp.resolve(UNPACK_MARKER), meta.stamp, StandardCharsets.UTF_8);
            applyExecutableBits(tmp);
            moveInto(tmp, target);
        } catch (ProcessCanceledException canceled) {
            // 用户取消：清掉半成品并把取消原样抛出去，让进度框干净地关掉
            deleteRecursivelyQuietly(tmp);
            throw canceled;
        } catch (Throwable t) {
            deleteRecursivelyQuietly(tmp);
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("解包内置 dsh 运行时失败: " + t.getMessage(), t);
        }

        LOG.info("[dsh-runtime] 已解包内置运行时 " + meta.dshVersion + " 到 " + target
                + "（" + (System.currentTimeMillis() - started) + " ms）");
        cleanupOldBaselines(root, meta.stamp);
        return target;
    }

    /**
     * 解包后的抽样自检：入口脚本必须能当 UTF-8 读出来、且不是安全软件加密后的密文。
     * <p>
     * 为什么需要它：企业里的透明加密客户端（DLP，本机实测 E-SafeNet）会按扩展名把
     * {@code .js} / {@code .ts} / {@code .json} 就地加密。实测把运行时解包到临时目录后，
     * 18390 个文件里有 64% 变成密文，node 读到的是乱码，启动时只会抛一堆看不懂的解析
     * 错误。与其把那些错误甩给用户，不如在这里失败并说清原因和出路。
     * <p>
     * 只查一个文件（入口），成本可以忽略；查不出「部分文件被加密」的极端情况，
     * 但那种情况下 node 本来也会立刻报错。
     */
    static void verifyExtractedTree(@NotNull Path root) throws IOException {
        Path entry = root.resolve(DSH_ENTRY);
        if (!Files.isRegularFile(entry)) {
            throw new IOException("内置 dsh 运行时解包不完整：缺少入口脚本 " + DSH_ENTRY
                    + "。请在「设置 → 工具 → DeepSeek Harness → 运行时」里点「清理运行时」后重试。");
        }

        byte[] head = new byte[64];
        int read;
        try (InputStream in = Files.newInputStream(entry)) {
            read = in.read(head);
        }
        if (read > 0 && new String(head, 0, read, StandardCharsets.ISO_8859_1)
                .contains(CIPHERTEXT_MARKER)) {
            throw new IOException("内置 dsh 运行时解包后的文件被本机的透明加密软件（DLP）"
                    + "加密了，node 无法读取，运行时起不来。这不是插件的问题：请让 IT 把运行时目录"
                    + "（设置页「运行时目录」一栏显示的路径）加入 DLP 排除名单，"
                    + "或在设置页把「运行时位置」改到未被加密的目录后重试。");
        }

        String text;
        try {
            text = Files.readString(entry, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new IOException("内置 dsh 运行时解包后的入口脚本不是合法的 UTF-8，"
                    + "文件可能在写入过程中被安全软件改写或截断。"
                    + "请在设置页点「清理运行时」后重试。", e);
        }
        if (!text.startsWith(ENTRY_SHEBANG)) {
            throw new IOException("内置 dsh 运行时解包后的入口脚本内容异常（不以 " + ENTRY_SHEBANG
                    + " 开头），解包结果不可信。请在设置页点「清理运行时」后重试。");
        }
    }

    /**
     * 用 node 自身验证一份解包好的运行时是否真的能被 node 读取。
     * <p>
     * 这是 DLP 自检的<b>唯一可靠手段</b>：企业透明加密（E-SafeNet）按<b>进程白名单</b>工作，
     * java 读到的永远是明文，所以 {@link #verifyExtractedTree} 这类 java 侧检查在本机永远查不出加密；
     * 而真正消费这些文件的是 node。这里直接让 node 去读 package.json、入口脚本并抽样扫描，
     * 一旦发现 {@code E-SafeNet} 头即说明 node 拿到密文，运行时起不来。
     * <p>
     * 检测脚本经 {@code node -e} 传入，不落盘，因此不会触发本机加密。node 缺失或命令异常时
     * 保守返回 {@code true}（不误杀），真正的「缺 node」由 {@link DshNodeChecker} 另行处理。
     *
     * @param runtimeDir 已解包的运行时根目录
     * @return {@code true} 表示 node 能正常读取
     */
    public static boolean nodeCanReadRuntime(@NotNull Path runtimeDir) {
        Boolean cached = NODE_READABLE_CACHE.get(runtimeDir);
        if (cached != null) {
            return cached;
        }
        String nodeExe = nodeExecutableOverride != null ? nodeExecutableOverride
                : DshUtil.resolveNodeExecutable();
        if (nodeExe == null || nodeExe.trim().isEmpty()) {
            NODE_READABLE_CACHE.put(runtimeDir, Boolean.TRUE);
            return true;
        }
        Path pkg = runtimeDir.resolve(DSH_PACKAGE);
        Path entry = runtimeDir.resolve(DSH_ENTRY);
        boolean result;
        try {
            ProcessBuilder pb = new ProcessBuilder(nodeExe, "-e", NODE_RUNTIME_CHECK_SCRIPT,
                    pkg.toString(), entry.toString(), runtimeDir.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(20, TimeUnit.SECONDS);
            int code = finished ? p.exitValue() : -1;
            if (!finished) {
                p.destroyForcibly();
                result = true; // 超时不要误杀
            } else {
                // 退出码 3 = 明确检测到密文；其余非零（含 node 缺失）按「查不了」处理，保守返回 true
                result = code != 3;
            }
        } catch (Exception e) {
            LOG.debug("[dsh-runtime] node 可读性自检异常（已忽略，按可读处理）: " + e.getMessage());
            result = true;
        }
        NODE_READABLE_CACHE.put(runtimeDir, result);
        return result;
    }

    /**
     * 把运行时 zip 解到指定目录。
     * <p>
     * 单独抽成静态方法是为了可测：它是整条链路里最容易出错、又最难在用户机器上复现的一步
     * （zip slip、目录层级、1.8 万个文件的完整性），单元测试直接拿真 zip 跑一遍。
     *
     * @param expectedEntries zip 内的文件数，用于进度百分比；&lt;=0 表示未知
     * @param indicator       进度回调，可为 null（后台静默解包）
     */
    static void extractZip(@NotNull InputStream in, @NotNull Path root,
                           int expectedEntries, @Nullable ProgressIndicator indicator) throws IOException {
        int total = expectedEntries > 0 ? expectedEntries : 1;
        int done = 0;
        byte[] buf = new byte[COPY_BUFFER];
        if (indicator != null) {
            indicator.setIndeterminate(false);
            indicator.setText(TITLE);
            indicator.setFraction(0);
        }
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in, COPY_BUFFER))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (indicator != null) {
                    indicator.checkCanceled();
                }
                Path out = root.resolve(entry.getName()).normalize();
                // zip slip：条目名里的 ../ 不能逃出目标目录
                if (!out.startsWith(root)) {
                    zis.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Path parent = out.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (OutputStream os = Files.newOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            os.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
                done++;
                if (indicator != null && (done & 0x3F) == 0) {
                    indicator.setFraction(Math.min(0.995, done / (double) total));
                    indicator.setText2(done + " / " + total + " 个文件");
                }
            }
        }
        if (indicator != null) {
            indicator.setFraction(1.0);
            indicator.setText2("");
        }
    }

    /**
     * 按构建期写下的清单补上可执行位。
     * <p>
     * zip 不保存 Unix 权限位，{@code node-pty} 的 {@code spawn-helper} 与
     * {@code @vscode/ripgrep} 的 {@code rg} 解出来会变成 0644，终端与搜索会直接不可用。
     * Windows 不看权限位，跳过。
     */
    static void applyExecutableBits(@NotNull Path root) {
        if (DshUtil.isWindows()) {
            return;
        }
        Path manifest = root.resolve(EXEC_MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            return;
        }
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
        try {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                String rel = line.trim();
                if (rel.isEmpty()) {
                    continue;
                }
                Path file = root.resolve(rel);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    Files.setPosixFilePermissions(file, perms);
                } catch (Exception e) {
                    // 非 POSIX 文件系统（或权限不足）时退回到最弱的可执行标记
                    if (!file.toFile().setExecutable(true, false)) {
                        LOG.warn("[dsh-runtime] 无法设置可执行位: " + file);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("[dsh-runtime] 读取可执行位清单失败", e);
        }
    }

    static void moveInto(@NotNull Path from, @NotNull Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        } catch (IOException e) {
            // 目标已存在（并发解包）时视为成功
            if (Files.isDirectory(to)) {
                deleteRecursivelyQuietly(from);
                return;
            }
            Files.move(from, to);
        }
    }

    /** 删掉其它 stamp 的基线目录，避免插件多次升级后磁盘上堆积多份运行时。 */
    private static void cleanupOldBaselines(@NotNull Path root, @NotNull String keepStamp) {
        String keep = BASELINE_PREFIX + keepStamp;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path dir : stream.toList()) {
                String name = dir.getFileName().toString();
                if (!name.startsWith(BASELINE_PREFIX) || name.equals(keep)) {
                    continue;
                }
                deleteRecursivelyQuietly(dir);
            }
        } catch (IOException ignored) {
            // 清理失败不影响使用
        }
    }

    /** 删除整个运行时目录（设置页「清理运行时」用）。 */
    public void clear(@NotNull Path root) throws IOException {
        deleteRecursively(root);
    }

    // ── 文件工具 ──────────────────────────────────────────────────────────

    /**
     * 递归删除目录。
     * <p>
     * 用 {@code Files.walk} 而不是 {@code File.walkTopDown()}：后者会跟进符号链接，
     * 遇到 node_modules 里的环形链接会原地空转（构建期踩过这个坑）。
     */
    static void deleteRecursively(@NotNull Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    static void deleteRecursivelyQuietly(@NotNull Path root) {
        try {
            deleteRecursively(root);
        } catch (IOException e) {
            LOG.info("[dsh-runtime] 清理目录失败（不影响使用）: " + root + " — " + e.getMessage());
        }
    }

    /** 人类可读的磁盘占用，用于设置页展示。 */
    @NotNull
    public static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        String[] units = {"B", "KB", "MB", "GB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, i == 0 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }
}
