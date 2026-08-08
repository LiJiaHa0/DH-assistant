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
    COMPACT_SUMMARY("compact_summary", "上下文压缩摘要提示词"),
    REQUIREMENT_CLARIFICATION("requirement_clarification", "需求澄清提示词"),
    RESEARCH_TOPIC_GENERATION("research_topic_generation", "研究主题生成提示词"),
    PLAN("plan", "生成计划提示词"),
    EXECUTE_PLAN("execute_plan", "执行计划提示词"),
    CRITIQUE("critique", "评审提示词"),
    TOOL_EXECUTE("tool_execute", "工具执行提示词"),
    KEYWORD_EXTRACTION("keyword_extraction", "关键词提取提示词"),
    QUERY_REWRITE("query_rewrite", "查询重写提示词"),
    INTENT_RECOGNITION("intent_recognition", "意图识别提示词"),
    RAG_ANSWER("rag_answer", "知识库回答提示词"),

    ;


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
