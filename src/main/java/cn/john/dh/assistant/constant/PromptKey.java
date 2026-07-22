package cn.john.dh.assistant.constant;

/**
 * Prompt用途标识枚举
 *
 * @Author John
 * @Date 2026-07-21
 */
public enum PromptKey {

    SYSTEM_PROMPT("system_prompt", "系统提示词"),
    RECOMMEND_PROMPT("recommend_prompt", "推荐问题生成提示词"),
    COMPACT_SUMMARY("compact_summary", "上下文压缩摘要提示词");

    private final String code;
    private final String description;

    PromptKey(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

}
