package com.deepseek.dshstudio.runtime;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.util.DshUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Node.js 可用性探测与引导。
 * <p>
 * dsh 运行时与系统 dsh 都是 Node 程序，本机没有 Node 就无从启动；版本过低则可能在运行期
 * 才抛出难以定位的错误。这里做两件事：探测（{@link #check()}）和一次性的引导对话框
 * （{@link #guideIfNeeded(Project)}）。
 * <p>
 * <b>刻意只警告、不拦截</b>：{@link DshStudioConstants#MIN_NODE_VERSION} 来自依赖包的
 * {@code engines} 声明，那是包作者的保守估计，低于它未必一定跑不起来。强行拦住反而会挡掉
 * 本来能正常使用的用户，所以对话框始终给出「继续尝试」这一项。
 */
public final class DshNodeChecker {

    /** 对话框按钮下标：「打开下载页」。 */
    private static final int OPTION_DOWNLOAD = 0;

    /** 对话框按钮下标：「继续尝试」。 */
    private static final int OPTION_CONTINUE = 1;

    /**
     * 本次会话里用户已经对哪种状态选过「继续尝试」（形如 {@code TOO_OLD:20.11.1}）。
     * <p>
     * 用户带着旧版 Node 反复点启动时，不该每次都弹一次框。只记住「继续尝试」，
     * 记住「取消」没有意义 —— 他可能就是想去装完再回来。
     */
    private static volatile String continueAnywayFor;

    public enum Status {
        /** 已安装且满足最低版本。 */
        OK,
        /** 未检测到 node。 */
        MISSING,
        /** 已安装但低于最低版本。 */
        TOO_OLD
    }

    /** 一次探测的结果。 */
    public static final class Report {
        @NotNull
        public final Status status;

        /** 检测到的版本号（如 {@code "22.19.0"}）；{@link Status#MISSING} 时为 {@code null}。 */
        @Nullable
        public final String version;

        Report(@NotNull Status status, @Nullable String version) {
            this.status = status;
            this.version = version;
        }

        public boolean isOk() {
            return status == Status.OK;
        }

        /** 一句话描述当前状态，供设置页展示与对话框正文使用。 */
        @NotNull
        public String describe() {
            switch (status) {
                case MISSING:
                    return "未检测到 Node.js（dsh 运行时需要 "
                            + DshStudioConstants.MIN_NODE_VERSION + " 或更高）";
                case TOO_OLD:
                    return "Node.js " + version + " 低于要求的 "
                            + DshStudioConstants.MIN_NODE_VERSION;
                default:
                    return "Node.js " + version + "（满足要求）";
            }
        }
    }

    /** 探测本机 Node.js；不弹任何 UI，可在任意线程调用。 */
    @NotNull
    public static Report check() {
        String version = DshUtil.detectNodeVersion();
        if (version == null) {
            return new Report(Status.MISSING, null);
        }
        if (DshUtil.compareVersion(version, DshStudioConstants.MIN_NODE_VERSION) < 0) {
            return new Report(Status.TOO_OLD, version);
        }
        return new Report(Status.OK, version);
    }

    /**
     * 状态不满足时弹一次引导对话框（满足时直接返回 {@code true}，不弹）。
     *
     * @return {@code true} 表示可以继续尝试启动；{@code false} 表示用户选择去装 Node 或直接取消
     */
    public static boolean guideIfNeeded(@Nullable Project project) {
        Report report = check();
        if (report.isOk()) {
            return true;
        }
        return guide(project, report);
    }

    /**
     * 判断一条启动命令是否需要本机安装 Node.js。
     * <p>
     * 已下载运行时走的是 {@code node .../lib/bin.js}，系统方式走 {@code npx}，
     * 全局安装则是 {@code dsh} —— 三者都是 Node 程序，缺 Node 时启动必然失败。
     * 用户在设置里自定义成别的命令（如一段 shell 脚本）时不该误报，所以按可执行文件名判断。
     */
    public static boolean commandNeedsNode(@Nullable List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String name = new File(command.get(0)).getName().toLowerCase(Locale.ROOT);
        return name.equals("npx") || name.equals("npx.cmd")
                || name.equals("npm") || name.equals("npm.cmd")
                || name.equals("dsh") || name.equals("dsh.cmd")
                || name.startsWith("node");
    }

    /** 弹引导对话框（自动切到 EDT）。返回用户是否选择继续尝试。 */
    public static boolean guide(@Nullable Project project, @NotNull Report report) {
        String signature = report.status + ":" + report.version;
        if (signature.equals(continueAnywayFor)) {
            return true; // 本次会话已经问过并且用户选了继续
        }
        boolean proceed = onEdt(() -> showDialog(project, report));
        if (proceed) {
            continueAnywayFor = signature;
        }
        return proceed;
    }

    private static boolean showDialog(@Nullable Project project, @NotNull Report report) {
        String[] options = {"打开下载页", "继续尝试", "取消"};
        int choice = Messages.showDialog(project, buildMessage(report), "需要 Node.js",
                options, OPTION_DOWNLOAD, Messages.getWarningIcon());
        if (choice == OPTION_DOWNLOAD) {
            DshUtil.openInBrowser(DshStudioConstants.NODE_DOWNLOAD_URL);
            return false; // 用户去装 Node 了，本次不再往下走
        }
        return choice == OPTION_CONTINUE;
    }

    private static String buildMessage(@NotNull Report report) {
        StringBuilder sb = new StringBuilder("<html><div style='width:430px'>");
        if (report.status == Status.MISSING) {
            sb.append("dsh 运行时是一个 Node 程序，但本机没有检测到 <code>node</code>。");
        } else {
            sb.append("检测到 <b>Node.js ").append(report.version)
                    .append("</b>，低于 dsh 运行时要求的最低版本 <b>")
                    .append(DshStudioConstants.MIN_NODE_VERSION).append("</b>。");
        }
        sb.append("<br><br>最低版本：<b>").append(DshStudioConstants.MIN_NODE_VERSION)
                .append("</b>（LTS）<br>下载地址：")
                .append(DshStudioConstants.NODE_DOWNLOAD_URL);
        if (report.status == Status.MISSING) {
            sb.append("<br><br>没有 Node 时启动几乎一定会失败，建议装好后再点「继续尝试」。");
        } else {
            sb.append("<br><br>该下限来自依赖包的声明，未必一定跑不起来，因此不强制拦截。");
        }
        return sb.append("</div></html>").toString();
    }

    /** 把一段必须跑在 EDT 上的逻辑包起来；已在 EDT 时直接执行，避免死锁。 */
    private static boolean onEdt(@NotNull BooleanSupplier action) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return action.getAsBoolean();
        }
        AtomicBoolean result = new AtomicBoolean(false);
        ApplicationManager.getApplication().invokeAndWait(() -> result.set(action.getAsBoolean()));
        return result.get();
    }

    private DshNodeChecker() {
    }
}
