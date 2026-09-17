package com.deepseek.dshstudio.util;

import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 端口工具单元测试（不依赖 IDE 运行环境）。
 * <p>
 * 多项目并行的关键前提就是「插件能自己挑到一个真正空闲的端口」——dsh 的 webServer.listen
 * 失败会让整个初始化失败，不会自己换端口。这里用真实的 socket 绑定来验证判定逻辑，
 * 而不是复用被测实现算期望值。
 */
public class DshPortTest {

    @Test
    public void isPortAvailableFalseForOccupiedPort() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(false);
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = occupied.getLocalPort();

            assertFalse("正在被监听的端口必须判为不可用", DshUtil.isPortAvailable(port));
        }
    }

    @Test
    public void isPortAvailableTrueAfterRelease() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            port = socket.getLocalPort();
        }
        // 监听 socket 关闭后端口立即释放（没有 ESTABLISHED 连接就不会进 TIME_WAIT）
        assertTrue("释放后的端口应当可用", DshUtil.isPortAvailable(port));
    }

    @Test
    public void isPortAvailableRejectsOutOfRange() {
        assertFalse(DshUtil.isPortAvailable(0));
        assertFalse(DshUtil.isPortAvailable(-1));
        assertFalse(DshUtil.isPortAvailable(70000));
    }

    @Test
    public void findFreePortReturnsBindablePort() {
        int port = DshUtil.findFreePort();
        assertTrue("应当能取到一个有效端口，实际 " + port, port > 0 && port <= 65535);

        // 独立验证：拿到的端口必须真的绑得上（不复用被测实现算期望值）
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (Exception e) {
            fail("findFreePort 返回的端口应当可绑定：" + port + " -> " + e);
        }
    }

    @Test
    public void findFreePortAvoidsPortHeldByAnotherInstance() throws Exception {
        try (ServerSocket held = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int heldPort = held.getLocalPort();
            int other = DshUtil.findFreePort();
            assertTrue(other > 0);
            assertNotEquals("已被占用的端口不应被再次分配", heldPort, other);
        }
    }
}
