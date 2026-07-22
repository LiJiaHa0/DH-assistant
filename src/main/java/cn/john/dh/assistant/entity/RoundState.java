package cn.john.dh.assistant.entity;

import lombok.Setter;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @Author John
 * @Date 2026-07-21 18:19
 */
@Setter
public class RoundState {

    // 当前轮次执行模式，默认为未知
    public RoundMode mode = RoundMode.UNKNOWN;
    // 文本内容缓冲区
    public final StringBuilder textBuffer = new StringBuilder();
    // 工具调用列表，使用线程安全的CopyOnWriteArrayList
    public final List<AssistantMessage.ToolCall> toolCalls = new CopyOnWriteArrayList<>();
    // 是否处于思考阶段标记
    public boolean inThink = false;

    /**
     * 获取当前轮次模式
     *
     * @return 当前执行模式
     */
    public RoundMode getMode() { return mode; }



    /**
     * 获取文本缓冲区
     *
     * @return 文本缓冲区
     */
    public StringBuilder getTextBuffer() { return textBuffer; }

    /**
     * 获取工具调用列表
     *
     * @return 工具调用列表
     */
    public List<AssistantMessage.ToolCall> getToolCalls() { return toolCalls; }

    /**
     * 判断是否处于思考阶段
     *
     * @return 是否在思考阶段
     */
    public boolean isInThink() { return inThink; }

}
