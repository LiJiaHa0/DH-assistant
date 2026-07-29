package cn.john.dh.assistant.agent.deepresearch;

import cn.john.dh.assistant.constant.AgentType;
import cn.john.dh.assistant.constant.PromptKey;
import cn.john.dh.assistant.prompt.service.AgentPromptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * @Author John
 * @Date 2026-07-27 16:45
 */
@Slf4j
public class SimpleReactAgent {

    // 聊天模型
    private final ChatModel chatModel;
    // 工具回调
    private final ToolCallback[] toolCallback;
    // 聊天客户端
    private ChatClient chatClient;
    // 最大轮数
    private int maxRound;
    // 系统提示词
    private String systemPrompt;

    private AgentPromptService agentPromptService;

    // 工具执行结果监听器，参数为(工具名称, 执行结果)，供外部收集搜索结果等信息
    private BiConsumer<String, String> toolResultListener;

    // JSON解析器
    private static final ObjectMapper MAPPER = new ObjectMapper(); // JSON解析器静态实例

    // 工具调用最大尝试次数（应对SSL握手失败、连接重置等瞬时网络异常）
    private static final int TOOL_MAX_ATTEMPTS = 3;


    public SimpleReactAgent(ChatModel chatModel, ToolCallback[] toolCallback,String systemPrompt,
                            ChatClient chatClient, int maxRound, AgentPromptService agentPromptService,
                            BiConsumer<String, String> toolResultListener) {
        this.chatModel = chatModel;
        this.toolCallback = toolCallback;
        this.systemPrompt = systemPrompt;
        this.chatClient = chatClient;
        this.maxRound = maxRound;
        this.agentPromptService = agentPromptService;
        this.toolResultListener = toolResultListener;
        initChatClient();
        if (this.chatClient == null) { // 校验ChatClient初始化是否成功
            throw new IllegalStateException("ChatClient 初始化失败！"); // 初始化失败时抛出异常
        }
    }

    /**
     * 初始化ChatClient
     * 配置工具调用选项、拦截器和默认工具
     */
    private void initChatClient() {
        try {
            ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder() // 构建工具调用选项
                    .toolCallbacks(toolCallback) // 设置可用工具数组
                    .internalToolExecutionEnabled(false) // 禁用内部自动工具执行，由Agent手动控制
                    .build(); // 完成构建

            ChatClient.Builder builder = ChatClient.builder(chatModel); // 创建ChatClient构建器
            this.chatClient = builder.defaultOptions(toolOptions) // 设置默认工具调用选项
                    .defaultToolCallbacks(toolCallback) // 设置默认工具回调
                    .build(); // 构建ChatClient实例
        } catch (Exception e) {
            throw new RuntimeException("ChatClient 初始化失败：" + e.getMessage(), e); // 初始化失败时抛出运行时异常
        }
    }

    /**
     * 非流式输出
     *
     * @param question 用户问题
     * @return 最终回答字符串
     */
    public String call(String question) { // 非流式调用，无会话记忆
        return callInternal(null, question); // 委托给内部方法
    }

    /**
     * 内部方法，用于调用ChatClient并返回结果
     *
     * @param conversationId 会话ID
     * @param question       用户问题
     * @return 最终回答字符串
     */
    private String callInternal(String conversationId, String question) {
        // 创建线程安全的消息列表
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        // 添加系统提示词
        messages.add(new SystemMessage(agentPromptService.getPromptContentAndBasePrompt(AgentType.REACT_AGENT, PromptKey.EXECUTE_PLAN) + "\n\n" +  systemPrompt));
        // 添加用户问题
        messages.add(new UserMessage("<question>" + question + "</question>"));
        // 初始化轮次计数器
        int round = 0;
        while (true) {
            // 轮次加1
            round++;
            // 超过最大轮数
            if (round > maxRound) {
                log.warn("=== 达到 maxRounds（{}），强制生成最终答案 ===", maxRound);
                // 添加强制生成最终答案的提示
                messages.add(new UserMessage("""
                        你已达到最大推理轮次限制。
                        请基于当前已有的上下文信息，
                        直接给出最终答案。
                        禁止再调用任何工具。
                        如果信息不完整，请合理总结和说明。
                        """));
                // 同步调用获取最终答案
                String finalText = chatClient.prompt().messages(messages).call().content();
                // 返回强制生成的最终答案
                return finalText;
            }
            // 调用LLM获取响应
            ChatClientResponse chatResponse = chatClient
                    .prompt() // 创建提示
                    .messages(messages) // 设置消息列表
                    .call() // 执行同步调用
                    .chatClientResponse(); // 获取客户端响应
            // 提取AI回复文本
            String aiText = chatResponse.chatResponse().getResult().getOutput().getText();
            // 构建助手消息
            AssistantMessage.Builder builder = AssistantMessage.builder().content(aiText);
            // ===== 没有工具调用，视为最终答案 =====
            // 检查是否包含工具调用
            if (!chatResponse.chatResponse().hasToolCalls()) {
                // 返回最终答案
                return aiText;
            }
            // ===== 有工具调用：执行工具 =====
            messages.add(builder.toolCalls(chatResponse.chatResponse().getResult().getOutput().getToolCalls()).build());
            chatResponse.chatResponse() // 遍历所有工具调用
                    .getResult().getOutput().getToolCalls()
                    .forEach(toolCall -> { // 对每个工具调用执行处理
                        String toolName = toolCall.name(); // 获取工具名称
                        String argsJson = toolCall.arguments(); // 获取工具参数

                        ToolCallback callback = findTool(toolName); // 查找工具回调
                        if (callback == null) { // 工具未找到时
                            addErrorToolResponse(messages, toolCall, "工具未找到：" + toolName); // 添加错误响应
                            return; // 跳过当前工具调用
                        }

                        try {
                            Object result = callToolWithRetry(callback, argsJson, toolName); // 执行工具调用（瞬时网络异常自动重试）
                            String resultStr = result.toString(); // 将结果转为字符串
                            // 如果设置了工具结果监听器，回调通知外部（如收集搜索参考来源）
                            if (toolResultListener != null) {
                                toolResultListener.accept(toolName, resultStr);
                            }
                            ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse( // 创建工具响应
                                    toolCall.id(), toolName, resultStr); // 包含ID、名称和结果
                            messages.add(ToolResponseMessage.builder().responses(List.of(tr)).build()); // 添加到消息列表
                        } catch (Exception ex) {
                            addErrorToolResponse(messages, toolCall, "工具执行失败：" + ex.getMessage()); // 执行异常时添加错误响应
                        }
                    });
        }
    }

    /**
     * 执行工具调用，瞬时网络异常时自动重试
     * 每次重试前按尝试次数递增退避等待（1s、2s）
     *
     * @param callback 工具回调
     * @param argsJson 工具参数JSON
     * @param toolName 工具名称（用于日志）
     * @return 工具执行结果
     */
    private Object callToolWithRetry(ToolCallback callback, String argsJson, String toolName) {
        RuntimeException lastError = null; // 记录最后一次异常
        for (int attempt = 1; attempt <= TOOL_MAX_ATTEMPTS; attempt++) { // 按最大尝试次数循环
            try {
                return callback.call(argsJson); // 执行工具调用，成功则直接返回
            } catch (RuntimeException ex) {
                lastError = ex; // 记录本次异常
                if (attempt >= TOOL_MAX_ATTEMPTS || !isTransientNetworkError(ex)) { // 已达最大次数或非瞬时网络异常
                    break; // 不再重试
                }
                long backoffMillis = attempt * 1000L; // 计算退避时间（随尝试次数递增）
                log.warn("工具 {} 第 {} 次调用失败（{}），{}ms 后重试", toolName, attempt, ex.getMessage(), backoffMillis);
                try {
                    Thread.sleep(backoffMillis); // 退避等待后重试
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // 恢复中断状态
                    break; // 被中断时停止重试
                }
            }
        }
        throw lastError; // 重试耗尽，抛出最后一次异常由调用方兜底
    }

    /**
     * 判断是否为瞬时网络异常（可重试）
     * 沿异常链查找IO类异常，覆盖SSL握手失败、连接重置、超时等场景
     * （Reactor会将底层异常包装为ReactiveException，故需遍历cause链）
     *
     * @param ex 待判断的异常
     * @return true表示瞬时网络异常，可重试
     */
    private boolean isTransientNetworkError(Throwable ex) {
        Throwable cur = ex; // 从异常本身开始
        while (cur != null) { // 遍历整个cause链
            if (cur instanceof IOException) { // SSLException/SocketException等均为IOException子类
                return true; // 判定为瞬时网络异常
            }
            cur = cur.getCause(); // 继续查找下一层原因
        }
        return false; // 非网络类异常，不重试
    }

    /**
     * 根据名称查找工具回调
     */
    private ToolCallback findTool(String name) {
        for (ToolCallback t : toolCallback) { // 遍历工具数组
            if (t.getToolDefinition().name().equals(name)) { // 工具名称匹配
                return t; // 返回匹配的工具回调
            }
        }
        return null; // 未找到时返回null
    }

    /**
     * @param messages
     * @param toolCall
     * @param errMsg
     */
    private void addErrorToolResponse(List<Message> messages, AssistantMessage.ToolCall toolCall, String errMsg) {
        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse( // 创建错误响应
                toolCall.id(), // 工具调用ID
                toolCall.name(), // 工具名称
                "{ \"error\": \"" + errMsg + "\" }" // 错误信息JSON
        );
        messages.add(ToolResponseMessage.builder().responses(List.of(tr)).build()); // 添加到消息列表
    }

    /**
     * 获取Builder实例的静态工厂方法
     */
    public static Builder builder() {
        return new Builder(); // 创建并返回Builder
    }

    public static class Builder {
        private ChatModel chatModel;
        private ToolCallback[] tools = new ToolCallback[0]; // 工具数组，默认空
        private String systemPrompt;
        private ChatClient chatClient;
        private int maxRound;
        private AgentPromptService agentPromptService;
        private BiConsumer<String, String> toolResultListener;

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        /**
         * 设置工具数组（可变参数）
         */
        public Builder tools(ToolCallback... tools) {
            this.tools = tools; // 直接赋值数组
            return this; // 返回Builder
        }

        /**
         * 设置工具列表
         */
        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools.toArray(new ToolCallback[0]); // 列表转数组
            return this; // 返回Builder
        }

        public Builder chatClient(ChatClient chatClient) {
            this.chatClient = chatClient;
            return this;
        }

        public Builder maxRound(int maxRound) {
            this.maxRound = maxRound;
            return this;
        }

        public Builder agentPromptService(AgentPromptService agentPromptService) {
            this.agentPromptService = agentPromptService;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * 设置工具执行结果监听器，参数为(工具名称, 执行结果)
         */
        public Builder toolResultListener(BiConsumer<String, String> toolResultListener) {
            this.toolResultListener = toolResultListener;
            return this;
        }

        public SimpleReactAgent build() {
            if (chatModel == null) { // 校验聊天模型
                throw new IllegalArgumentException("chatModel 不能为空！"); // 抛出异常
            }
            return new SimpleReactAgent(chatModel, tools, systemPrompt,  chatClient, maxRound, agentPromptService, toolResultListener);
        }
    }

}
