package cn.john.dh.assistant.constant;

/**
 * Agent类型枚举
 *
 * @Author John
 * @Date 2026-07-21
 */
public enum AgentType {

    REACT_AGENT("react_agent", "ReAct推理Agent"),
    WEB_SEARCH("web_search", "联网搜索Agent"),
    FILE("file", "文件分析Agent"),
    SKILLS("skills", "全能技能Agent");

    private final String code;
    private final String description;

    AgentType(String code, String description) {
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
