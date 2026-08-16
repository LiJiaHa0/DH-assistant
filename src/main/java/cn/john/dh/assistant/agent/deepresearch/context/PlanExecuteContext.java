package cn.john.dh.assistant.agent.deepresearch.context;

import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 计划执行上下文
 * @Author John
 * @Date 2026-07-24 14:43
 */
@Data
public class PlanExecuteContext {

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 消息列表
     */
    private List<Message> messages = new ArrayList<>();
    /**
     * 用户问题
     */
    private String question;

    /**
     * 优化后的研究主题
     */
    private String optimizingResearchTopics;

    // 收集最终答案（纯文本），用于存储到数据库的memory
    final StringBuilder finalAnswerBuffer = new StringBuilder();

    // 收集思考过程，用于存储到数据库
    final StringBuilder thinkingBuffer = new StringBuilder();

    // 是否已发送最终结果的标记位
    final AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);

    /**
     * 是否已进行需求澄清
     */
    AtomicBoolean hasRequirementClarificationThink = new AtomicBoolean(false);
    /**
     * 是否已确定研究主题
     */
    AtomicBoolean hasResearchTopicThink = new AtomicBoolean(false);

    /**
     * 是否已生成最终总结
     */
    AtomicBoolean hasSummarizeThink = new AtomicBoolean(false);

    // 是否正在思考
    boolean inThink = false;

    /**
     * 当前轮次
     */
    private int round;

    // 累计 prompt tokens（流式 Usage 通常在最后一个 chunk 出现）
    public final AtomicLong promptTokens = new AtomicLong(0);
    // 累计 generation tokens
    public final AtomicLong generationTokens = new AtomicLong(0);

    public PlanExecuteContext(String conversationId, String question) {
        this.conversationId = conversationId;
        this.question = question;
    }

    /**
     * 添加一条消息到对话列表
     *
     * @param m 要添加的Spring AI消息对象
     */
    public void add(Message m) {
        // 将消息追加到列表末尾
        messages.add(m);
    }

    /**
     * 进入下一轮执行
     * 每次Plan-Execute循环迭代时调用
     */
    public void nextRound() {
        // 轮次加1
        round++;
    }

    /**
     * 渲染完整上下文（过滤历史Critique，只保留最近一次）
     * 用于generatePlan阶段，避免历史评审反馈干扰新计划的生成
     * 输出格式为：[MessageType]\n\n消息文本
     *
     * @return 渲染后的完整上下文字符串
     */
    public String renderFullContext() {
        // 查找最后一条评审反馈的位置
        int lastCritiqueIndex = findLastCritiqueIndex();

        // 创建字符串构建器
        StringBuilder sb = new StringBuilder();
        // 按索引遍历所有消息
        for (int i = 0; i < messages.size(); i++) {
            // 获取当前消息
            Message m = messages.get(i);
            // 获取消息文本
            String text = m.getText();

            // 如果这是之前轮次的Critique Feedback，跳过（只保留最近一条）
            if (i < lastCritiqueIndex && text != null && text.contains("【Critique Feedback】")) {
                // 跳过此消息，不加入上下文
                continue;
            }

            // 以[MessageType]格式包装消息，便于LLM识别消息来源
            sb.append("\n\n[").append(m.getMessageType()).append("]\n\n")
                    // 追加消息文本内容
                    .append(text);
        }
        // 返回渲染后的完整上下文
        return sb.toString();
    }

    /**
     * 找到最近一次Critique Feedback的索引
     * 从后往前遍历消息列表，找到第一个包含评审反馈标记的消息
     *
     * @return 最后一条Critique Feedback的索引，不存在则返回-1
     */
    private int findLastCritiqueIndex() {
        // 从后向前遍历消息列表
        for (int i = messages.size() - 1; i >= 0; i--) {
            // 获取当前消息的文本
            String text = messages.get(i).getText();
            // 如果是评审反馈消息
            if (text != null && text.contains("【Critique Feedback】")) {
                // 返回该消息的索引
                return i;
            }
        }
        // 没有找到评审反馈则返回-1
        return -1;
    }

    /**
     * 计算当前所有消息的总字符数
     * 用于判断是否超过上下文窗口限制
     *
     * @return 所有消息文本的总字符数
     */
    public int currentChars() {
        // 将消息列表转为流
        return messages.stream()
                // 映射每条消息的文本长度，空文本按0计算
                .mapToInt(m -> m.getText() == null ? 0 : m.getText().length())
                // 求和得到总字符数
                .sum();
    }

    /**
     * 提取所有工具执行结果
     * 用于summarize阶段生成最终报告，仅保留包含任务结果的消息
     *
     * @return 所有已完成任务结果的拼接字符串
     */
    public String extractToolResults() {
        // 创建字符串构建器
        StringBuilder sb = new StringBuilder();
        // 遍历所有消息
        for (Message m : messages) {
            // 获取消息文本
            String text = m.getText();
            // 过滤出包含任务结果标记的消息
            if (text != null && text.contains("【Completed Task Result】")) {
                // 追加结果文本并用双换行分隔
                sb.append(text).append("\n\n");
            }
        }
        // 返回所有工具执行结果的拼接字符串
        return sb.toString();
    }

}
