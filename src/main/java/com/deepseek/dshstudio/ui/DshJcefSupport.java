package com.deepseek.dshstudio.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

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

    /** 页面 JS 回传数据用的 console 消息前缀。 */
    private static final String SYNC_PREFIX = "DSHSTUDIO_SYNC:";

    /**
     * 安装两条页面桥接（都靠反射 + JDK 动态代理，失败静默降级，不影响主功能）：
     *
     * <ol>
     *   <li><b>回传桥</b>（{@code CefDisplayHandler}）：拦截页面里以 {@code DSHSTUDIO_SYNC:}
     *       开头的 {@code console.log}，把 dsh 网页选的背景图 / 透明度回传给插件持久化。</li>
     *   <li><b>加载完成桥</b>（{@code CefLoadHandler}）：页面每次加载完成（包含用户点刷新后的 reload）
     *       都回调 {@code onLoadEnd}，由插件重新注入浮层脚本。这是「刷新后背景消失」的根治点 ——
     *       {@code reload()} 是异步的，调用后立刻注入只会打在正在卸载的旧页面上。</li>
     * </ol>
     *
     * <p>挂载优先用 {@code JBCefClient.addXxxHandler(handler, cefBrowser)}（平台提供的多路复用
     * 版本，可与 JBCefBrowser 自带的 handler 共存）；该 API 不可用时降级到
     * {@code CefClient.addXxxHandler(handler)} 单参写法。</p>
     */
    public static void installBridges(@NotNull Object browser,
                                     @NotNull Consumer<String> onSync,
                                     @NotNull Runnable onLoadEnd) {
        Object cefBrowser;
        try {
            cefBrowser = browser.getClass().getMethod("getCefBrowser").invoke(browser);
        } catch (Throwable t) {
            return;
        }
        if (cefBrowser == null) {
            return;
        }

        // ① console 回传桥
        try {
            Class<?> iface = Class.forName("org.cef.handler.CefDisplayHandler");
            InvocationHandler h = (proxy, method, args) -> {
                if ("onConsoleMessage".equals(method.getName()) && args != null && args.length >= 3
                        && args[2] instanceof String) {
                    String msg = (String) args[2];
                    if (msg.startsWith(SYNC_PREFIX)) {
                        onSync.accept(msg.substring(SYNC_PREFIX.length()));
                        // 返回 true 表示该消息已被消费，不再打进 IDE 日志
                        return method.getReturnType() == boolean.class ? Boolean.TRUE : defaultValue(method);
                    }
                }
                return defaultValue(method);
            };
            addHandler(browser, cefBrowser, "addDisplayHandler", iface,
                    Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface}, h));
        } catch (Throwable ignored) {
            // 该 IDE 的 JCEF 版本不支持：失去回传能力，但浮层仍可由插件权威值注入
        }

        // ② 页面加载完成桥（刷新后自动重新注入）
        try {
            Class<?> iface = Class.forName("org.cef.handler.CefLoadHandler");
            InvocationHandler h = (proxy, method, args) -> {
                String name = method.getName();
                if ("onLoadEnd".equals(name)) {
                    onLoadEnd.run();
                } else if ("onLoadingStateChange".equals(name) && args != null && args.length >= 2
                        && Boolean.FALSE.equals(args[1])) {
                    onLoadEnd.run(); // isLoading == false
                }
                return defaultValue(method);
            };
            addHandler(browser, cefBrowser, "addLoadHandler", iface,
                    Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface}, h));
        } catch (Throwable ignored) {
            // 降级：由调用方的延迟重注入兜底
        }
    }

    /** 优先用 JBCefClient 的多路复用 API 挂 handler，不可用时退回 CefClient 单参写法。 */
    private static void addHandler(@NotNull Object browser, @NotNull Object cefBrowser,
                                   @NotNull String methodName, @NotNull Class<?> iface,
                                   @NotNull Object proxy) throws Exception {
        try {
            Object jbClient = browser.getClass().getMethod("getJBCefClient").invoke(browser);
            if (jbClient != null) {
                Class<?> browserIface = Class.forName("org.cef.browser.CefBrowser");
                jbClient.getClass().getMethod(methodName, iface, browserIface)
                        .invoke(jbClient, proxy, cefBrowser);
                return;
            }
        } catch (Throwable ignored) {
            // 落到下面的 CefClient 单参写法
        }
        Object cefClient = cefBrowser.getClass().getMethod("getClient").invoke(cefBrowser);
        if (cefClient != null) {
            cefClient.getClass().getMethod(methodName, iface).invoke(cefClient, proxy);
        }
    }

    /** 动态代理里未处理的方法返回类型安全的默认值。 */
    @Nullable
    private static Object defaultValue(@NotNull Method method) {
        Class<?> ret = method.getReturnType();
        if (ret == boolean.class) return Boolean.FALSE;
        if (ret == int.class) return 0;
        if (ret == long.class) return 0L;
        if (ret == double.class) return 0d;
        if (ret == float.class) return 0f;
        return null;
    }
}
