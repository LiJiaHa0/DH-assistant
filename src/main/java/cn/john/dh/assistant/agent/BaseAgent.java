package cn.john.dh.assistant.agent;

import cn.john.dh.assistant.chat.domain.entity.ChatMessage;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.chat.service.ChatTokenLimitService;
import cn.john.dh.assistant.chat.util.ChatTokenUsageUtil;
import cn.john.dh.assistant.common.AgentResponse;
import cn.john.dh.assistant.constant.AgentType;
import cn.john.dh.assistant.constant.ChatMessageType;
import cn.john.dh.assistant.constant.PromptKey;
import cn.john.dh.assistant.prompt.service.AgentPromptService;
import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @Author John
 * @Date 2026-07-20 10:14
 */
public abstract class BaseAgent {

    // 日志记录器
    protected static final Logger log = LoggerFactory.getLogger(BaseAgent.class);

    // AI聊天模型
    protected ChatModel chatModel;
    // 轻量任务专用模型（本地Ollama，用于标题生成、推荐问题等），未设置时回退使用chatModel
    protected ChatModel titleModel;
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

    // 每日聊天 Token 限制服务
    protected ChatTokenLimitService chatTokenLimitService;

    // 任务管理器
    protected AgentTaskManager taskManager;
    // Agent类型标识
    protected String agentType;
    // 是否启用推荐问题
    protected boolean enableRecommendations = true;

    // 已使用的工具名称集合
    protected Set<String> usedTools = new HashSet<>();
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
     * 设置轻量任务专用模型
     * 用于会话标题生成、推荐问题生成等轻量场景，避免消耗云端模型配额
     *
     * @param titleModel 轻量任务模型（如本地Ollama模型）
     */
    public void setTitleModel(ChatModel titleModel) {
        // 设置轻量任务专用模型
        this.titleModel = titleModel;
    }

    /**
     * 设置每日聊天 Token 限制服务
     *
     * @param chatTokenLimitService Token 限制服务
     */
    public void setChatTokenLimitService(ChatTokenLimitService chatTokenLimitService) {
        this.chatTokenLimitService = chatTokenLimitService;
    }

    /**
     * 获取当前主聊天模型的名称，用于落库统计
     *
     * @return 模型名称，无法识别时返回类名简写
     */
    protected String getModelName() {
        if (chatModel == null) {
            return "unknown";
        }
        try {
            var defaultOptions = chatModel.getDefaultOptions();
            if (defaultOptions != null) {
                // Spring AI 各模型实现通常把 model 放在 options 中
                var modelField = defaultOptions.getClass().getDeclaredField("model");
                modelField.setAccessible(true);
                Object modelValue = modelField.get(defaultOptions);
                if (modelValue != null) {
                    return modelValue.toString();
                }
            }
        } catch (Exception e) {
            // 忽略反射异常，回退到类名
        }
        return chatModel.getClass().getSimpleName();
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
     * 生成推荐问题（不记录 token 的兼容版本）
     *
     * @param currentQuestion 当前问题
     * @param currentAnswer   当前答案
     * @return 推荐问题JSON字符串，失败返回null
     */
    protected String generateRecommendations(String currentQuestion, String currentAnswer, List<Message> historyMessage) {
        return generateRecommendations(currentQuestion, currentAnswer, historyMessage, null, null);
    }

    /**
     * 生成推荐问题，并记录 token 使用量
     *
     * @param currentQuestion 当前问题
     * @param currentAnswer   当前答案
     * @param promptTokens    prompt token 计数器
     * @param generationTokens generation token 计数器
     * @return 推荐问题JSON字符串，失败返回null
     */
    protected String generateRecommendations(String currentQuestion, String currentAnswer, List<Message> historyMessage,
                                              AtomicLong promptTokens, AtomicLong generationTokens) {
        if (!enableRecommendations) {
            return null;
        }
        try {
            // 创建用于发送给模型的消息列表
            List<Message> messages = new ArrayList<>();
            //推荐问题的系统提示词
            messages.add(new SystemMessage(agentPromptService.getPromptContent(AgentType.REACT_AGENT, PromptKey.RECOMMEND_PROMPT)));
            if (!CollectionUtils.isEmpty(historyMessage)) {
                messages.add(new UserMessage("历史消息："));
                // 复制而非直接 addAll(historyMessage)：原实现先向调用方列表插入"历史消息："再整体复制，
                // 会污染调用方的消息列表（后续轮次该标记会残留到模型上下文）
                messages.addAll(new ArrayList<>(historyMessage));
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
            // 把结构化输出格式说明拼进 prompt，并额外强调只输出 JSON 数组，
            // 提高本地轻量模型（Ollama）输出合法 JSON 的概率
            messages.add(new SystemMessage(converter.getFormat()));
            messages.add(new UserMessage("注意：请只输出一个 JSON 字符串数组，不要输出任何解释性文字或思考过程。"));
            // 推荐问题改用主模型：本地轻量模型（Ollama）常不遵循 JSON 数组输出导致解析失败，
            // 云端主模型（百炼 qwen3.6-flash）对结构化输出的遵循度更高、更稳定
            ChatModel model = chatModel;
            // 使用轻量模型构建ChatClient
            ChatClientResponse response = ChatClient.builder(model).build()
                    // 创建提示词请求
                    .prompt()
                    // 设置消息列表
                    .messages(messages)
                    // 发起同步调用
                    .call()
                    // 获取完整客户端响应（含 Usage）
                    .chatClientResponse();
            String text = null;
            if (response != null && response.chatResponse() != null
                    && response.chatResponse().getResult() != null
                    && response.chatResponse().getResult().getOutput() != null) {
                text = response.chatResponse().getResult().getOutput().getText();
                // 记录 token 使用量
                if (promptTokens != null && generationTokens != null) {
                    ChatTokenUsageUtil.recordUsage(response.chatResponse(), promptTokens, generationTokens);
                }
            }
            if (StringUtils.hasText(text)) {
                // 容错解析：轻量模型可能不严格输出 JSON 数组，标准转换失败后手动提取
                List<String> recommendations = parseRecommendations(converter, text);
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
            log.warn("生成推荐问题失败，响应格式无效: {}", text);
            // 返回null表示生成失败
            return null;
        } catch (Exception e) {
            // 推荐问题生成失败不影响主流程，降级为 warn（模型输出不符合预期属常见情况，无需打印完整堆栈）
            log.warn("生成推荐问题失败: {}", e.getMessage());
            // 发生异常时返回null
            return null;
        }
    }

    /**
     * 容错解析推荐问题：优先用 BeanOutputConverter 标准转换（模型严格输出 JSON 数组时）；
     * 转换失败（如本地轻量模型返回了自然语言）时，尝试从文本中提取 JSON 数组。
     *
     * @param converter 结构化输出转换器
     * @param text      模型原始输出文本
     * @return 推荐问题列表，无法解析时返回 null
     */
    private List<String> parseRecommendations(BeanOutputConverter<List<String>> converter, String text) {
        // 1. 标准结构化转换（模型正确输出 JSON 数组时走这里）
        try {
            List<String> list = converter.convert(text);
            if (list != null && !list.isEmpty()) {
                return list;
            }
        } catch (Exception e) {
            // 模型未按 JSON 数组输出，降级到手动提取
        }
        // 2. 手动提取 JSON 数组（兼容模型输出带额外说明文字/markdown 代码块的情况）
        try {
            String trimmed = text.trim();
            // 去掉可能的 markdown 代码块包裹
            if (trimmed.startsWith("```")) {
                trimmed = trimmed.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            // 先尝试整体按 JSON 数组解析
            List<String> list = JSON.parseArray(trimmed, String.class);
            if (list != null && !list.isEmpty()) {
                return list;
            }
            // 提取第一个 [...] 片段再解析
            int start = trimmed.indexOf('[');
            int end = trimmed.lastIndexOf(']');
            if (start >= 0 && end > start) {
                String arrStr = trimmed.substring(start, end + 1);
                list = JSON.parseArray(arrStr, String.class);
                if (list != null && !list.isEmpty()) {
                    return list;
                }
            }
        } catch (Exception e) {
            // 忽略，返回 null 即可
        }
        return null;
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
     * 清除已使用的工具记录集合
     */
    protected void clearUsedTools() {
        // 工具集合非空时清空
        if (usedTools != null) {
            usedTools.clear();
        }
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
            try {
                // 优先使用标题生成专用模型（本地Ollama），未配置时回退到主模型
                ChatModel model = titleModel != null ? titleModel : chatModel;
                // 使用标题模型构建ChatClient
                String response = ChatClient.builder(model).build()
                        // 创建提示词请求
                        .prompt()
                        // 设置消息列表
                        .messages(messages)
                        // 发起同步调用
                        .call()
                        // 获取响应内容
                        .content();
                chatConversationService.updateTitle(conversation, response);
            } catch (Exception e) {
                // 标题生成失败不影响主流程，仅记录错误日志
                log.error("会话标题生成失败: conversationId={}", conversation, e);
            }
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

    /** 流式分块间隔（毫秒），模拟打字机效果，避免前端瞬间渲染全部内容 */
    private static final long STREAM_CHUNK_DELAY_MS = 30L;

    /**
     * 发送响应（带思考缓冲区参数版本）
     * 内容过长时自动拆分为 3~7 字符分块，模拟流式逐字推送
     *
     * @param sink     响应流信号发射器
     * @param finished 完成标记
     * @param content  响应内容
     * @param type     响应类型（text/thinking）
     */
    protected void emit(Sinks.Many<String> sink,
                        AtomicBoolean finished,
                        String content,
                        String type) {
        // 如果流程已完成，不再发送
        if (finished.get() || content == null || content.isEmpty()) {
            return;
        }
        // 短内容直接整块发送
        if (content.length() <= 7) {
            doSend(sink, content, type);
            return;
        }
        // 长内容拆分为 3~7 字符分块逐段发送
        int offset = 0;
        while (offset < content.length()) {
            // 流程已被中断则提前退出
            if (finished.get()) {
                return;
            }
            // 随机决定本块长度（3~7字符，剩余不足时取剩余部分）
            int chunkLen = Math.min(ThreadLocalRandom.current().nextInt(3, 8), content.length() - offset);
            int end = offset + chunkLen;
            // 避免切割代理对：emoji等增补字符由2个char组成，若切点落在高低代理之间则向后顺延一位
            if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) {
                end++;
            }
            String chunk = content.substring(offset, end);
            doSend(sink, chunk, type);
            // 分块间短暂延迟，模拟打字机效果，避免前端瞬间渲染全部内容
            try {
                Thread.sleep(STREAM_CHUNK_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            offset = end;
        }
    }

    /**
     * 流式发送响应（分块 + 代理对保护 + 并发安全）
     * 内容自动拆分为 3~7 字符分块逐段发送，模拟流式逐字推送效果；
     * 切点处检测UTF-16代理对（emoji等增补字符），若切点落在高低代理之间则顺延一位，保证emoji不被切割；
     * 与emit的区别：在整个分块过程中持有sink锁，保证一条消息的所有小块连续发送完毕，
     * 不会被其他并发任务的小块穿插交错（emit每个小块各自加锁，适合单线程LLM流式输出）。
     *
     * @param sink     响应流信号发射器
     * @param finished 完成标记
     * @param content  响应内容
     * @param type     响应类型（text/thinking）
     */
    protected void emitAtomic(Sinks.Many<String> sink,
                              AtomicBoolean finished,
                              String content,
                              String type) {
        if (finished.get() || content == null || content.isEmpty()) {
            return;
        }
        // 整个分块过程在锁内完成，保证所有小块连续发送，不被其他线程穿插
        synchronized (sink) {
            if (content.length() <= 7) {
                sendChunk(sink, content, type);
                return;
            }
            int offset = 0;
            while (offset < content.length()) {
                if (finished.get()) {
                    return;
                }
                int chunkLen = Math.min(ThreadLocalRandom.current().nextInt(3, 8), content.length() - offset);
                int end = offset + chunkLen;
                if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) {
                    end++;
                }
                sendChunk(sink, content.substring(offset, end), type);
                offset = end;
            }
        }
    }

    /**
     * 发送错误到流
     * 使用CAS确保只发送一次错误
     *
     * @param sink     响应流信号发射器
     * @param finished 完成标记
     * @param e        异常对象
     */
    protected void error(Sinks.Many<String> sink,
                         AtomicBoolean finished,
                         Throwable e) {

        // CAS操作：仅当finished为false时设为true
        if (finished.compareAndSet(false, true)) {
            // 发送错误信号
            sink.tryEmitError(e);
        }
    }

    /**
     * 实际发送单个分块（不加锁，由调用方负责同步）
     *
     * @param sink  响应流信号发射器
     * @param chunk 本次发送的文本片段
     * @param type  响应类型（text/thinking）
     */
    private void sendChunk(Sinks.Many<String> sink, String chunk, String type) {
        if ("thinking".equals(type)) {
            sink.tryEmitNext(createThinkingResponse(chunk));
        } else {
            sink.tryEmitNext(createTextResponse(chunk));
        }
    }

    /**
     * 实际发送单个分块（加锁版本，供emit单线程流式输出使用）
     * 注意：unicast sink的tryEmitNext非线程安全，并发任务多线程同时emit会返回
     * FAIL_NON_SERIALIZED导致消息静默丢失（如某个任务的"正在执行任务"提示不显示）。
     * 用synchronized串行化emit，保证并发场景下每条消息都能成功发送。
     *
     * @param sink  响应流信号发射器
     * @param chunk 本次发送的文本片段
     * @param type  响应类型（text/thinking）
     */
    private void doSend(Sinks.Many<String> sink, String chunk, String type) {
        synchronized (sink) {
            sendChunk(sink, chunk, type);
        }
    }

}
