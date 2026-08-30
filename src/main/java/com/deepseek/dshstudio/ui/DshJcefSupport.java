package com.deepseek.dshstudio.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.lang.reflect.Method;

/**
 * JCEF 浏览器能力的反射封装。
 *
 * <p>IntelliJ 平台在 2023.1–2025.2 不把 JCEF 暴露为独立模块
 * （<code>com.intellij.modules.jcef</code> 不存在），但插件代码里若直接引用
 * <code>com.intellij.ui.jcef.*</code> 类，pluginVerifier 会在这些版本上报告
 * compatibility problem（因为 optional 依赖对应的模块解析不到，字节码引用就变成
 * 找不到的类）。</p>
 *
 * <p>把所有 JCEF 操作收拢到这个类里，并且全部通过 {@link Class#forName(String)}
 * + 反射调用，这样主插件其它类的字节码不再包含 <code>com.intellij.ui.jcef</code>
 * 引用。当 JCEF 不可用时，{@link #isSupported()} 返回 false，工具窗口回退到
 * 说明面板，不会触发任何 JCEF 类加载。</p>
 */
public final class DshJcefSupport {

    private static final String JBCEF_APP_CLASS = "com.intellij.ui.jcef.JBCefApp";
    private static final String JBCEF_BROWSER_CLASS = "com.intellij.ui.jcef.JBCefBrowser";

    private static Boolean cachedSupported;

    private DshJcefSupport() {
        // utility
    }

    /**
     * 检查当前 IDE 是否支持 JCEF（类存在且 isSupported() 为 true）。
     * 结果会被缓存；如果类或方法找不到，直接返回 false。
     */
    public static synchronized boolean isSupported() {
        if (cachedSupported != null) {
            return cachedSupported;
        }
        try {
            Class<?> appClass = Class.forName(JBCEF_APP_CLASS);
            Method m = appClass.getMethod("isSupported");
            Boolean result = (Boolean) m.invoke(null);
            cachedSupported = result != null && result;
        } catch (Throwable t) {
            cachedSupported = Boolean.FALSE;
        }
        return cachedSupported;
    }

    /**
     * 创建 JCEF 浏览器实例。若 JCEF 类不可用则返回 null。
     */
    @Nullable
    public static Object createBrowser() {
        try {
            Class<?> browserClass = Class.forName(JBCEF_BROWSER_CLASS);
            return browserClass.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 获取浏览器实例的 Swing 组件。
     */
    @Nullable
    public static JComponent getComponent(@NotNull Object browser) {
        try {
            Method m = browser.getClass().getMethod("getComponent");
            return (JComponent) m.invoke(browser);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 加载指定 URL。
     */
    public static void loadURL(@NotNull Object browser, @NotNull String url) {
        try {
            Method m = browser.getClass().getMethod("loadURL", String.class);
            m.invoke(browser, url);
        } catch (Throwable ignored) {
            // 浏览器可能已释放或 IDE 版本不支持
        }
    }

    /**
     * 在页面内执行一段 JavaScript。
     */
    public static void executeJavaScript(@NotNull Object browser, @NotNull String script) {
        try {
            Object cefBrowser = browser.getClass().getMethod("getCefBrowser").invoke(browser);
            if (cefBrowser == null) {
                return;
            }
            Method m = cefBrowser.getClass().getMethod("executeJavaScript",
                    String.class, String.class, int.class);
            m.invoke(cefBrowser, script, "", 0);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 刷新当前页面。
     */
    public static void reload(@NotNull Object browser) {
        try {
            Object cefBrowser = browser.getClass().getMethod("getCefBrowser").invoke(browser);
            if (cefBrowser == null) {
                return;
            }
            Method m = cefBrowser.getClass().getMethod("reload");
            m.invoke(cefBrowser);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 释放浏览器资源。
     */
    public static void dispose(@Nullable Object browser) {
        if (browser instanceof Disposable) {
            Disposer.dispose((Disposable) browser);
        }
    }
}
