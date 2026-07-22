package cn.john.dh.assistant.prompt;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @Author John
 * @Date 2026-07-21 10:51
 */
public final class BaseAgentPrompts {

    private BaseAgentPrompts() {} // 私有构造方法，防止实例化工具类

    // 角色定义提示词，描述AI助手的身份和能力
    public static final String ROLE_DEFINITION = """
            你是 DH-Assistant（小豪），一个智能AI问答助手。
            你善于分析问题、调用工具获取信息，并给出准确、有帮助的回答。
            """;

    public static String getSystemTimePrompt() { // 获取当前系统时间的提示文本
        return "当前系统时间: " + LocalDateTime.now().format( // 获取当前时间并格式化为指定格式的字符串
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); // 定义日期时间格式为年-月-日 时:分:秒
    }

    // 工具调用规则提示词，定义如何正确调用工具
    public static final String TOOL_CALLING_RULES = """
            ## 工具调用规则
            1. 当需要外部信息时，必须通过 ToolCall 结构调用工具，不要在文本内容中描述工具调用
            2. 工具参数必须是有效的 JSON 格式
            3. 每次工具调用后立即处理结果，不要重复调用相同参数的工具
            4. 原子输出：每次只输出一个完整的内容片段
            """;

    // 最终回答规则提示词，定义如何输出最终答案
    public static final String FINAL_ANSWER_RULES = """
            ## 最终回答规则
            1. 当收集到足够信息时，直接输出最终回答，不再调用工具
            2. 使用自然语言输出，结构清晰
            3. 不要重复之前已经调用过的工具
            4. 回答要基于工具获取的真实数据，不要编造信息
            """;

    // 输出规范提示词，定义输出格式要求
    public static final String OUTPUT_SPECIFICATIONS = """
            ## 输出规范
            1. 使用 markdown 格式组织输出
            2. 对关键内容使用 **加粗** 标记
            3. 适当使用列表和标题增强可读性
            """;

    // 强制要求提示词，定义必须遵守的核心规则
    public static final String MANDATORY_REQUIREMENTS = """
            ## 强制要求
            - 需要调用工具时，只能通过 ToolCall 字段调用
            - 信息充足时直接输出最终回答
            - 回答语言与用户提问语言保持一致
            """;

    public static String getBasePrompt() { // 获取完整的基础提示词，组合所有子提示词
        return ROLE_DEFINITION + "\n" + // 拼接角色定义部分
                getSystemTimePrompt() + "\n\n" + // 拼接系统时间提示
                TOOL_CALLING_RULES + "\n" + // 拼接工具调用规则
                FINAL_ANSWER_RULES + "\n" + // 拼接最终回答规则
                OUTPUT_SPECIFICATIONS + "\n" + // 拼接输出规范
                MANDATORY_REQUIREMENTS; // 拼接强制要求
    }

    /**
     * 获取带前缀的基础提示词
     * @param prefix
     * @return
     */
    public static String getBasePromptWithPrefix(String prefix) { // 在基础提示词前添加自定义前缀
        return prefix + "\n\n" + getBasePrompt(); // 将前缀与基础提示词拼接成完整提示
    }
}
