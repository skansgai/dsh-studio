package com.deepseek.dshstudio.server;

import org.jetbrains.annotations.Nullable;

/**
 * 会话摘要（对应 dsh Remote API <code>session.list</code> 返回的 SessionSummary）。
 * <p>
 * 字段均为可选容错解析：不同 dsh 版本的字段集合可能变化，缺失的字段保持默认值。
 */
public final class DshSessionSummary {

    public final String sessionId;
    @Nullable
    public final String title;
    /** 最近活动时间（Unix 毫秒）；未知时为 0。 */
    public final long updatedAt;
    public final boolean running;
    /** 空会话（尚无对话轮次）。 */
    public final boolean blank;

    public DshSessionSummary(String sessionId,
                             @Nullable String title,
                             long updatedAt,
                             boolean running,
                             boolean blank) {
        this.sessionId = sessionId;
        this.title = title;
        this.updatedAt = updatedAt;
        this.running = running;
        this.blank = blank;
    }

    /** 展示名：优先标题，否则使用 sessionId 的短形式。 */
    public String displayTitle() {
        if (title != null && !title.trim().isEmpty()) {
            return title.trim();
        }
        // session-xxxxxxxx-xxxx… → 取末段
        String id = sessionId;
        int dash = id.lastIndexOf('-');
        String shortId = dash > 0 && dash < id.length() - 1 ? id.substring(dash + 1) : id;
        return "session " + (shortId.length() > 12 ? shortId.substring(0, 12) : shortId);
    }
}
