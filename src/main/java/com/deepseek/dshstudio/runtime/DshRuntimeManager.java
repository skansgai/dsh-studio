package com.deepseek.dshstudio.runtime;

import com.deepseek.dshstudio.settings.DshSettingsState;
import com.deepseek.dshstudio.util.DshUtil;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * dsh 运行时的解析与安装。
 *
 * <h2>为什么不再内置运行时</h2>
 * 早期版本把一份裁剪过的 dsh（含全部 Node 依赖，约 80MB）打进插件包，换来「装上即用、不用等
 * npx 下载十几分钟」。但这带来几个持续代价：插件包体积从 115KB 涨到 80+MB（每次小版本发布都要
 * 全量重下）、5 个平台里 4 份对用户是浪费、还把第三方原生二进制运行时带进了插件包的供应链 /
 * 安全审查面。对于高频发小版本的节奏，前两者尤其不可接受。
 *
 * <h2>现在的做法</h2>
 * 插件包保持 ~115KB。首次启动时复用已经写好的<b>热更新</b>链路：检测到本地没有 dsh
 * → 从本仓库的 GitHub Release 拉取<b>当前平台</b>的包（约 41MB）→ sha256 校验
 * → 原子解包安装 → 启动。原始问题（284MB / 十几分钟的 npx 拉取）照样解决，首次体验只是
 * 「等一次 41MB 下载」，比内置方案只差一个网络请求；而插件包体积、分发与每次小版本发布的下载
 * 成本都不受影响。访问不了 GitHub Release 的极少数环境，可手动下载 Release 页上的「全平台离线包」
 * 导入，不必让所有人都背 80MB。
 *
 * <h2>目录布局</h2>
 * <pre>
 * ~/.dshstudio/runtime/        （或 Windows 上的系统临时目录）
 *   └─ &lt;dshVersion&gt;/         运行时（下载后解包，自动模式优先使用）
 * </pre>
 *
 * <h2>解析优先级</h2>
 * 已下载（热更新）版本 → 系统 dsh（npx）。见 {@link DshRuntimeMode}。
 */
public final class DshRuntimeManager {

    private static final Logger LOG = Logger.getInstance(DshRuntimeManager.class);

    /** 解包完成标记，内容为 zip 的 sha256 前 8 位（stamp）；存在即代表这棵树是完整的。 */
    static final String UNPACK_MARKER = ".unpacked-ok";

    /** 需要可执行位的文件清单（zip 不保留 Unix 权限位）。 */
    static final String EXEC_MANIFEST = ".dsh-runtime-executables";

    /** dsh 入口脚本（相对运行时根目录）。 */
    static final String DSH_ENTRY = "node_modules/@deepseek-ai/dsh/lib/bin.js";

    /** dsh 的 package.json（相对运行时根目录），用于读取版本号。 */
    static final String DSH_PACKAGE = "node_modules/@deepseek-ai/dsh/package.json";

    /** 解包时的读缓冲；1.8 万个小文件，缓冲大一点能明显少几次系统调用。 */
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

    /** 最近一次 {@link #resolve} 留下的降级说明（如「已下载的运行时被 DLP 加密，已回退系统 dsh」）；消费后清空。 */
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

    @NotNull
    public static DshRuntimeManager getInstance() {
        return ApplicationManager.getApplication().getService(DshRuntimeManager.class);
    }

    // ── 目录 ──────────────────────────────────────────────────────────────

    /**
     * 运行时根目录。
     * <p>
     * 默认（{@link DshRuntimeLocation#AUTO}）在 Windows 上落在系统临时目录，其余平台落在
     * 用户主目录。这不是随手定的 —— 实测企业安全软件（DLP）按路径范围做透明加密，
     * 用户目录 / 项目目录下写一个小文件要 ~85 ms（11 个/秒），而临时目录被排除在外
     * （2500 个/秒）。dsh 运行时解包后约 1.8 万个文件，两者的首次落地耗时相差 **27 分钟 vs 6 秒**。
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

    /**
     * 解析 dsh 的数据目录（DSH_HOME）：dsh 把 profiles 与 node_modules.lock 放在这里。
     * <p>
     * 默认落到运行时根的 {@code .dsh} 子目录，而不是用户主目录的 {@code ~/.dsh} ——
     * 后者在本机被企业 DLP 透明加密，node 写入会被异步加密、读锁会因加密队列卡死
     * （实测 {@code atomic-write: timed out waiting for the writer lock}）。运行时根在 Windows 上
     * 默认是系统临时目录（DLP 排除范围），因此把 DSH_HOME 也放在这里能彻底绕开这个坑。
     * <p>
     * 用户若在设置里显式填了 DSH_HOME，则优先用他填的值。
     */
    @NotNull
    public String resolveDshHome(@NotNull DshSettingsState settings) {
        if (settings.dshHome != null && !settings.dshHome.trim().isEmpty()) {
            return settings.dshHome.trim();
        }
        return runtimeRoot().resolve(".dsh").toString();
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    /** 运行时来源。 */
    public enum Source {
        /** 用户目录里的已下载（热更新）运行时。 */
        HOT_UPDATE("已下载运行时"),
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
        /** 解析时发生的「降级 / 告警」说明（例如已下载运行时被加密后回退到系统 dsh）；正常为 {@code null}。 */
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
     * 需要首次下载时，调用方应先调 {@link #prepare(Project, DshRuntimeMode)} 把运行时拉下来；
     * 这里只负责「用哪一份」。
     * <p>
     * 选定已下载运行时后，本方法会用 node 自身验证这份运行时能否被读取
     * （见 {@link #nodeCanReadRuntime}）——这是发现本机透明加密（DLP）把解包出的文件加密的
     * 唯一可靠手段（java 永远读到明文，靠 java 侧自检查不出）。验证失败时在「自动」模式下
     * 静默回退系统 dsh 并记下说明，供启动流程提示用户。
     */
    @NotNull
    public Launch resolve(@NotNull DshRuntimeMode mode) {
        if (mode == DshRuntimeMode.SYSTEM) {
            return systemLaunch(null);
        }

        // 自动模式：优先用已下载（热更新）的运行时
        Path hot = hotUpdateDir();
        if (hot != null) {
            String version = readVersion(hot);
            if (version != null) {
                if (nodeCanReadRuntime(hot)) {
                    return new Launch(Source.HOT_UPDATE, version, hot, nodePrefix(hot), null);
                }
                // 解包标记在、但 node 读不到：典型就是 DLP 把解包出的文件加密了。
                LOG.warn("[dsh-runtime] 已下载的运行时无法被 node 读取（疑似本机透明加密），回退系统 dsh");
                lastResolveNote = "已下载的 dsh 运行时无法被 node 读取（疑似本机透明加密软件加密），已改用系统 dsh（npx）启动。"
                        + "修复办法：在设置页把「运行时位置」改到未被加密的目录，或清理运行时后重试。";
                return systemLaunch(lastResolveNote);
            }
        }

        // 没有已下载的运行时、也拉不到（离线 / 发布渠道无本平台包）时，回退系统 dsh（npx）。
        return systemLaunch(null);
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
     * 用户目录里最新的已下载运行时；没有则返回 {@code null}。
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
                if (name.startsWith(".") || name.startsWith("baseline-")) {
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
            LOG.warn("[dsh-runtime] 扫描已下载运行时目录失败: " + root, e);
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

    // ── 首次安装：从发布渠道下载 ──────────────────────────────────────────

    /**
     * 保证运行时可用：首次启动时若本地没有 dsh，从发布渠道拉取当前平台的包并安装。
     * <p>
     * 已下载 / 系统模式会立刻返回，不产生任何开销。下载是带进度、可取消的；用户取消视为失败，
     * 由调用方决定要不要回退系统 dsh。
     *
     * @throws IOException 解包 / 校验失败（网络中断、包被截断等）或用户取消下载
     */
    public void prepare(@Nullable Project project, @NotNull DshRuntimeMode mode) throws IOException {
        if (mode == DshRuntimeMode.SYSTEM) {
            return;
        }
        // 已经有下载好的运行时，无需动作
        if (hotUpdateDir() != null) {
            return;
        }
        // 首次使用：从发布渠道拉取当前平台的运行时
        downloadWithProgress(project);
    }

    /** 弹模态进度下载（在 EDT 上）；已在后台线程时直接内联执行，避免嵌套模态框。 */
    private void downloadWithProgress(@Nullable Project project) throws IOException {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            AtomicReference<IOException> failure = new AtomicReference<>();
            boolean ok = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    () -> {
                        try {
                            DshRuntimeUpdater.UpdateInfo info =
                                    DshRuntimeUpdater.getInstance().checkForUpdate();
                            if (info == null) {
                                LOG.info("[dsh-runtime] 没有可用的运行时发布（可能离线或发布渠道无本平台包），"
                                        + "首次启动将改用系统 dsh");
                                return;
                            }
                            DshRuntimeUpdater.getInstance().downloadAndInstall(
                                    info, ProgressManager.getInstance().getProgressIndicator());
                        } catch (IOException e) {
                            failure.set(e);
                        }
                    },
                    DOWNLOAD_TITLE, true, project);
            if (failure.get() != null) {
                throw failure.get();
            }
            if (!ok) {
                throw new IOException("已取消下载 dsh 运行时");
            }
            return;
        }
        DshRuntimeUpdater.UpdateInfo info = DshRuntimeUpdater.getInstance().checkForUpdate();
        if (info == null) {
            LOG.info("[dsh-runtime] 没有可用的运行时发布（可能离线或发布渠道无本平台包），"
                    + "首次启动将改用系统 dsh");
            return;
        }
        DshRuntimeUpdater.getInstance().downloadAndInstall(
                info, ProgressManager.getInstance().getProgressIndicator());
    }

    private static final String DOWNLOAD_TITLE = "正在下载 dsh 运行时（首次启动，约 41MB）";

    // ── 解包校验 ──────────────────────────────────────────────────────────

    /**
     * 解包后的抽样自检：入口脚本必须能当 UTF-8 读出来、且不是安全软件加密后的密文。
     * <p>
     * 为什么需要它：企业里的透明加密客户端（DLP，本机实测 E-SafeNet）会按扩展名把
     * {@code .js} / {@code .ts} / {@code .json} 就地加密。实测把运行时解包到临时目录后，
     * 1.8 万个文件里可能有相当比例变成密文，node 读到的是乱码，启动时只会抛一堆看不懂的解析
     * 错误。与其把那些错误甩给用户，不如在这里失败并说清原因和出路。
     * <p>
     * 只查一个文件（入口），成本可以忽略；查不出「部分文件被加密」的极端情况，
     * 但那种情况下 node 本来也会立刻报错。
     */
    static void verifyExtractedTree(@NotNull Path root) throws IOException {
        Path entry = root.resolve(DSH_ENTRY);
        if (!Files.isRegularFile(entry)) {
            throw new IOException("dsh 运行时解包不完整：缺少入口脚本 " + DSH_ENTRY
                    + "。请在「设置 → 工具 → DeepSeek Harness → 运行时」里点「清理运行时」后重试。");
        }

        byte[] head = new byte[64];
        int read;
        try (InputStream in = Files.newInputStream(entry)) {
            read = in.read(head);
        }
        if (read > 0 && new String(head, 0, read, StandardCharsets.ISO_8859_1)
                .contains(CIPHERTEXT_MARKER)) {
            throw new IOException("dsh 运行时解包后的文件被本机的透明加密软件（DLP）"
                    + "加密了，node 无法读取，运行时起不来。这不是插件的问题：请让 IT 把运行时目录"
                    + "（设置页「运行时目录」一栏显示的路径）加入 DLP 排除名单，"
                    + "或在设置页把「运行时位置」改到未被加密的目录后重试。");
        }

        String text;
        try {
            text = Files.readString(entry, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new IOException("dsh 运行时解包后的入口脚本不是合法的 UTF-8，"
                    + "文件可能在写入过程中被安全软件改写或截断。"
                    + "请在设置页点「清理运行时」后重试。", e);
        }
        if (!text.startsWith(ENTRY_SHEBANG)) {
            throw new IOException("dsh 运行时解包后的入口脚本内容异常（不以 " + ENTRY_SHEBANG
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
            indicator.setText(DOWNLOAD_TITLE);
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

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? def : e.getAsString();
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
