package cn.john.dh.assistant.agent.websearch;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.agent.AgentTaskManager;
import cn.john.dh.assistant.agent.BaseAgent;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.constant.AgentType;
import cn.john.dh.assistant.constant.ChatMessageType;
import cn.john.dh.assistant.constant.PromptKey;
import cn.john.dh.assistant.entity.AgentState;
import cn.john.dh.assistant.entity.RoundMode;
import cn.john.dh.assistant.entity.RoundState;
import cn.john.dh.assistant.entity.SearchResult;
import cn.john.dh.assistant.prompt.service.AgentPromptService;
import cn.john.dh.assistant.utils.ThinkTagParser;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @Author John
 * @Date 2026-07-21 11:43
 */
@Slf4j
public class WebSearchReactAgent extends BaseAgent {

    // Jackson JSON映射器，用于解析搜索结果JSON，线程安全可复用
    private static final ObjectMapper MAPPER = new ObjectMapper();


    /**
     * 聊天客户端实例，在构造时初始化，后续所有轮次复用
     */
    private ChatClient chatClient;

    /**
     * 工具回调数组，在构造时初始化，后续所有轮次复用
     * 包含Tavily搜索等工具
     */
    private final ToolCallback[] tools;

    /**
     * 自定义系统提示
     */
    private final String systemPrompt;

    /**
     * 最大轮数
     */
    private int maxRounds;

    /**
     * Advisor列表，如聊天记忆Advisor等
     */
    private final List<Advisor> advisors;

    /**
     * 最大反思轮数
     */
    private final int maxReflectionRounds;


    /**
     * 私有构造方法，通过Builder模式创建实例。
     * 初始化所有字段并构建ChatClient。
     *
     * @param builder Builder实例，包含所有配置参数
     */
    private WebSearchReactAgent(Builder builder) { // 私有构造方法，通过Builder创建
        super(builder.name != null ? builder.name : "WebSearchAgent", builder.chatModel, "websearch"); // 调用父类构造，设置Agent名称、聊天模型和类型标识
        this.tools = builder.tools; // 设置可用工具数组
        this.systemPrompt = builder.systemPrompt; // 设置自定义系统提示词
        this.maxRounds = builder.maxRounds; // 设置最大推理轮次
        this.advisors = builder.advisors; // 设置Advisor列表
        this.chatMemory = builder.chatMemory; // 设置聊天记忆（字段继承自BaseAgent）
        this.chatConversationService = builder.conversationService; // 设置会话服务（字段继承自BaseAgent）
        this.chatMessageService = builder.chatMessageService; // 设置消息服务（字段继承自BaseAgent）
        this.agentPromptService = builder.agentPromptService;
        this.taskManager = builder.taskManager; // 设置任务管理器（字段继承自BaseAgent）
        this.maxReflectionRounds = builder.maxReflectionRounds;
        this.usedTools = new HashSet<>(); // 初始化已使用工具记录集合

        initChatClient(); // 初始化ChatClient实例

        if (this.chatClient == null) { // 验证ChatClient是否初始化成功
            throw new IllegalStateException("ChatClient 初始化失败！"); // 初始化失败时抛出异常
        }
    }

    /**
     * 初始化ChatClient实例。
     * 创建ChatClient实例并设置默认工具调用选项和工具回调。
     */
    private void initChatClient() {
        try {
            // 创建ToolCallingChatOptions实例
            ToolCallingChatOptions toolCallingChatOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(tools) // 设置工具回调数组
                    .internalToolExecutionEnabled(false)// 禁用内部工具执行
                    .build();
            // 创建ChatClient实例
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            if (advisors != null) {
                builder.defaultAdvisors(advisors);
            }
            // 设置默认工具调用选项和工具回调
            this.chatClient = builder.defaultOptions(toolCallingChatOptions)
                    .defaultToolCallbacks(tools)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("ChatClient 初始化失败：" + e.getMessage(), e); // 初始化异常时包装并抛出
        }
    }


    /**
     * 执行Agent，返回Flux<String>流式响应。
     * @param conversationId 会话ID
     * @param question       用户问题
     * @return
     */
    @Override
    public Flux<String> execute(String conversationId, String question) {
        return chat(conversationId, question);
    }


    /**
     * 流式输出的内部核心实现。
     * 负责构建消息列表、加载历史记忆、保存问题、调度推理轮次，并处理流的完成和错误。
     *
     * @param conversationId 会话ID，为null时不使用聊天记忆
     * @param question       用户问题
     * @return 流式响应Flux
     */
    public Flux<String> chat(String conversationId, String question) {
        // 解析会话ID，为空时创建新会话
        final String convId = resolveConversationId(conversationId, question);
        // 检查是否已有任务在执行，避免同一会话并发执行多个任务
        Flux<String> checkResult = checkRunningTask(convId); // 调用BaseAgent方法检查运行中任务
        if (checkResult != null) { // 如果有正在运行的任务
            return checkResult; // 直接返回错误Flux，拒绝重复执行
        }
        // 创建单播Sink并启用背压缓冲
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        // 注册任务到管理器，支持通过conversationId取消任务，调用BaseAgent方法注册任务
        AgentTaskManager.TaskInfo taskInfo = registerTask(convId, sink);
        // 注册失败且有会话ID时
        if (taskInfo == null && convId != null && taskManager != null) {
            // 返回错误流
            return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
        }
        // 构建初始消息列表（System Prompt + 历史记忆 + 当前问题），并保存用户问题
        List<Message> messages = buildInitialMessages(convId, question);
        //设置当前会话问题
        currentQuestion = question;
        // 创建本次流式调用的上下文状态
        ChatStreamContext ctx = new ChatStreamContext();
        //开始调度第一轮
        scheduleRound(messages, sink, ctx, convId);
        // 组装并返回响应流
        return assembleResponseFlux(sink, ctx, convId);
    }

    /**
     * 解析会话ID。
     * 如果传入的会话ID为空，则创建新会话并返回新会话ID；否则原样返回。
     *
     * @param conversationId 会话ID
     * @param question       用户问题
     * @return 有效的会话ID
     */
    private String resolveConversationId(String conversationId, String question) {
        if (!StringUtils.hasText(conversationId)) {
            return createConversation(StpUtil.getLoginIdAsString(), question);
        }
        return conversationId;
    }

    /**
     * 构建初始消息列表。
     * 依次加载System Prompt、历史记忆，保存用户问题并追加到消息列表。
     *
     * @param conversationId 会话ID
     * @param question       用户问题
     * @return 线程安全的消息列表
     */
    private List<Message> buildInitialMessages(String conversationId, String question) {
        //创建消息列表，线程安全
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        // ===== 加载 System Prompt（始终放在消息列表最开始）=====
        messages.add(new SystemMessage(agentPromptService.getPromptContentAndBasePrompt(AgentType.WEB_SEARCH, PromptKey.SYSTEM_PROMPT))); // 添加网络搜索Agent的基础系统提示词
        if (systemPrompt != null && !systemPrompt.isBlank()) { // 如果有自定义系统提示词
            messages.add(new SystemMessage(systemPrompt)); // 追加自定义系统提示词
        }
        //加载历史记忆
        loadChatHistory(messages, conversationId, 10);
        //保存问题
        chatMessageService.saveMessage(conversationId, ChatMessageType.USER, question);
        //拼接当前会话问题
        messages.add(new UserMessage("<question>" + question + "</question>"));
        return messages;
    }

    /**
     * 组装响应流。
     * 为Sink的Flux绑定响应块累积、取消清理和最终落库的回调逻辑。
     *
     * @param sink           响应流信号发射器
     * @param ctx            流式会话上下文
     * @param conversationId 会话ID
     * @return 组装完成的响应流
     */
    private Flux<String> assembleResponseFlux(Sinks.Many<String> sink, ChatStreamContext ctx, String conversationId) {
        return sink.asFlux()
                // 处理每个响应块
                .doOnNext(chunk -> accumulateChunk(chunk, ctx))
                // 流被取消时清理任务
                .doOnCancel(() -> handleStreamCancel(conversationId, ctx))
                // 流结束时落库并清理任务
                .doFinally(finalStatus -> finalizeStream(conversationId, ctx));
    }

    /**
     * 累积响应块内容。
     * 解析响应块JSON，将文本内容和思考内容分别追加到对应缓冲区。
     *
     * @param chunk 响应块
     * @param ctx   流式会话上下文
     */
    private void accumulateChunk(String chunk, ChatStreamContext ctx) {
        try {
            JSONObject json = JSON.parseObject(chunk); // 尝试将响应块解析为JSON
            String type = json.getString("type"); // 获取响应类型
            if ("text".equals(type)) { // 如果是文本类型
                ctx.finalAnswerBuffer.append(json.getString("content")); // 将内容追加到最终答案缓冲区
            } else if ("thinking".equals(type)) { // 如果是思考类型
                ctx.thinkingBuffer.append(json.getString("content")); // 将内容追加到思考缓冲区
            }
        } catch (Exception e) {
            // 非JSON格式的内容，直接追加到最终答案
            ctx.finalAnswerBuffer.append(chunk); // 容错处理
        }
    }

    /**
     * 流取消时的清理逻辑。
     * 标记已发送最终结果并停止任务。
     *
     * @param conversationId 会话ID
     * @param ctx            流式会话上下文
     */
    private void handleStreamCancel(String conversationId, ChatStreamContext ctx) {
        // 标记已发送最终结果，防止后续操作继续执行
        ctx.hasSentFinalResult.set(true);
        // 如果任务管理器存在
        if (taskManager != null) {
            // 停止并清理任务
            taskManager.stopTask(conversationId);
        }
    }

    /**
     * 流结束时的收尾逻辑。
     * 记录日志、保存助手消息并清理任务。
     *
     * @param conversationId 会话ID
     * @param ctx            流式会话上下文
     */
    private void finalizeStream(String conversationId, ChatStreamContext ctx) {
        // 记录最终答案日志
        log.info("最终答案: {}", ctx.finalAnswerBuffer);
        // 记录思考过程日志
        log.info("思考过程: {}", ctx.thinkingBuffer);
        //打印推荐问题
        log.info("推荐问题：{}",JSONObject.toJSONString(ctx.agentState.getSearchResults()));
        // 构建 metadata，将思考过程、参考来源、推荐问题序列化到JSON
        JSONObject metadata = new JSONObject();
        if (ctx.thinkingBuffer.length() > 0) {
            metadata.put("thinking", ctx.thinkingBuffer.toString());
        }
        if (!ctx.agentState.getSearchResults().isEmpty()) {
            metadata.put("references", ctx.agentState.getSearchResults());
        }
        if (currentRecommendations != null) {
            metadata.put("recommend", currentRecommendations);
        }
        String metadataStr = metadata.isEmpty() ? null : metadata.toJSONString();
        //保存Assistant Message（含思考过程、参考来源、推荐问题）
        chatMessageService.saveMessage(conversationId, ChatMessageType.ASSISTANT, ctx.finalAnswerBuffer.toString(), metadataStr);
        // 流结束时从任务管理器中移除任务
        if (taskManager != null) { // 如果任务管理器存在
            taskManager.stopTask(conversationId); // 停止并移除任务跟踪
        }
    }


    /**
     * 调度执行一轮推理。
     * 使用ChatClient发起流式请求，处理响应块，并在轮次结束时决定下一步操作。
     *
     * @param messages       消息列表
     * @param sink           响应流信号发射器
     * @param ctx            流式会话上下文
     * @param conversationId 会话ID
     */
    private void scheduleRound(List<Message> messages, Sinks.Many<String> sink, ChatStreamContext ctx, String conversationId) {

        // 轮次计数器+1
        ctx.roundCounter.incrementAndGet();
        // 创建本轮的状态跟踪对象
        RoundState state = new RoundState();
        //调用大模型处理响应数据，disposable是一个遥控器，可以关闭流式输出，用于用户停止输出，防止大模型继续输出。
        Disposable disposable = chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                //处理单个响应快
                .doOnNext(chunk -> processChunk(chunk, sink, state))
                .doOnComplete(() -> finishRound(messages, sink, state, ctx, conversationId))
                .doOnError(error -> {
                    if (!ctx.hasSentFinalResult.get()) { // 如果尚未发送最终结果
                        ctx.hasSentFinalResult.set(true); // 标记为已发送
                        sink.tryEmitError(error); // 将错误信号发射到Sink
                    }
                })
                .subscribe();
        // 保存Disposable引用到任务管理器，支持外部取消
        if (conversationId != null && taskManager != null) { // 如果有会话ID和任务管理器
            taskManager.setDisposable(conversationId, disposable); // 关联Disposable到任务，支持取消操作
        }
    }

    /**
     * 处理单个LLM流式响应块。
     * 如果响应块包含工具调用，则切换到TOOL_CALL模式并合并工具调用参数。
     * 否则使用ThinkTagParser解析think标签，区分思考内容和文本内容。
     *
     * @param chunk LLM响应块
     * @param sink  响应流信号发射器
     * @param state 当前轮次状态
     */
    private void processChunk(ChatResponse chunk, Sinks.Many<String> sink, RoundState state) {
        // 处理响应块，空响应检查、确保响应块、结果和输出都不为空
        if (chunk == null || chunk.getResult() == null ||
                chunk.getResult().getOutput() == null) {
            // 空响应直接跳过
            return;
        }
        // 获取生成结果
        Generation gen = chunk.getResult();
        String text = gen.getOutput().getText(); // 获取输出文本内容
        List<AssistantMessage.ToolCall> tc = gen.getOutput().getToolCalls(); // 获取工具调用列表
        // // 如果响应块中包含工具调用，一旦发现tool_call，立即进入TOOL_CALL模式
        if (tc != null && !tc.isEmpty()) {
            // 将本轮模式切换为工具调用模式
            state.mode = RoundMode.TOOL_CALL;
            // 遍历每个工具调用
            for (AssistantMessage.ToolCall incoming : tc) {
                // 合并工具调用到轮次状态中（处理流式分片）
                mergeToolCall(state, incoming);
            }
            // 工具调用模式下不处理文本内容
            return;
        }
        // 没有tool_call时，如果有文本内容使用ThinkTagParser解析<think>标签
        if (text != null) {
            // 解析think标签，传入当前思考状态
            ThinkTagParser.ParseResult parseResult = ThinkTagParser.parse(text, state.inThink);
            // 更新思考状态（是否仍在think标签内）
            state.inThink = parseResult.inThink();
            // 遍历解析出的所有文本段
            for (ThinkTagParser.Segment segment : parseResult.segments()) {
                // 如果是思考内容（在think标签内）
                if (segment.thinking()) {
                    // 发送thinking类型的SSE响应
                    sink.tryEmitNext(createThinkingResponse(segment.content()));
                } else { // 如果是普通文本内容
                    // 发送text类型的SSE响应
                    sink.tryEmitNext(createTextResponse(segment.content()));
                    // 将文本追加到轮次文本缓冲区
                    state.textBuffer.append(segment.content());
                }
            }
        }

    }

    /**
     * 合并流式分片中的工具调用。
     * 由于LLM流式输出可能将一个完整的工具调用拆分成多个chunk，
     * 需要根据工具调用ID进行匹配合并，拼接完整的参数JSON。
     *
     * @param state    当前轮次状态
     * @param incoming 新到达的工具调用分片
     */
    private void mergeToolCall(RoundState state, AssistantMessage.ToolCall incoming) {
        // 遍历已有的工具调用列表
        for (int i = 0; i < state.toolCalls.size(); i++) {
            AssistantMessage.ToolCall toolCall = state.toolCalls.get(i);
            if (toolCall.id().equals(incoming.id())) {
                // 将已有参数转为字符串（null安全）, 拼接新到达的参数片段
                String mergedArgs = Objects.toString(toolCall.arguments(), "")
                        + Objects.toString(incoming.arguments(), "");
                // 用合并后的工具调用替换原有的，创建新的ToolCall对象
                state.toolCalls.set(i,
                        new AssistantMessage.ToolCall(toolCall.id(), "function", toolCall.name(), mergedArgs)
                );
                // 合并完成，直接返回
                return;
            }
        }
        // 如果是全新的工具调用（ID不匹配任何已有的），直接添加到列表
        state.toolCalls.add(incoming); // 将新工具调用追加到列表末尾
    }

    /**
     * 轮次结束时的处理逻辑。
     * 如果本轮没有工具调用，说明LLM已给出最终答案，输出参考链接和推荐问题后完成流。
     * 如果有工具调用，执行工具并将结果添加到消息列表，然后调度下一轮推理。
     * 如果已达到最大轮次限制，强制要求LLM给出最终答案。
     *
     * @param messages       消息列表
     * @param sink           响应流信号发射器
     * @param state          当前轮次状态
     * @param ctx            流式会话上下文
     * @param conversationId 会话ID
     */
    private void finishRound(List<Message> messages, Sinks.Many<String> sink, RoundState state,
                             ChatStreamContext ctx, String conversationId) {
        if (state.getMode() != RoundMode.TOOL_CALL) {
            String finalText = state.textBuffer.toString(); // 获取本轮输出的完整文本
            if (finalText != null && !finalText.isEmpty()) {
                // 输出参考链接（如果有搜索结果）
                if (!ctx.agentState.getSearchResults().isEmpty()) { // 如果Agent状态中有搜索结果
                    String reference = JSON.toJSONString(ctx.agentState.getSearchResults()); // 将搜索结果序列化为JSON
                    String referenceJson = createReferenceResponse(reference); // 生成参考链接类型的SSE响应
                    sink.tryEmitNext(referenceJson); // 发送参考链接响应
                }
                // 输出推荐问题（如果启用了推荐功能）
                if (enableRecommendations) { // 如果推荐问题功能已启用
                    String recommendations = generateRecommendations(currentQuestion, finalText, messages); // 调用BaseAgent方法生成推荐问题
                    if (recommendations != null) { // 如果推荐问题生成成功
                        currentRecommendations = recommendations; // 保存到BaseAgent字段，供数据库存储使用
                        String recommendJson = createRecommendResponse(recommendations); // 生成推荐问题类型的SSE响应
                        sink.tryEmitNext(recommendJson); // 发送推荐问题响应
                    }
                }

                sink.tryEmitNext(createCompleteResponse()); // 发送流式响应结束标记
                sink.tryEmitComplete(); // 完成Sinks流，触发下游的doFinally
                ctx.hasSentFinalResult.set(true); // 标记已发送最终结果
                return; // 最终答案已输出，结束本轮
            }
        }
        // 有TOOL_CALL：将助手消息（包含工具调用）添加到消息列表
        AssistantMessage assistantMsg = AssistantMessage.builder().toolCalls(state.toolCalls).build(); // 构建包含工具调用的助手消息
        messages.add(assistantMsg); // 将助手消息添加到消息历史

        // 检查是否已达到最大推理轮次限制
        if (maxRounds > 0 && ctx.roundCounter.get() >= maxRounds) {
            // 轮次已用完，达到最大推理次数
            forceFinalStream(messages, sink, ctx, state, conversationId); // 强制输出最终答案
            return; // 不再调度新的推理轮次
        }

        // 并行执行工具调用，完成后调度下一轮推理
        executeToolCalls(sink, state.toolCalls, messages, ctx, state, () -> { // 执行工具调用，传入完成回调
            if (!ctx.hasSentFinalResult.get()) { // 如果尚未发送最终结果
                scheduleRound(messages, sink, ctx, conversationId);
            }
        });

    }


    /**
     * 达到最大推理轮次时，强制输出最终答案。
     * 重建消息列表，添加系统提示词和限制提示，要求LLM不再调用工具并直接给出答案。
     *
     * @param messages       原始消息列表（会被替换）
     * @param sink           响应流信号发射器
     * @param ctx            流式会话上下文
     * @param state          当前轮次状态
     * @param conversationId 会话ID
     */
    private void forceFinalStream(List<Message> messages, Sinks.Many<String> sink, ChatStreamContext ctx,
                                  RoundState state, String conversationId) {
        List<Message> newMessages = new ArrayList<>();
        // 添加系统提示词
        newMessages.add(new SystemMessage(agentPromptService.getPromptContent(AgentType.WEB_SEARCH, PromptKey.RECOMMEND_PROMPT)));
        for (Message message : messages) {
            // 跳过系统消息
            if (!(message instanceof SystemMessage)) {
                newMessages.add(message);
            }
        }
        // 添加限制提示，告知LLM已达到最大推理轮次，强制要求LLM不再调用工具，直接输出最终答案
        newMessages.add(new UserMessage("""
                你已达到最大推理轮次限制。
                请基于当前已有的上下文信息，
                直接给出最终答案。
                禁止再调用任何工具。
                如果信息不完整，请合理总结和说明。
                """));
        // 替换原消息列表内容
        messages.clear(); // 清空原消息列表
        messages.addAll(newMessages); // 用新消息列表替换
        // 收集强制输出的最终文本
        // 创建最终文本缓冲区
        StringBuilder finalTextBuffer = new StringBuilder();

        Disposable disposable = chatClient.prompt()
                .messages(newMessages)
                .stream()
                .chatResponse()
                //绑定到线程池
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                        return;
                    }
                    String text = chunk.getResult().getOutput().getText();
                    // 文本非空且未发送最终结果
                    if (text != null && !ctx.hasSentFinalResult.get()) {
                        // 解析think标签
                        ThinkTagParser.ParseResult parseResult = ThinkTagParser.parse(text, state.inThink);
                        // 更新思考状态
                        state.inThink = parseResult.inThink();
                        // 遍历解析出的文本段
                        for (ThinkTagParser.Segment segment : parseResult.segments()) {
                            if (segment.thinking()) { // 如果是思考内容
                                // 发送thinking响应
                                sink.tryEmitNext(createThinkingResponse(segment.content()));
                            } else { // 如果是普通文本
                                sink.tryEmitNext(createTextResponse(segment.content()));
                                // 发送text响应;
                                // 追加到最终文本缓冲区
                                finalTextBuffer.append(segment.content());
                            }
                        }
                    }
                }).doOnComplete(() -> {
                    String finalText = finalTextBuffer.toString(); // 获取最终文本
                    // 输出参考链接（如果有搜索结果）
                    if (!ctx.agentState.getSearchResults().isEmpty()) { // 如果有搜索结果
                        String reference = JSON.toJSONString(ctx.agentState.getSearchResults()); // 序列化搜索结果
                        String referenceJson = createReferenceResponse(reference); // 生成参考链接响应
                        sink.tryEmitNext(referenceJson); // 发送参考链接
                    }
                    // 输出推荐问题（如果启用了推荐功能）
                    if (enableRecommendations) { // 如果推荐功能已启用
                        // 生成推荐问题
                        String recommendations = generateRecommendations(currentQuestion, finalText, messages);
                        // 如果生成成功
                        if (recommendations != null) {
                            // 保存推荐问题
                            currentRecommendations = recommendations;
                            // 生成推荐响应
                            String recommendJson = createRecommendResponse(recommendations);
                            // 发送推荐问题
                            sink.tryEmitNext(recommendJson);
                        }
                    }
                    // 标记已发送最终结果
                    ctx.hasSentFinalResult.set(true);
                    sink.tryEmitNext(createCompleteResponse()); // 发送流式响应结束标记
                    // 完成Sinks流
                    sink.tryEmitComplete();
                })
                .subscribe();
    }

    /**
     * 并行执行工具调用。
     * 使用boundedElastic调度器并行执行多个工具调用，通过AtomicInteger和ConcurrentHashMap
     * 保证所有工具调用完成后按原始顺序组装结果。
     *
     * @param sink       响应流信号发射器
     * @param toolCalls  工具调用列表
     * @param messages   消息列表
     * @param ctx        流式会话上下文
     * @param state      当前轮次状态
     * @param onComplete 所有工具调用完成后的回调
     */
    private void executeToolCalls(Sinks.Many<String> sink, List<AssistantMessage.ToolCall> toolCalls,
                                  List<Message> messages, ChatStreamContext ctx,
                                  RoundState state, Runnable onComplete) {
        // 原子计数器，跟踪已完成的工具调用数量
        AtomicInteger completedCount = new AtomicInteger(0);
        // 工具调用总数
        int totalToolCalls = toolCalls.size();
        // 使用ConcurrentHashMap保证并发写入安全，按toolCall ID存储结果，线程安全的工具响应映射
        Map<String, ToolResponseMessage.ToolResponse> responseMap = new ConcurrentHashMap<>();
        //遍历所有的工具
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            //提交工具去弹性线程池异步执行
            Schedulers.boundedElastic().schedule(() -> {
                if (ctx.hasSentFinalResult.get()) {
                    // 已发送最终结果，完成工具调用
                    completeToolCall(completedCount, totalToolCalls, responseMap, toolCalls, messages, onComplete);
                    return;
                }
                // 获取工具名称
                String toolName = toolCall.name();
                // 获取工具参数JSON
                String argsJson = toolCall.arguments();
                ToolCallback tool = findTool(toolName);
                if (tool == null) { // 如果工具未找到
                    // 将错误信息作为工具响应放入responseMap
                    responseMap.put(toolCall.id(), new ToolResponseMessage.ToolResponse( // 记录工具未找到的错误
                            toolCall.id(), toolName, "{ \"error\": \"工具未找到：" + toolName + "\" }"));
                    completeToolCall(completedCount, totalToolCalls, responseMap, toolCalls, messages, onComplete); // 标记完成
                    return; // 跳过执行
                }
                // 如果是搜索类工具，发送thinking提示
                if (toolName.contains("search")) { // 工具名称包含"search"
                    JSONObject args = JSON.parseObject(argsJson); // 解析工具参数
                    String query = (String) args.get("query"); // 获取搜索查询词
                    // 发送搜索提示到thinking流
                    String queryThink = (query != null && !query.isBlank()) // 根据查询词是否为空
                            ? "正在搜索信息: " + query + "\n" // 有查询词时显示具体搜索内容
                            : "正在搜索相关信息\n"; // 无查询词时显示通用提示
                    sink.tryEmitNext(createThinkingResponse(queryThink)); // 发送thinking类型的SSE响应
                }
                try {
                    Object result = tool.call(argsJson); // 执行工具调用，传入参数JSON
                    String resultStr = result.toString(); // 将结果转为字符串

                    // 记录使用的工具名称
                    recordUsedTool(toolName); // 调用BaseAgent方法记录工具使用
                    // 如果是tavily搜索工具，解析搜索结果用于生成参考链接
                    if (toolName.contains("tavily")) { // 工具名称包含"tavily"
                        parseSearchResult(resultStr, ctx.agentState); // 解析搜索结果并添加到Agent状态
                    }
                    // 将工具执行结果放入responseMap
                    responseMap.put(toolCall.id(), new ToolResponseMessage.ToolResponse( // 存储工具执行结果
                            toolCall.id(), toolName, resultStr));
                } catch (Exception ex) { // 工具执行异常时
                    // 将错误信息作为工具响应放入responseMap
                    responseMap.put(toolCall.id(), new ToolResponseMessage.ToolResponse( // 记录工具执行失败信息
                            toolCall.id(), toolName, "{ \"error\": \"工具执行失败：" + ex.getMessage() + "\" }"));
                } finally {
                    completeToolCall(completedCount, totalToolCalls, responseMap, toolCalls, messages, onComplete); // 无论成功失败都标记完成
                }
            });
        }
    }

    /**
     * 根据名称查找工具回调。
     *
     * @param name 工具名称
     * @return 匹配的ToolCallback，未找到返回null
     */
    private ToolCallback findTool(String name) { // 根据名称查找工具
        for (ToolCallback callback : tools) { // 遍历工具数组
            if (callback.getToolDefinition().name().equals(name)) { // 工具定义名称匹配时
                return callback; // 返回匹配的ToolCallback
            }
        }
        return null; // 未找到匹配的工具
    }

    private void completeToolCall(AtomicInteger completedCount, int total, // 工具调用完成处理
                                  Map<String, ToolResponseMessage.ToolResponse> responseMap,
                                  List<AssistantMessage.ToolCall> originalToolCalls,
                                  List<Message> messages,
                                  Runnable onComplete) {
        // 原子递增完成计数
        int current = completedCount.incrementAndGet();
        if (current >= total) { // 所有工具调用都已完成
            // 按原始toolCalls的顺序重组工具响应结果
            List<ToolResponseMessage.ToolResponse> sortedResponses = new ArrayList<>(); // 创建有序响应列表
            for (AssistantMessage.ToolCall tc : originalToolCalls) { // 按原始顺序遍历
                ToolResponseMessage.ToolResponse response = responseMap.get(tc.id()); // 从映射中获取响应
                if (response != null) { // 如果响应存在
                    sortedResponses.add(response); // 添加到有序列表
                } else { // 如果某个工具调用没有响应（异常情况）
                    // 添加错误响应作为兜底
                    sortedResponses.add(new ToolResponseMessage.ToolResponse( // 生成工具响应丢失的错误信息
                            tc.id(), tc.name(), "{ \"error\": \"工具响应丢失\" }"));
                }
            }
            // 一次性添加所有工具响应到消息列表（按原始顺序）
            messages.add(ToolResponseMessage.builder() // 构建工具响应消息
                    .responses(sortedResponses) // 设置有序的工具响应列表
                    .build()); // 完成构建
            onComplete.run(); // 触发完成回调，通常是调度下一轮推理
        }
    }

    /**
     * 解析Tavily搜索结果JSON。
     * 从工具返回的嵌套JSON中提取搜索结果（url、title、content），添加到Agent状态中。
     * JSON结构：[{ text: { results: [{ url, title, content }, ...] } }]
     *
     * @param resultJson 工具返回的JSON字符串
     * @param state      Agent状态对象
     */
    private void parseSearchResult(String resultJson, AgentState state) { // 解析Tavily搜索结果
        try {
            JsonNode root = MAPPER.readTree(resultJson); // 使用Jackson解析JSON字符串

            if (!root.isArray() || root.isEmpty()) { // 如果根节点不是数组或为空
                return; // 无有效数据，直接返回
            }

            JsonNode first = root.get(0); // 获取数组第一个元素
            JsonNode textNode = first.get("text"); // 获取text字段节点

            if (textNode == null || textNode.isNull()) { // 如果text字段不存在或为null
                return; // 无有效text数据
            }

            JsonNode textJson; // 声明textJson节点
            if (textNode.isTextual()) { // 如果text是字符串类型（嵌套JSON字符串）
                textJson = MAPPER.readTree(textNode.asText()); // 二次解析字符串为JSON
            } else { // 如果text已经是JSON对象
                textJson = textNode; // 直接使用
            }

            JsonNode results = textJson.get("results"); // 获取results数组节点
            if (results == null || !results.isArray()) { // 如果results不存在或不是数组
                return; // 无有效搜索结果
            }

            for (JsonNode item : results) { // 遍历每条搜索结果
                String url = getSafe(item, "url"); // 安全获取url字段
                String title = getSafe(item, "title"); // 安全获取title字段
                String content = getSafe(item, "content"); // 安全获取content字段

                if (url != null && !url.isBlank()) { // 只添加有URL的结果
                    state.addSearchResult(new SearchResult(title, content, url)); // 创建SearchResult并添加到Agent状态
                }
            }

        } catch (Exception e) { // 解析异常时
            log.warn("解析 tavily 搜索结果失败: {}", e.getMessage()); // 记录警告日志，不影响主流程
        }
    }

    /**
     * 安全获取JsonNode中的字符串字段值。
     *
     * @param node  JSON节点
     * @param field 字段名
     * @return 字段值字符串，不存在或为null时返回null
     */
    private String getSafe(JsonNode node, String field) { // 安全获取JSON节点的字符串字段值
        JsonNode v = node.get(field); // 获取指定字段的节点
        return v == null || v.isNull() ? null : v.asText(); // 节点不存在或为null时返回null，否则返回文本值
    }

    /**
     * 流式会话上下文。
     * 封装单次流式调用过程中需要跨方法传递的状态对象，
     * 避免在方法签名中层层传递多个独立变量。
     */
    private static class ChatStreamContext {
        // 迭代轮次计数器
        final AtomicLong roundCounter = new AtomicLong(0);
        // 是否已发送最终结果的标记位
        final AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);
        // 收集最终答案（纯文本），用于存储到数据库的memory
        final StringBuilder finalAnswerBuffer = new StringBuilder();
        // 收集思考过程，用于存储到数据库
        final StringBuilder thinkingBuffer = new StringBuilder();
        // 每次流式调用创建独立的Agent状态对象
        final AgentState agentState = new AgentState();
    }

    /**
     * WebSearchReactAgent的构建器。
     * 提供链式API设置所有配置参数，最终通过build()方法创建Agent实例。
     */
    public static class Builder { // 构建器内部类

        private String name; // Agent名称
        private ChatModel chatModel; // AI聊天模型
        private ToolCallback[] tools; // 工具回调数组
        private String systemPrompt = ""; // 自定义系统提示词，默认为空字符串
        private int maxReflectionRounds; // 最大反思轮次
        private int maxRounds; // 最大推理轮次
        private List<Advisor> advisors; // Advisor列表
        private ChatMemory chatMemory; // 聊天记忆
        private ChatConversationService conversationService; // 会话服务
        private ChatMessageService chatMessageService; // 聊天消息服务
        private AgentPromptService agentPromptService; // 系统提示器服务
        private AgentTaskManager taskManager; // 任务管理器

        /**
         * 设置聊天记忆。
         */
        public Builder chatMemory(ChatMemory chatMemory) { // 设置聊天记忆
            this.chatMemory = chatMemory; // 赋值聊天记忆
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置会话服务。
         */
        public Builder conversationService(ChatConversationService conversationService) { // 设置会话服务
            this.conversationService = conversationService; // 赋值会话服务
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置聊天消息服务。
         */
        public Builder chatMessageService(ChatMessageService chatMessageService) { // 设置聊天消息服务
            this.chatMessageService = chatMessageService; // 赋值聊天消息服务
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置系统提示器服务。
         */
        public Builder agentPromptService(AgentPromptService agentPromptService) { // 设置系统提示器服务
            this.agentPromptService = agentPromptService; // 赋值系统提示器服务
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置任务管理器。
         */
        public Builder taskManager(AgentTaskManager taskManager) { // 设置任务管理器
            this.taskManager = taskManager; // 赋值任务管理器
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置Agent名称。
         */
        public Builder name(String name) { // 设置Agent名称
            this.name = name; // 赋值名称
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置聊天模型。
         */
        public Builder chatModel(ChatModel chatModel) { // 设置聊天模型
            this.chatModel = chatModel; // 赋值聊天模型
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置工具回调（可变参数版本）。
         */
        public Builder tools(ToolCallback... tools) { // 设置工具（可变参数版本）
            this.tools = tools; // 直接赋值ToolCallback数组
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置工具回调（List版本）。
         */
        public Builder tools(List<ToolCallback> tools) { // 设置工具（List版本）
            this.tools = tools != null ? tools.toArray(new ToolCallback[0]) : new ToolCallback[0]; // 将List转换为数组
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置Advisor列表。
         */
        public Builder advisors(List<Advisor> advisors) { // 设置Advisor列表
            this.advisors = advisors; // 赋值Advisor列表
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置Advisor（可变参数版本）。
         */
        public Builder advisors(Advisor... advisors) { // 设置Advisor（可变参数版本）
            this.advisors = Arrays.asList(advisors); // 将可变参数转换为List
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置自定义系统提示词。
         */
        public Builder systemPrompt(String systemPrompt) { // 设置自定义系统提示词
            this.systemPrompt = systemPrompt; // 赋值系统提示词
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置最大反思轮次。
         */
        public Builder maxReflectionRounds(int maxReflectionRounds) { // 设置最大反思轮次
            this.maxReflectionRounds = maxReflectionRounds; // 赋值反思轮次
            return this; // 返回Builder支持链式调用
        }

        /**
         * 设置最大推理轮次。
         */
        public Builder maxRounds(int maxRounds) { // 设置最大推理轮次
            this.maxRounds = maxRounds; // 赋值推理轮次
            return this; // 返回Builder支持链式调用
        }

        /**
         * 构建WebSearchReactAgent实例。
         * 校验必填参数并创建Agent实例。
         *
         * @return 配置完成的WebSearchReactAgent实例
         * @throws IllegalArgumentException 如果chatModel为null
         */
        public WebSearchReactAgent build() { // 构建Agent实例
            if (chatModel == null) { // 校验聊天模型必填
                throw new IllegalArgumentException("chatModel 不能为空！"); // 缺少必填参数时抛出异常
            }
            return new WebSearchReactAgent(this); // 使用当前Builder配置创建Agent实例
        }
    }

    /**
     * 获取Builder实例的静态工厂方法。
     *
     * @return 新的Builder实例
     */
    public static Builder builder() { // 获取Builder实例
        return new Builder(); // 创建并返回新的Builder
    }

}
