package com.deepseek.dshstudio.server;

/**
 * 服务器状态变化 / 日志输出的监听器（通过 MessageBus 派发，见 {@link DshServerTopics}）。
 */
public interface DshServerListener {

    /** 服务器状态变化。 */
    default void onStateChanged(DshServerManager.ServerState state) {
    }

    /** 新增日志片段（按行追加）。 */
    default void onLog(String chunk) {
    }
}
