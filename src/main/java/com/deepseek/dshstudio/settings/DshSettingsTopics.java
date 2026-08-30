package com.deepseek.dshstudio.settings;

import com.intellij.util.messages.Topic;

/**
 * 设置变化广播。设置页 apply 时广播，让已打开的工具窗口重新应用
 * 主题 / 背景图 / 浮层等（无需重启 IDE）。
 */
public final class DshSettingsTopics {

    public static final Topic<SettingsListener> SETTINGS_TOPIC =
            Topic.create("dsh.settings.changed", SettingsListener.class);

    private DshSettingsTopics() {
        // utility
    }

    public interface SettingsListener {
        void onChanged();
    }
}
