package cn.john.dh.assistant.agent.deepresearch.context;

import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private String query;

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

    // 是否正在思考
    boolean inThink = false;

    /**
     * 当前轮次
     */
    private int round;

    public PlanExecuteContext(String conversationId, String query) {
        this.conversationId = conversationId;
        this.query = query;
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
}
