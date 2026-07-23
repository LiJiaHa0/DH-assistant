package cn.john.dh.assistant.agent;

import cn.john.dh.assistant.chat.domain.entity.ChatMessage;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.common.AgentResponse;
import cn.john.dh.assistant.constant.AgentType;
import cn.john.dh.assistant.constant.ChatMessageType;
import cn.john.dh.assistant.constant.PromptKey;
import cn.john.dh.assistant.prompt.service.AgentPromptService;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.*;

/**
 * @Author John
 * @Date 2026-07-20 10:14
 */
public abstract class BaseAgent {

    // 日志记录器
    protected static final Logger log = LoggerFactory.getLogger(BaseAgent.class);

    // AI聊天模型
    protected ChatModel chatModel;
    // Agent名称
    protected String name;
    // 聊天记忆
    protected ChatMemory chatMemory;

    // 聊天会话服务
    protected ChatConversationService chatConversationService;

    // 聊天消息服务
    protected ChatMessageService chatMessageService;

    // Agent提示服务
    protected AgentPromptService agentPromptService;

    // 任务管理器
    protected AgentTaskManager taskManager;
    // Agent类型标识
    protected String agentType;
    // 是否启用推荐问题
    protected boolean enableRecommendations = true;

    // 已使用的工具名称集合
    protected Set<String> usedTools = new HashSet<>();
    // 当前对话ID
    protected String currentConversationId;
    // 当前用户问题
    protected String currentQuestion;
    // 当前推荐问题
    protected String currentRecommendations;

    /**
     * 构造函数，初始化Agent基础属性
     *
     * @param name      Agent名称
     * @param chatModel AI聊天模型
     * @param agentType Agent类型标识
     */
    public BaseAgent(String name, ChatModel chatModel, String agentType) {
        // 设置Agent名称
        this.name = name;
        // 设置聊天模型
        this.chatModel = chatModel;
        // 设置Agent类型标识
        this.agentType = agentType;
    }

    /**
     * 执行Agent的核心方法
     * 子类必须实现此方法以定义具体的执行逻辑，返回SSE流式响应
     *
     * @param conversationId 会话ID
     * @param question       用户问题
     * @return SSE流式响应的Flux
     */
    public abstract Flux<String> execute(String conversationId, String question);

    /**
     * 加载聊天历史记录
     *
     * @param conversationId 会话ID
     * @param maxMessages    最大消息数
     */
    protected void loadChatHistory(List<Message> messageList, String conversationId, int maxMessages) {
        List<ChatMessage> messages = chatMessageService.listByConversationId(conversationId, maxMessages);
        for (ChatMessage dbMessage : messages) {
            if (dbMessage.getType() == ChatMessageType.USER) {
                messageList.add(new UserMessage(dbMessage.getContent()));
            } else if (dbMessage.getType() == ChatMessageType.ASSISTANT) {
                messageList.add(new AssistantMessage(dbMessage.getContent()));
            }
        }
    }


    /**
     * 生成推荐问题
     *
     * @param currentQuestion 当前问题
     * @param currentAnswer   当前答案
     * @return 推荐问题JSON字符串，失败返回null
     */
    protected String generateRecommendations(String currentQuestion, String currentAnswer, List<Message> historyMessage) {
        if (!enableRecommendations) {
            return null;
        }
        try {
            // 创建用于发送给模型的消息列表
            List<Message> messages = new ArrayList<>();
            //推荐问题的系统提示词
            messages.add(new SystemMessage(agentPromptService.getPromptContent(AgentType.REACT_AGENT, PromptKey.RECOMMEND_PROMPT)));
            if (!CollectionUtils.isEmpty(historyMessage)) {
                historyMessage.add(new UserMessage("历史消息："));
                messages.addAll(historyMessage);
            }
            // 添加系统消息，包含推荐问题的提示
            messages.add(new UserMessage("当前问题："));
            messages.add(new UserMessage(currentQuestion));
            messages.add(new AssistantMessage("当前回答"));
            messages.add(new AssistantMessage(currentAnswer));

            //添加格式说明消息
            // 使用 BeanOutputConverter 进行结构化输出
            BeanOutputConverter<List<String>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
            });
            // 使用chatModel构建ChatClient
            String response = ChatClient.builder(chatModel)
                    .defaultOptions(DashScopeChatOptions.builder()
                            .model("qwen-turbo")
                            .enableThinking(false).build())
                    .build()
                    // 创建提示词请求
                    .prompt()
                    // 设置消息列表
                    .messages(messages)
                    // 发起同步调用
                    .call()
                    // 获取响应内容
                    .content();
            if (StringUtils.hasText(response)) {
                // 使用转换器将模型响应解析为字符串列表
                List<String> recommendations = converter.convert(response);
                // 如果解析后的推荐列表非空
                if (recommendations != null && !recommendations.isEmpty()) {
                    // 将推荐列表序列化为JSON字符串
                    String jsonStr = JSON.toJSONString(recommendations);
                    // 记录成功日志
                    log.info("生成推荐问题成功: {}", jsonStr);
                    // 返回推荐问题的JSON字符串
                    return jsonStr;
                }
            }
            // 如果响应为空或格式无效，记录警告日志
            log.warn("生成推荐问题失败，响应格式无效: {}", response);
            // 返回null表示生成失败
            return null;
        } catch (Exception e) {
            // 捕获异常并记录错误日志
            log.error("生成推荐问题异常", e);
            // 发生异常时返回null
            return null;
        }
    }

    /**
     * 检查是否有正在运行的任务，如有则返回错误Flux
     * 防止同一会话重复提交任务
     *
     * @param conversationId 会话ID
     * @return 错误Flux（有运行中任务时），或null（无运行中任务时）
     */
    protected Flux<String> checkRunningTask(String conversationId) {
        // 任务管理器非空且存在运行中任务时
        if (conversationId != null && taskManager != null && taskManager.hasRunningTask(conversationId)) {
            // 返回错误流
            return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
        }
        // 没有运行中的任务
        return null;
    }

    /**
     * 注册任务到任务管理器
     * 用于任务追踪和取消控制
     *
     * @param conversationId 会话ID
     * @param sink           响应式信号发射器
     * @return 任务信息对象，注册失败返回null
     */
    protected AgentTaskManager.TaskInfo registerTask(String conversationId, Sinks.Many<String> sink) {
        // 会话ID和任务管理器均非空时注册
        if (conversationId != null && taskManager != null) {
            // 调用任务管理器注册任务
            AgentTaskManager.TaskInfo taskInfo = taskManager.registerTask(conversationId, sink, agentType);
            // 注册失败时记录警告日志
            if (taskInfo == null) {
                log.warn("任务注册失败: conversationId={}", conversationId);
            }
            // 返回任务信息对象
            return taskInfo;
        }
        // 会话ID或任务管理器为空时返回null
        return null;
    }

    /**
     * 创建会话，并且创建虚拟线程根据用户问题重写会话标题
     *
     * @param userId
     * @param question
     * @return
     */
    protected String createConversation(String userId, String question) {
        String conversation = chatConversationService.createConversation(userId, question);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("你是一个对话标题生成助手。根据用户的第一句话，生成一个简洁的中文会话标题，要求：不超过20个字，不加引号，直接输出标题内容。"));
        messages.add(new UserMessage("请根据当前问题生成会话标题：" + question));
        Thread.ofVirtual().name("title" + conversation).start(() -> {
            // 使用chatModel构建ChatClient
            String response = ChatClient.builder(chatModel)
                    .defaultOptions(DashScopeChatOptions.builder()
                            .model("qwen-turbo")
                            .enableThinking(false).build())
                    .build()
                    // 创建提示词请求
                    .prompt()
                    // 设置消息列表
                    .messages(messages)
                    // 发起同步调用
                    .call()
                    // 获取响应内容
                    .content();
            chatConversationService.updateTitle(conversation, response);
        });
        return conversation;
    }

    /**
     * 记录使用的工具
     *
     * @param toolName 工具名称
     */
    protected void recordUsedTool(String toolName) {
        // 工具集合和工具名均非空时添加
        if (usedTools != null && toolName != null) {
            usedTools.add(toolName);
        }
    }

    /**
     * 创建文本类型的SSE响应
     *
     * @param content 文本内容
     * @return JSON格式的响应字符串
     */
    protected String createTextResponse(String content) {
        // 调用AgentResponse生成文本JSON
        return AgentResponse.text(content);
    }


    /**
     * 创建思考类型的SSE响应
     *
     * @param content 思考过程内容
     * @return JSON格式的响应字符串
     */
    protected String createThinkingResponse(String content) {
        // 调用AgentResponse生成思考JSON
        return AgentResponse.thinking(content);
    }


    /**
     * 创建参考信息类型的SSE响应
     *
     * @param references 参考信息内容
     * @return JSON格式的响应字符串
     */
    protected String createReferenceResponse(String references) {
        // 调用AgentResponse生成参考信息JSON
        return AgentResponse.json("reference", references);
    }

    /**
     * 创建推荐问题类型的SSE响应
     *
     * @param questions 推荐问题内容
     * @return JSON格式的响应字符串
     */
    protected String createRecommendResponse(String questions) {
        // 调用AgentResponse生成推荐问题JSON
        return AgentResponse.json("recommend", questions);
    }

    /**
     * 创建流式响应结束标记
     *
     * @return JSON格式的结束响应字符串
     */
    protected String createCompleteResponse() {
        // 调用AgentResponse生成结束标记JSON
        return AgentResponse.complete();
    }

}
