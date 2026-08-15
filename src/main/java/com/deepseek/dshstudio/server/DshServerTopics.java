package com.deepseek.dshstudio.server;

import com.intellij.util.messages.Topic;

/**
 * 服务器相关 MessageBus 主题。
 */
public final class DshServerTopics {

    public static final Topic<DshServerListener> SERVER_TOPIC =
            new Topic<>("DshStudio.Server", DshServerListener.class);

    private DshServerTopics() {
    }
}
