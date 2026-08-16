package cn.john.dh.assistant.agent.deepresearch;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.agent.AgentTaskManager;
import cn.john.dh.assistant.agent.BaseAgent;
import cn.john.dh.assistant.agent.deepresearch.context.PlanExecuteContext;
import cn.john.dh.assistant.agent.websearch.WebSearchReactAgent;
import cn.john.dh.assistant.chat.domain.entity.ChatConversation;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.chat.service.ChatTokenLimitService;
import cn.john.dh.assistant.chat.util.ChatTokenUsageUtil;
import cn.john.dh.assistant.common.AgentResponse;
import cn.john.dh.assistant.constant.AgentType;
import cn.john.dh.assistant.constant.ChatMessageType;
import cn.john.dh.assistant.constant.PromptKey;
import cn.john.dh.assistant.entity.CritiqueResult;
import cn.john.dh.assistant.entity.PlanTask;
import cn.john.dh.assistant.entity.SearchResult;
import cn.john.dh.assistant.entity.TaskResult;
import cn.john.dh.assistant.prompt.PlanExecutePrompts;
import cn.john.dh.assistant.prompt.service.AgentPromptService;
import cn.john.dh.assistant.utils.ThinkTagParser;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * @Author John
 * @Date 2026-07-23 21:55
 */
@Slf4j
public class PlanExecuteAgent extends BaseAgent {

    private ChatClient chatClient;
    // 可用工具回调数组
    private final ToolCallback[] tools;
    // 最大计划-执行迭代轮数
    private final int maxRounds;
    // 上下文压缩的字符数阈值
    private final int contextCharLimit;
    // 工具并发调用信号量，限制同时执行的工具数量
    private final Semaphore toolSemaphore;
    // 工具执行失败时的最大重试次数
    private final int maxToolRetries;
    // 组合Disposable容器，用于统一管理所有异步任务的取消操作
    private Disposable.Composite compositeDisposable;
    // 所有搜索结果列表，用于最终报告引用和前端展示
    private List<SearchResult> allReferences;



    /**
     * 私有构造方法，通过Builder创建PlanExecuteAgent实例
     *
     * @param builder 构建器实例，包含所有配置参数
     */
    private PlanExecuteAgent(Builder builder) {
        // 调用父类构造，设置Agent名称、模型和类型
        super("PlanExecuteAgent", builder.chatModel, "plan-execute");
        // 基于ChatModel构建ChatClient客户端
        this.chatClient = ChatClient.builder(builder.chatModel).build();
        // 设置可用工具数组
        this.tools = builder.tools;
        // 设置最大迭代轮数
        this.maxRounds = builder.maxRounds;
        // 设置上下文压缩字符阈值
        this.contextCharLimit = builder.contextCharLimit;
        // 设置工具最大重试次数
        this.maxToolRetries = builder.maxToolRetries;
        // 设置会话服务（从BaseAgent继承的字段）
        this.chatConversationService = builder.chatConversationService;
        // 设置消息服务（从BaseAgent继承的字段）
        this.chatMessageService = builder.chatMessageService;
        this.agentPromptService = builder.agentPromptService;
        // 设置任务管理器（从BaseAgent继承的字段）
        this.taskManager = builder.taskManager;
        // 设置每日 Token 限制服务（从BaseAgent继承的字段）
        this.chatTokenLimitService = builder.chatTokenLimitService;
        // 初始化信号量，允许最多3个工具并发执行
        this.toolSemaphore = new Semaphore(3);
        // 初始化已使用工具名称集合（从BaseAgent继承的字段）
        this.usedTools = new HashSet<>();
    }

    @Override
    public Flux<String> execute(String conversationId, String question) {
        //解析会话ID，为空时创建对话
        final String convId = resolveConversationId(conversationId, question);
        //检查是否已有任务执行，避免同一会话并发执行多个任务
        Flux<String> checkResult = checkRunningTask(convId);
        // 如果有正在运行的任务
        if (checkResult != null) {
            // 直接返回错误Flux，拒绝重复执行
            return checkResult;
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
        try {
        // 清除之前记录的工具使用记录
        clearUsedTools();
        // 初始化参考来源列表（线程安全，任务并发执行时会并发写入）
        this.allReferences = Collections.synchronizedList(new ArrayList<>());
        //设置当前会话问题
        currentQuestion = question;
        // 创建新的组合Disposable容器
        compositeDisposable = Disposables.composite();
        // 初始化全局状态并将问题保存到数据库
        PlanExecuteContext ptx = getPlanExecuteContext(question, convId);

        //开始需求澄清->需求澄清之后启动研究主题生成->研究主题生成之后启动执行循环
        clarificationRequirements(sink, ptx,
                //研究主题生成
                () -> researchTopicGeneration(sink, ptx,
                        //主题生成后启动执行阶段
                        () -> executeLoopPhase(sink, ptx)));
        // 将Disposable关联到任务管理器以支持取消操作
        // 注意：必须使用解析后的 convId（conversationId 参数可能为 null，新会话时会导致 Disposable 关联失败、取消失效）
        registerTaskToManager(convId);
        return complete(sink, ptx);
        } catch (Exception e) {
            // 同步阶段异常：此时响应流尚未返回，doFinally不会触发，必须主动清理任务，避免会话被永久锁定
            if (taskManager != null) {
                taskManager.stopTask(convId);
            }
            log.error("PlanExecuteAgent 启动异常", e);
            // 返回错误流
            return Flux.error(e);
        }
    }

    /**
     * 完成流处理，对每个chunk进行解析并追加到缓冲区，处理取消操作并保存最终结果
     * @param sink
     * @param context
     * @return
     */
    private Flux<String> complete(Sinks.Many<String> sink, PlanExecuteContext context) {
        return sink.asFlux()
                .doOnNext(chunk ->{
                    //将返回信息解析并追加到缓冲区，用于保存最终答案和思考过程
                    parseAndAppendToBuffers(chunk, context);
                })
                .doOnCancel(() -> handleCancel(sink, context))
                .doFinally(signal ->{
                    //保存最终结果
                    handleFinally(signal,context);
                });
    }

    /**
     * 处理流结束操作
     * 根据信号类型记录日志，并保存最终结果
     * @param signalType
     * @param context
     */
    private void handleFinally(SignalType signalType,PlanExecuteContext context) {
        // 记录流结束日志
        log.info("流结束，类型: {}, 最终答案长度: {}, 思考过程长度: {}",
                signalType, context.getFinalAnswerBuffer().length(), context.getThinkingBuffer().length());
        // 构建metadata，仅保存参考来源（深度思考Agent不保存推荐问题）
        String metadataStr = null;
        if (allReferences != null && !allReferences.isEmpty()) {
            JSONObject metadata = new JSONObject();
            metadata.put("references", allReferences);
            metadataStr = metadata.toJSONString();
        }
        // 思考过程为空时存null，避免写入空字符串
        String thinkingContent = context.getThinkingBuffer().length() > 0 ? context.getThinkingBuffer().toString() : null;
        // 保存Assistant消息（最终答案 + 思考过程 + 参考来源）
        chatMessageService.saveMessage(context.getConversationId(), ChatMessageType.ASSISTANT,
                context.getFinalAnswerBuffer().toString(), thinkingContent, metadataStr);
        // 扣减当日 Token 限额
        consumeTokenLimit(context);
        // 流结束时从任务管理器中移除任务
        if (taskManager != null) {
            taskManager.stopTask(context.getConversationId());
        }
    }

    /**
     * 根据会话ID查询用户并扣减当日 Token 限额。
     *
     * @param context 计划执行上下文
     */
    private void consumeTokenLimit(PlanExecuteContext context) {
        try {
            if (chatTokenLimitService == null || chatConversationService == null) {
                return;
            }
            String conversationId = context.getConversationId();
            ChatConversation conversation = chatConversationService.getByConversationId(conversationId);
            if (conversation == null || !StringUtils.hasText(conversation.getUserId())) {
                return;
            }
            long totalTokens = ChatTokenUsageUtil.getTotalTokens(context.promptTokens, context.generationTokens);
            if (totalTokens > 0) {
                chatTokenLimitService.consume(conversation.getUserId(), totalTokens);
                log.info("PlanExecuteAgent 会话 {} 本次消耗 token: {}, model: {}",
                        conversationId, totalTokens, getModelName());
            }
        } catch (Exception e) {
            log.warn("PlanExecuteAgent 扣减 token 限额失败: conversationId={}", context.getConversationId(), e);
        }
    }

    /**
     * 处理取消操作
     * 标记流程完成并停止任务管理器中的任务
     *
     * @param sink     响应流信号发射器
     * @param context  流程上下文
     */
    private void handleCancel(Sinks.Many<String> sink, PlanExecuteContext context) {
        // 标记流程为已完成
        context.getHasSentFinalResult().set(true);
        // 通知任务管理器停止当前任务
        taskManager.stopTask(context.getConversationId());
    }

    /**
     *
     * @param chunk
     * @param context
     */
    private void parseAndAppendToBuffers(String chunk,PlanExecuteContext context){
        try {
            // 尝试将消息块解析为JSON对象
            JSONObject json = JSON.parseObject(chunk);
            // 获取消息类型字段
            String type = json.getString("type");
            // 如果是文本类型
            if ("text".equals(type)) {
                // 将内容追加到最终回答缓冲区
                context.getFinalAnswerBuffer().append(json.getString("content"));
                // 如果是思考类型
            } else if ("thinking".equals(type)) {
                // 将内容追加到思考过程缓冲区
                context.getThinkingBuffer().append(json.getString("content"));
            }
            // 解析失败时
        } catch (Exception e) {
            // 将原始内容追加到最终回答缓冲区
            context.getFinalAnswerBuffer().append(chunk);
        }
    }

    /**
     * 注册任务到任务管理器，关联Disposable以支持取消
     *
     * @param conversationId 会话ID
     */
    private void registerTaskToManager(String conversationId) {
        // 会话ID和任务管理器都非空时
        if (conversationId != null && taskManager != null) {
            // 将组合Disposable关联到任务
            taskManager.setDisposable(conversationId, compositeDisposable);
        }
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
            conversationId = createConversation(StpUtil.getLoginIdAsString(), question);
        }
        //保存用户问题
        chatMessageService.saveMessage(conversationId, ChatMessageType.USER, question, null, null);
        return conversationId;
    }

    /**
     * 获取计划执行上下文，并且保存用户消息
     *
     * @param query
     * @param conversationId
     * @return
     */
    public PlanExecuteContext getPlanExecuteContext(String query, String conversationId) {
        List<Message> messages = new ArrayList<>();
        PlanExecuteContext context = new PlanExecuteContext(conversationId, query);
        loadChatHistory(messages, conversationId, 10);
        // 如果历史消息非空
        if (CollectionUtils.isNotEmpty(messages)) {
            // 将每条历史消息添加到状态中
            messages.forEach(context::add);
        }
        context.getMessages().add(new UserMessage(query));
        return context;
    }


    /**
     * 需求澄清,通过对用户问题以及指定prompt的对话，对用户的问题进行需求澄清
     * 作用：判断用户问题的信息是否足够开展研究
     *
     * @param sink
     * @param context
     * @param onComplete
     */
    private void clarificationRequirements(Sinks.Many<String> sink, PlanExecuteContext context,
                                           Runnable onComplete) {
        log.info("正在执行需求澄清阶段，问题：{}", context.getQuestion());
        //发送正在分析需求的text到前端
        emit(sink, context.getHasSentFinalResult(), "\n🔍 正在分析您的需求...\n", AgentResponse.TYPE_THINKING);
        //构建消息列表
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(agentPromptService.getPromptContent(AgentType.PLAN_EXECUTE, PromptKey.REQUIREMENT_CLARIFICATION)));
        //加入聊天记录
        messages.addAll(context.getMessages());
        //创建消息响应缓存区
        StringBuilder response = new StringBuilder();
        Disposable disposable = chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .doOnNext(chunk -> {
                    // 记录 token 使用量（流式 Usage 通常只在最后一个 chunk 出现，用 max 覆盖）
                    ChatTokenUsageUtil.recordUsage(chunk, context.promptTokens, context.generationTokens);
                    // 提取文本内容
                    String text = null;
                    if (chunk != null && chunk.getResult() != null
                            && chunk.getResult().getOutput() != null) {
                        text = chunk.getResult().getOutput().getText();
                    }
                    if (text == null) {
                        return;
                    }
                    // 解析每一段chunk，如果有<think>标签则截取放到parse的segment中
                    ThinkTagParser.ParseResult parse = ThinkTagParser.parse(text, context.getHasRequirementClarificationThink().get());
                    context.setHasRequirementClarificationThink(new AtomicBoolean(parse.inThink()));
                    for (ThinkTagParser.Segment segment : parse.segments()) {
                        //先把<think>标签的内容数据推给页面
                        emit(sink, context.getHasSentFinalResult(), segment.content(), AgentResponse.TYPE_THINKING);
                        //如果没有<think>标签的内容当做正文内容，先保存到response中。
                        if (!segment.thinking()) {
                            response.append(segment.content());
                        }

                    }
                }).doOnComplete(() -> {
                    // 需求澄清完成，调用回调方法
                    clarificationRequirementsComplete(response, sink, context, onComplete);
                }).doOnError(error ->
                        handleError("需求澄清异常", error, sink, context.getHasSentFinalResult())
                ).subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        // 将Disposable添加到组合容器以支持统一取消
        compositeDisposable.add(disposable);
    }

    /**
     * 需求澄清完成，根据响应内容判断是否需要更多信息
     *
     * @param response
     * @param sink
     * @param context
     * @param onComplete
     */
    private void clarificationRequirementsComplete(StringBuilder response, Sinks.Many<String> sink,
                                                   PlanExecuteContext context, Runnable onComplete) {
        // 获取LLM的完整响应文本
        String responseStr = response.toString();
        // 检查响应中是否包含"【需要补充信息】"标记
        boolean needsMoreInfo = responseStr.contains("【需要补充信息】");
        if (needsMoreInfo) {
            // 如果需要更多信息，则发送提示消息
            emit(sink, context.getHasSentFinalResult(), "\n❌ 需求分析未完成，请补充更多信息\n" +
                    responseStr.replace("【需要补充信息】", ""), AgentResponse.TYPE_TEXT);
            complete(sink, context.getHasSentFinalResult());
        } else {
            // 如果不需要更多信息，则发送完成消息
            emit(sink, context.getHasSentFinalResult(), "\n✅ 需求分析完成\n", AgentResponse.TYPE_THINKING);
            //进入回调，进入生成研究主题阶段
            onComplete.run();
        }
    }

    /**
     * 完成流
     * 使用CAS确保只完成一次
     *
     * @param sink     响应流信号发射器
     * @param finished 完成标记
     */
    private void complete(Sinks.Many<String> sink,
                          AtomicBoolean finished) {

        // CAS操作：仅当finished为false时设为true
        if (finished.compareAndSet(false, true)) {
            // 先发送流式响应结束标记，前端据此判断正常结束
            sink.tryEmitNext(createCompleteResponse());
            // 发送完成信号
            sink.tryEmitComplete();
        }
    }

    /**
     * 处理错误（通用错误处理方法）
     *
     * @param logMessage 日志消息
     * @param err        异常对象
     * @param sink       响应流信号发射器
     * @param finished   完成标记
     */
    private void handleError(String logMessage, Throwable err,
                             Sinks.Many<String> sink, AtomicBoolean finished) {
        // 记录错误日志
        log.error(logMessage, err);
        // 发送错误到流
        error(sink, finished, err);
    }


    /**
     * 研究主题生成
     *
     * @param sink       响应流信号发射器
     * @param context    计划执行上下文
     * @param onComplete 完成回调
     */
    private void researchTopicGeneration(Sinks.Many<String> sink, PlanExecuteContext context, Runnable onComplete) {
        emit(sink, context.getHasSentFinalResult(), "\n🔍 正在生成研究主题...\n", AgentResponse.TYPE_THINKING);
        List<Message> messages = new ArrayList<>();
        //加入系统提示词，研究主题生成
        messages.add(new SystemMessage(agentPromptService.getPromptContent(AgentType.PLAN_EXECUTE, PromptKey.RESEARCH_TOPIC_GENERATION)));
        //加入聊天记录
        messages.addAll(context.getMessages());
        // 用XML标签包装原始问题
        messages.add(new UserMessage("<original_question>" + context.getQuestion() + "</original_question>"));
        // 创建研究主题缓冲区
        StringBuilder topicBuffer = new StringBuilder();
        chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .doOnNext(chunk -> {
                    // 记录 token 使用量（流式 Usage 通常只在最后一个 chunk 出现，用 max 覆盖）
                    ChatTokenUsageUtil.recordUsage(chunk, context.promptTokens, context.generationTokens);
                    // 提取文本内容
                    String text = null;
                    if (chunk != null && chunk.getResult() != null
                            && chunk.getResult().getOutput() != null) {
                        text = chunk.getResult().getOutput().getText();
                    }
                    if (text == null) {
                        return;
                    }
                    ThinkTagParser.ParseResult parse = ThinkTagParser.parse(text, context.getHasResearchTopicThink().get());
                    // 设置当前是否在思考中
                    context.setHasResearchTopicThink(new AtomicBoolean(parse.inThink()));
                    // 遍历解析出的每个文本段
                    for (ThinkTagParser.Segment segment : parse.segments()) {
                        // 发送思考内容
                        emit(sink, context.getHasSentFinalResult(), segment.content(), AgentResponse.TYPE_THINKING);
                        // 如果不是思考内容
                        if (!segment.thinking()) {
                            // 追加到研究主题缓冲区
                            topicBuffer.append(segment.content());
                        }
                    }
                }).doOnComplete(() -> {
                    // 研究主题生成完成，调用完成处理方法（内部会触发onComplete回调，此处不可重复调用，否则执行循环会并发启动两次）
                    researchTopicGenerationComplete(topicBuffer, sink, context, onComplete);
                }).doOnError(error ->
                        handleError("研究主题生成异常", error, sink, context.getHasSentFinalResult())
                ).subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * 研究主题生成完成
     *
     * @param topicBuffer
     * @param sink
     * @param context
     * @param onComplete
     */
    private void researchTopicGenerationComplete(StringBuilder topicBuffer, Sinks.Many<String> sink,
                                                 PlanExecuteContext context, Runnable onComplete) {
        emit(sink, context.getHasSentFinalResult(), "\n✅ 研究主题生成完成\n", AgentResponse.TYPE_THINKING);
        // 获取研究主题
        String researchTopic = topicBuffer.toString();
        // 设置研究主题
        context.setOptimizingResearchTopics(researchTopic);
        emit(sink, context.getHasSentFinalResult(), "\n🔍 研究主题：\n" + researchTopic, AgentResponse.TYPE_THINKING);
        onComplete.run();
    }

    /**
     * 执行循环阶段
     * 需求澄清->研究主题生成->执行循环
     * 这一步主要是根据研究主题进行一轮一轮研究
     *
     * @param sink    响应流信号发射器
     * @param context 计划执行上下文
     */
    private void executeLoopPhase(Sinks.Many<String> sink, PlanExecuteContext context) {
        Mono<Void> executionMono = executeLoop(sink, context);
        // 在弹性线程池中调度
        Disposable executionDisposable = executionMono.subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        // 成功完成时的回调（无操作）
                        unused -> {
                        },
                        // 错误时的处理
                        e -> handleExecutionError(e, sink, context.getHasSentFinalResult())
                );

        // 将执行Disposable添加到组合容器
        compositeDisposable.add(executionDisposable);
    }

    /**
     * 处理执行过程中的异常
     * 区分用户主动停止和真正的异常
     *
     * @param e        异常对象
     * @param sink     响应流信号发射器
     * @param finished 完成标记
     */
    private void handleExecutionError(Throwable e, Sinks.Many<String> sink, AtomicBoolean finished) {
        // 检查是否被用户停止
        if (compositeDisposable.isDisposed() || Thread.currentThread().isInterrupted()
                || (e.getMessage() != null && e.getMessage().contains("interrupted"))) {
            // 记录用户停止日志
            log.info("PlanExecuteAgent 执行被用户停止: {}", e.getMessage());
            // 真正的异常
        } else {
            // 记录错误日志
            log.error("PlanExecuteAgent execute error", e);
            // 发送错误到流
            error(sink, finished, e);
        }
    }

    /**
     * 执行循环
     *
     * @param sink    响应流信号发射器
     * @param context 计划执行上下文
     * @return
     */
    private Mono<Void> executeLoop(Sinks.Many<String> sink, PlanExecuteContext context) {
        // 执行一轮
        return Mono.fromRunnable(() -> {
            try {
                // 判断是否达到最大轮数或已发送最终结果或组合 disposable 已取消
                while (maxRounds > context.getRound() && !context.getHasSentFinalResult().get() && !compositeDisposable.isDisposed()) {
                    //轮次加1
                    context.nextRound();
                    //开始进入执行计划研究阶段
                    log.info("===== Plan-Execute Round {} =====", context.getRound());
                    emit(sink, context.getHasSentFinalResult(), "\n🔄 第 " + context.getRound() + " 轮研究开始\n", AgentResponse.TYPE_THINKING);
                    //生成计划
                    List<PlanTask> planTasks = generatePlan(sink, context);
                    // 检查是否已被停止
                    if (context.getHasSentFinalResult().get() || compositeDisposable.isDisposed()) {
                        // 直接退出
                        return;
                    }
                    // 计划为空或所有任务ID为null
                    if (planTasks.isEmpty() || planTasks.stream().allMatch(t -> t.id() == null)) {
                        // 跳出循环，进入总结阶段
                        break;
                    }
                    // 发送开始执行任务的思考消息
                    emit(sink, context.getHasSentFinalResult(), "\n--- 开始执行任务 ---\n\n", AgentResponse.TYPE_THINKING);
                    // 执行计划中的所有任务
                    Map<String, TaskResult> results = executePlan(planTasks, sink, context);
                    // 检查是否已被停止
                    if (context.getHasSentFinalResult().get() || compositeDisposable.isDisposed()) {
                        // 直接退出
                        return;
                    }
                    // 发送任务执行完成的思考消息
                    emit(sink, context.getHasSentFinalResult(), "\n--- 任务执行完成 ---\n\n", AgentResponse.TYPE_THINKING);
                    // 评审当前轮次的研究结果
                    CritiqueResult critiqueResult = critiquePlan(sink, context, planTasks, results);
                    // 检查是否已被停止
                    if (context.getHasSentFinalResult().get() || compositeDisposable.isDisposed()) {
                        // 直接退出
                        return;
                    }
                    // 如果评审通过
                    if (critiqueResult.passed()) {
                        // 跳出循环，进入总结阶段
                        break;
                    }
                    // 评审未通过但已达最大轮数：不再有下一轮，提示后直接进入总结阶段
                    if (context.getRound() >= maxRounds) {
                        emit(sink, context.getHasSentFinalResult(), "\n⚠️ 已达最大研究轮数（" + maxRounds + " 轮），将基于现有研究结果生成最终报告\n", AgentResponse.TYPE_THINKING);
                        break;
                    }
                    // 将评审反馈添加到状态（供下一轮计划参考）
                    context.add(new AssistantMessage("""
                            【Critique Feedback】
                            %s
                            """.formatted(critiqueResult.feedback())));
                    // 发送准备下一轮的思考消息
                    emit(sink, context.getHasSentFinalResult(), "\n--- 准备进入下一轮迭代 ---\n", AgentResponse.TYPE_THINKING);
                    // 判断是否需要压缩上下文
                    compressIfNeeded(sink, context);
                }
                // 发送研究完成的思考消息
                emit(sink, context.getHasSentFinalResult(), "\n✅ 研究阶段完成，准备生成最终报告\n", AgentResponse.TYPE_THINKING);
                summarizeStream(sink, context);
            } catch (Exception e) {
                // 检查是否被用户停止
                if (compositeDisposable.isDisposed() || Thread.currentThread().isInterrupted()
                        || (e.getMessage() != null && e.getMessage().contains("interrupted"))) {
                    // 记录用户停止日志
                    log.info("PlanExecuteAgent 执行被用户停止: {}", e.getMessage());
                    // 发送停止生成的文本响应
                    sink.tryEmitNext(createTextResponse("⏹ 用户已停止生成\n"));
                    // 完成流
                    complete(sink, context.getHasSentFinalResult());
                } else {
                    // 记录执行异常日志
                    log.error("PlanExecuteAgent 执行异常", e);
                    // 重新抛出异常让上层处理
                    throw e;
                }
            }
        });
    }

    /**
     * 总结流，生成最终报告
     * @param sink
     * @param context
     */
    private void summarizeStream(Sinks.Many<String> sink, PlanExecuteContext context) {
        // 发送生成报告的思考消息
        emit(sink, context.getHasSentFinalResult(), "\n📝 正在生成最终研究报告...\n\n", AgentResponse.TYPE_THINKING);
        // 从状态中提取所有工具执行结果
        String toolResults = context.extractToolResults();
        // 构建总结提示消息
        Prompt prompt = new Prompt(List.of(
                // 系统消息为总结提示
                new SystemMessage(PlanExecutePrompts.SUMMARIZE),
                new UserMessage("""
                                        【用户原始问题】
                                        %s
                        
                                        【研究主题】
                                        %s
                        
                                        【工具检索结果】
                                        %s
                        """.formatted(
                        // 用户原始问题
                        context.getQuestion(),
                        // 研究主题
                        context.getOptimizingResearchTopics() != null ? context.getOptimizingResearchTopics() : "未生成研究主题",
                        // 工具检索结果
                        toolResults.isEmpty() ? "（未检索到相关结果）" : toolResults
                ))
        ));
        Disposable subscribe = chatClient.prompt()
                .messages(prompt.getInstructions())
                .stream().chatResponse().publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    // 检查是否已被停止
                    if (context.getHasSentFinalResult().get() || compositeDisposable.isDisposed()) {
                        // 直接返回
                        return;
                    }
                    // 记录 token 使用量（流式 Usage 通常只在最后一个 chunk 出现，用 max 覆盖）
                    ChatTokenUsageUtil.recordUsage(chunk, context.promptTokens, context.generationTokens);
                    // 检查响应块有效性
                    if ((chunk == null
                            || chunk.getResult() == null
                            || chunk.getResult().getOutput() == null) || (chunk.getResult().getOutput().getText() == null)) {
                        // 无效响应块直接跳过
                        return;
                    }
                    // 解析think标签
                    ThinkTagParser.ParseResult parseResult = ThinkTagParser.parse(chunk.getResult().getOutput().getText(), context.getHasSummarizeThink().get());
                    // 更新think标签状态
                    context.setHasSummarizeThink(new AtomicBoolean(parseResult.inThink()));
                    // 遍历解析出的文本段
                    for (ThinkTagParser.Segment segment : parseResult.segments()) {
                        // 如果是思考内容
                        if (segment.thinking()) {
                            // 发送为思考响应
                            emit(sink, context.getHasSentFinalResult(), segment.content(), AgentResponse.TYPE_THINKING);
                            // 如果是正文内容
                        } else {
                            // 发送为文本响应（正文由下游 doOnNext 统一累积到 finalAnswerBuffer，
                            // 此处不再直接 append，避免与下游线程并发写 StringBuilder 且内容被重复保存）
                            emit(sink, context.getHasSentFinalResult(), segment.content(), AgentResponse.TYPE_TEXT);
                        }
                    }
                })
                .doOnComplete(() -> {
                    // 如果有搜索引用结果
                    if (!allReferences.isEmpty()) {
                        // 发送引用响应
                        sink.tryEmitNext(createReferenceResponse(JSON.toJSONString(allReferences)));
                    }
                    // 完成流
                    complete(sink, context.getHasSentFinalResult());
                }).doOnError(error -> error(sink, context.getHasSentFinalResult(), error))
                .subscribe();
        // 添加到组合Disposable容器以支持取消
        compositeDisposable.add(subscribe);

    }

    /**
     * 压缩上下文
     *
     * @param sink
     * @param context
     */
    private void compressIfNeeded(Sinks.Many<String> sink, PlanExecuteContext context) {
        // 如果上下文未超过字符限制
        if (context.currentChars() < contextCharLimit) {
            // 不需要压缩，直接返回
            return;
        }
        // 记录上下文过长的警告日志
        log.warn("===== Context too large, compressing ,size is {} =====", context.currentChars());
        // 发送压缩中的思考消息
        emit(sink, context.getHasSentFinalResult(), "📦 上下文过长，正在压缩...\n", AgentResponse.TYPE_THINKING);
        // 实现压缩逻辑
        // 检查是否已被停止
        if (context.getHasSentFinalResult().get() || compositeDisposable.isDisposed()) {
            // 直接返回
            return;
        }
        // 构建压缩提示消息
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("""
                        ##最大压缩限制（必须遵守）
                        -你输出的最终内容【总字符数（包含所有标签、空格、换行）】
                        不得超过：%s
                                - 这是硬性上限，不是建议
                                - 如超过该限制，视为压缩失败
                        
                        """.formatted(contextCharLimit) + PlanExecutePrompts.COMPRESS),
                // 用户消息包含完整的上下文内容
                new UserMessage(context.renderFullContext())
        ));
        // 同步调用LLM进行压缩
        org.springframework.ai.chat.model.ChatResponse compressResponse = chatModel.call(prompt);
        String snapshot = compressResponse
                // 获取聊天结果
                .getResult()
                // 获取输出消息
                .getOutput()
                // 获取文本内容
                .getText();
        // 记录 token 使用量
        ChatTokenUsageUtil.recordUsage(compressResponse, context.promptTokens, context.generationTokens);
        // 清空当前所有消息
        context.getMessages().clear();
        // 添加压缩后的状态消息
        context.add(new SystemMessage("【Compressed Agent State】\n" + snapshot));
        // 记录压缩完成后的日志
        log.warn("===== Context compress has completed, size is {} =====", context.currentChars());
        // 发送压缩完成的思考消息
        emit(sink, context.getHasSentFinalResult(), "✅ 上下文压缩完成\n", AgentResponse.TYPE_THINKING);
    }

    /**
     * 评审当前轮次的研究结果
     * 上下文：用户问题 + 研究主题 + 当前轮次的执行计划 + 当前轮次的工具结果
     *
     * @param sink
     * @param context
     * @param planTasks
     * @param results
     * @return
     */
    private CritiqueResult critiquePlan(Sinks.Many<String> sink, PlanExecuteContext context,
                                        List<PlanTask> planTasks, Map<String, TaskResult> results) {
        // 创建CritiqueResult转换器
        BeanOutputConverter<CritiqueResult> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });
        // 发送评估思考消息
        emit(sink, context.getHasSentFinalResult(), "\n🔍 正在评估当前研究结果...\n", AgentResponse.TYPE_THINKING);
        // 检查是否已被停止
        if (context.getHasSentFinalResult().get() || compositeDisposable.isDisposed()) {
            // 返回通过的评审结果（终止循环）
            return new CritiqueResult(true, "任务已取消");
        }
        // 构建评审用户消息
        StringBuilder userMessage = new StringBuilder();
        //构建用户消息：用户问题、研究主题内容、执行计划
        structureUserMessage(userMessage, context, planTasks);
        //构建工具结果
        structureToolResults(userMessage, results);
        //构建评审提示
        String critiquePrompt = agentPromptService.getPromptContent(AgentType.PLAN_EXECUTE, PromptKey.CRITIQUE) + "\n\n" +
                converter.getFormat();
        // 构建评审提示消息
        Prompt prompt = new Prompt(List.of(
                // 系统消息包含评审提示和格式要求
                new SystemMessage(critiquePrompt),
                // 用户消息包含评审上下文
                new UserMessage(userMessage.toString())
        ));
        // 同步调用LLM进行评审
        ChatClientResponse critiqueResponse = chatClient.prompt(prompt).call().chatClientResponse();
        String raw = critiqueResponse.chatResponse().getResult().getOutput().getText();
        // 记录 token 使用量
        ChatTokenUsageUtil.recordUsage(critiqueResponse.chatResponse(), context.promptTokens, context.generationTokens);
        // 去除think标签后解析评审结果
        CritiqueResult result = converter.convert(ThinkTagParser.stripThinkTags(raw));
        // 如果评审通过
        if (result.passed()) {
            // 发送评审通过消息
            emit(sink, context.getHasSentFinalResult(), "\n✅ 研究结果评估通过，准备生成最终报告\n", AgentResponse.TYPE_THINKING);
            // 评审未通过
        } else {
            // 发送评审未通过消息及反馈
            emit(sink, context.getHasSentFinalResult(), "\n⚠️ 研究结果评估未通过，原因分析：" + result.feedback() + "\n", AgentResponse.TYPE_THINKING);
        }

        // 返回评审结果
        return result;
    }

    /**
     * 结构用户消息
     * 构建示例：
     * 【用户原始问题】
     * xxxxxxxxxx
     * 【研究主题】
     * xxxxxxxxxx
     * 【当前轮次的执行计划】
     * xxxxxxxxxx
     * xxxxxxxxxx
     * xxxxxxxxxx
     *
     * @param userMessage 用户消息
     * @param context     计划执行上下文
     * @param planTasks   计划任务列表
     */
    private void structureUserMessage(StringBuilder userMessage, PlanExecuteContext context, List<PlanTask> planTasks) {
        userMessage.append("【用户原始问题】 \n").append(context.getQuestion()).append("\n");
        userMessage.append("【研究主题】 \n").append(context.getOptimizingResearchTopics() != null ?
                context.getOptimizingResearchTopics() : "未生成研究主题").append("\n");
        // 添加执行计划标签
        userMessage.append("\n\n【当前轮次的执行计划】\n");
        // 如果计划非空
        if (planTasks != null && !planTasks.isEmpty()) {
            // 遍历计划中的每个任务
            for (PlanTask task : planTasks) {
                // 格式化添加任务指令
                userMessage.append(String.format("- %s\n", task.instruction()));
            }
            // 计划为空
        } else {
            // 标记为无
            userMessage.append("无\n");
        }
    }

    /**
     * 结构工具结果
     * 构建示例：
     * 【当前轮次的工具结果】
     * 任务 task-1: xxxxxxxxxx
     * 任务 task-2: xxxxxxxxxx
     * 任务 task-3: xxxxxxxxxx
     *
     * @param results
     */
    private void structureToolResults(StringBuilder userMessage, Map<String, TaskResult> results) {
        // 添加工具结果标签
        userMessage.append("\n\n【当前轮次的工具结果】\n");
        // 如果结果非空
        if (results != null && !results.isEmpty()) {
            // 遍历每个任务结果
            for (Map.Entry<String, TaskResult> entry : results.entrySet()) {
                // 获取任务结果
                TaskResult result = entry.getValue();
                // 任务成功且有输出
                if (result != null && result.success() && result.output() != null) {
                    // 添加成功的任务结果
                    userMessage.append(String.format("任务 %s: %s\n\n",
                            entry.getKey(), result.output()));
                    // 任务失败且有错误信息
                } else if (result != null && !result.success() && result.error() != null) {
                    // 添加失败的任务结果
                    userMessage.append(String.format("任务 %s: 执行失败 - %s\n\n",
                            entry.getKey(), result.error()));
                }
            }
            // 结果为空
        } else {
            // 标记为无
            userMessage.append("无\n");
        }
    }

    /**
     * 执行计划
     * [
     * {"id":"task-1","instruction":"调用 XXX 工具，执行<明确查询或操作>","order":1},
     * {"id":"task-2","instruction":"调用 XXX 工具，执行<明确查询或操作>","order":1},
     * {"id":"task-3","instruction":"根据 task1 和 task-2 的结果，调用 XXX 工具，执行<明确查询或操作>","order":2}
     * ]
     * order为0，无需调用任何工具
     * order为1，可以并发执行的任务
     * order为2，需要依赖前一个任务结果的任务，或者需要依赖多个任务结果的任务
     *
     * @param planTasks 计划任务列表
     * @param sink      响应流信号发射器
     * @param context   计划执行上下文
     * @return 任务结果映射
     */
    private Map<String, TaskResult> executePlan(List<PlanTask> planTasks, Sinks.Many<String> sink, PlanExecuteContext context) {
        // 使用并发Map存储任务结果
        Map<String, TaskResult> results = new ConcurrentHashMap<>();
        // 按order字段分组
        Map<Integer, List<PlanTask>> grouped = planTasks.stream().collect(Collectors.groupingBy(PlanTask::order));
        // 累积已完成任务的结果（用于依赖传递）
        Map<String, String> accumulatedResults = new ConcurrentHashMap<>();
        // 遍历每个order，
        for (Integer order : new TreeSet<>(grouped.keySet())) {
            // 检查是否已被停止
            if (context.getHasSentFinalResult().get() || compositeDisposable.isDisposed()) {
                // 跳出循环
                break;
            }
            // 构建任务执行的依赖上下文
            String dependencyContext = buildDependencyContext(accumulatedResults, order, planTasks);
            // 获取当前order组的所有任务
            List<PlanTask> tasks = grouped.get(order);
            // 创建计数器，数量为本组任务数
            CountDownLatch latch = new CountDownLatch(tasks.size());
            //遍历当前order的全部任务
            for (PlanTask task : tasks) {
                //执行每个任务
                Disposable disposable = executeTask(task, sink, context, dependencyContext, results, accumulatedResults, latch);
                // 添加到组合Disposable容器以支持取消
                compositeDisposable.add(disposable);
            }
            try {
                // 等待本组所有任务执行完成，再进入下一个order组（保证依赖传递和结果收集完整）
                latch.await();
            } catch (InterruptedException e) {
                // 恢复中断状态并停止后续任务组的执行
                Thread.currentThread().interrupt();
                log.info("executePlan 等待任务完成时被中断");
                break;
            }

        }
        return results;
    }

    /**
     * 执行任务，支持重试
     *
     * @param task
     * @param sink
     * @param context
     * @param dependencyContext
     * @param results
     * @param accumulatedResults
     * @param latch
     * @return 返回任务执行的Disposable开关，用于取消任务执行
     */
    private Disposable executeTask(PlanTask task, Sinks.Many<String> sink, PlanExecuteContext context, String dependencyContext,
                                   Map<String, TaskResult> results, Map<String, String> accumulatedResults, CountDownLatch latch) {
        return Mono.fromRunnable(() -> {
            log.info("正在执行任务==========》：{}", task.id());
                    // 标记是否已获取信号量许可
                    boolean acquired = false;
                    try {
                        // 检查是否已被取消
                        if (compositeDisposable.isDisposed()) {
                            // 直接返回
                            return;
                        }
                        // 获取信号量许可（限制并发数）
                        toolSemaphore.acquire();
                        // 标记已获取许可
                        acquired = true;
                        // 检查任务有效性
                        if (task == null || task.id() == null || task.id().isEmpty()) {
                            // 无效任务直接返回
                            return;
                        }
                        // 再次检查是否已被取消
                        if (compositeDisposable.isDisposed()) {
                            // 直接返回
                            return;
                        }
                        TaskResult taskResult = executeTaskWithRetry(task, sink, context, dependencyContext);
                        // 将结果存入并发Map
                        results.put(task.id(), taskResult);
                        // 如果任务成功且有输出
                        if (taskResult.success() && taskResult.output() != null) {
                            // 累积结果供后续依赖使用
                            accumulatedResults.put(task.id(), taskResult.output());
                        }
                        // 构建结果消息
                        StringBuilder resultMessage = new StringBuilder();
                        // 添加完成标记
                        resultMessage.append("【Completed Task Result】\n");
                        // 添加任务ID
                        resultMessage.append("taskId: ").append(task.id()).append("\n");
                        // 添加成功状态
                        resultMessage.append("success: ").append(taskResult.success()).append("\n");
                        // 如果有输出
                        if (taskResult.output() != null) {
                            // 添加输出内容
                            resultMessage.append("result:\n").append(taskResult.output()).append("\n");
                        }
                        // 如果有错误
                        if (taskResult.error() != null) {
                            // 添加错误信息
                            resultMessage.append("error:\n").append(taskResult.error()).append("\n");
                        }
                        // 添加结果结束标记
                        resultMessage.append("【End Task Result】");
                        // 将任务结果添加到全局状态
                        context.add(new AssistantMessage(resultMessage.toString()));
                    } catch (InterruptedException e) {
                        // 记录中断日志
                        log.info("Task {} 执行被中断", task.id());
                        // 恢复中断状态
                        Thread.currentThread().interrupt();

                        // 记录中断结果
                        results.put(task.id(), new TaskResult(task.id(), false, null, "Task execution interrupted"));
                    } catch (Exception e) {
                        // 检查是否被用户停止
                        if (compositeDisposable.isDisposed() || Thread.currentThread().isInterrupted()
                                || (e.getMessage() != null && e.getMessage().contains("interrupted"))) {
                            // 记录用户停止日志
                            log.info("Task {} 执行被用户停止: {}", task.id(), e.getMessage());
                            // 记录停止结果
                            results.put(task.id(), new TaskResult(task.id(), false, null, "Task execution interrupted by user"));
                            // 真正的异常
                        } else {
                            // 记录任务执行错误
                            log.error("Task execution error", e);
                            // 记录错误结果
                            results.put(task.id(), new TaskResult(task.id(), false, null, "Task execution error: " + e.getMessage()));
                        }
                    } finally {
                        // 如果已获取信号量许可
                        if (acquired) {
                            // 释放信号量许可
                            toolSemaphore.release();
                        }
                        // 计数器减一，通知等待线程
                        latch.countDown();
                    }
                }).subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * 执行任务并重试
     *
     * @param task
     * @param sink
     * @param context
     * @param dependencyContext
     * @return
     */
    private TaskResult executeTaskWithRetry(PlanTask task, Sinks.Many<String> sink, PlanExecuteContext context, String dependencyContext) {
        // 发送任务执行消息（并发任务下原子发送，避免多线程分块交错导致前端文本乱码）
        emitAtomic(sink, context.getHasSentFinalResult(), "⚙️ 正在执行任务 " + task.id() + " : " + task.instruction() + "\n\n", AgentResponse.TYPE_THINKING);
        // 检查是否已被停止
        if (context.getHasSentFinalResult().get() || compositeDisposable.isDisposed()) {
            // 返回停止结果
            return new TaskResult(task.id(), false, null, "任务被用户停止");
        }
        // 记录最后一次错误
        Throwable lastError = null;
        try {
            // 构建完整任务上下文（依赖 + 当前任务指令）
            String fullContext = """
                                【Available Results】
                                %s
                    
                                【Current Task】
                                %s
                    """.formatted(
                    // 依赖任务的结果
                    dependencyContext,
                    // 当前任务的指令
                    task.instruction()
            );
            // 为当前任务创建SimpleReactAgent
            SimpleReactAgent agent = SimpleReactAgent.builder()
                    // 设置聊天模型
                    .chatModel(chatModel)
                    // 设置可用工具
                    .tools(tools)
                    // 设置单任务最大推理轮次为5
                    .maxRound(5)
                    // 设置任务执行系统提示
                    .systemPrompt(agentPromptService.getPromptContent(AgentType.PLAN_EXECUTE,PromptKey.TOOL_EXECUTE))
                    // 设置提示词服务（内部构建系统提示词时使用）
                    .agentPromptService(agentPromptService)
                    // 共享外部 token 计数器
                    .promptTokens(context.promptTokens)
                    .generationTokens(context.generationTokens)
                    // 监听工具执行结果，收集搜索参考来源
                    .toolResultListener((toolName, resultStr) -> {
                        // 记录使用的工具名称
                        recordUsedTool(toolName);
                        // 如果是tavily搜索工具，解析搜索结果并收集到参考来源列表
                        if (toolName.contains("tavily")) {
                            parseSearchResult(resultStr);
                        }
                    })
                    // 构建Agent实例
                    .build();
            // 调用Agent执行任务并获取回答
            String answer = agent.call(fullContext);
            // 检查是否已被取消
            if (compositeDisposable.isDisposed()) {
                // 返回停止结果
                return new TaskResult(task.id(), false, null, "任务被用户停止");
            }
            // 发送执行结果思考消息（并发任务下原子发送，避免多线程分块交错）
            emitAtomic(sink, context.getHasSentFinalResult(), "任务：" + task.id() + " 执行结果: " + answer + "\n\n", AgentResponse.TYPE_THINKING);
            // 返回成功结果
            return new TaskResult(task.id(), true, answer, null);
        } catch (Exception e) {
            // 检查是否被用户停止
            if (compositeDisposable.isDisposed() || Thread.currentThread().isInterrupted()
                    || (e.getMessage() != null && e.getMessage().contains("interrupted"))) {
                // 记录用户停止日志
                log.info("Task {} 执行被用户停止: {}", task.id(), e.getMessage());
                // 返回停止结果
                return new TaskResult(task.id(), false, null, "任务被用户停止");
            }
            // 记录最后一次错误
            lastError = e;
            // 记录任务失败警告
            log.warn("Task {} failed: {}", task.id(), e.getMessage());
        }
        // 发送任务失败消息（并发任务下原子发送，避免多线程分块交错）
        emitAtomic(sink, context.getHasSentFinalResult(), "\n❌ 任务 " + task.id() + " 执行失败: " + (lastError == null ? "unknown error" : lastError.getMessage()) + "\n\n", AgentResponse.TYPE_THINKING);
        // 返回失败结果
        return new TaskResult(
                task.id(),
                false,
                null,
                lastError == null ? "unknown error" : lastError.getMessage()
        );
    }

    /**
     * 构建任务执行的依赖上下文
     * 规则：同 order 的任务不传依赖（并行），不同 order 的任务只传递上一个 order 的结果
     * 注意：此方法只返回【Available Results】部分，【Current Task】由 executeWithRetry 拼接
     *
     * @param accumulatedResults 所有已完成任务的结果
     * @param currentOrder       当前任务的 order
     * @param planTasks          计划任务列表
     * @return 依赖上下文字符串
     */
    private String buildDependencyContext(Map<String, String> accumulatedResults, Integer currentOrder, List<PlanTask> planTasks) {
        // 创建上下文字符串构建器
        StringBuilder context = new StringBuilder();
        // 如果是第一个执行顺序
        if (currentOrder == 1) {
            // 没有依赖，返回"无"
            return context.append("无\n").toString();
        }
        // 标记是否找到依赖结果
        boolean hasDependencies = false;
        // 遍历所有已完成任务的结果
        for (Map.Entry<String, String> entry : accumulatedResults.entrySet()) {
            // 在计划中查找对应任务
            PlanTask task = planTasks.stream()
                    // 按任务ID匹配
                    .filter(t -> t.id() != null && t.id().equals(entry.getKey()))
                    // 获取第一个匹配项
                    .findFirst()
                    // 未找到返回null
                    .orElse(null);
            // 如果是上一个order的任务结果
            if (task != null && task.order() == currentOrder - 1) {
                // 如果还没有添加过"任务"前缀
                if (!hasDependencies) {
                    // 添加"任务"前缀
                    context.append("任务 ");
                    // 标记已有依赖
                    hasDependencies = true;
                }
                // 格式化添加任务结果
                context.append(String.format("%s: %s\n\n",
                        entry.getKey(),
                        entry.getValue()));
            }
        }
        // 如果没有找到任何依赖
        if (!hasDependencies) {
            // 标记为无依赖
            context.append("无\n");
        }
        // 返回构建好的依赖上下文
        return context.toString();
    }

    /**
     * 根据用户需求以及模型生成的研究主题生成计划
     * [
     * {"id":"task-1","instruction":"调用 XXX 工具，执行<明确查询或操作>","order":1},
     * {"id":"task-2","instruction":"调用 XXX 工具，执行<明确查询或操作>","order":1},
     * {"id":"task-3","instruction":"根据 task1 和 task-2 的结果，调用 XXX 工具，执行<明确查询或操作>","order":2}
     * ]
     *
     * @param sink
     * @param context
     * @return
     */
    private List<PlanTask> generatePlan(Sinks.Many<String> sink, PlanExecuteContext context) {
        String toolDesc = renderToolDescriptions();
        // 创建List<PlanTask>的输出转换器
        BeanOutputConverter<List<PlanTask>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });
        //添加研究主题
        // 构建提示消息
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(agentPromptService.getPromptContent(AgentType.PLAN_EXECUTE, PromptKey.PLAN)
                        .formatted(context.getRound(), toolDesc, converter.getFormat())),
                new UserMessage("""
                        【研究主题】
                        %s
                        
                        【对话历史】
                        %s
                        
                        ## 重要约束
                        如果会话历史中存在【Critique Feedback】，你必须：
                        1. 仔细分析反馈中指出的不足
                        2. 新的计划必须直接解决这些问题
                        3. 不要重复之前失败的尝试
                        """.formatted(
                        // 研究主题或原始问题
                        context.getOptimizingResearchTopics() != null ? context.getOptimizingResearchTopics() : context.getQuestion(),
                        // 渲染完整对话上下文
                        context.renderFullContext()
                ))
        ));
        // 发送生成计划的思考消息
        emit(sink, context.getHasSentFinalResult(), "📋 正在生成执行计划...\n", AgentResponse.TYPE_THINKING);
        // 检查是否已被停止
        if (context.getHasSentFinalResult().get() || compositeDisposable.isDisposed()) {
            // 返回空计划列表
            return new ArrayList<>();
        }
        // 同步调用LLM生成计划
        ChatClientResponse planResponse = chatClient.prompt()
                // 设置提示消息
                .messages(prompt.getInstructions())
                // 执行同步调用
                .call()
                // 获取完整客户端响应（含 Usage）
                .chatClientResponse();
        String json = planResponse.chatResponse().getResult().getOutput().getText();
        // 记录 token 使用量
        ChatTokenUsageUtil.recordUsage(planResponse.chatResponse(), context.promptTokens, context.generationTokens);
        // 去除think标签后解析为PlanTask列表
        List<PlanTask> planTasks = converter.convert(Objects.requireNonNull(ThinkTagParser.stripThinkTags(json)));
        // 发送计划生成完成消息
        emit(sink, context.getHasSentFinalResult(), "\n✅ 执行计划已生成，共 " + planTasks.size() + " 个任务\n", AgentResponse.TYPE_THINKING);
        // 如果计划非空
        if (!planTasks.isEmpty()) {
            // 创建计划表文本
            StringBuilder planText = new StringBuilder("\n📋 执行计划表：\n");
            // 遍历每个任务
            for (PlanTask task : planTasks) {
                // 格式化显示任务指令
                planText.append(String.format("  🟠 %s \n", task.instruction()));
            }
            // 发送计划表展示消息（整块原子发送，避免分块与其他输出交错）
            emitAtomic(sink, context.getHasSentFinalResult(), planText.toString(), AgentResponse.TYPE_THINKING);
        }
        // 返回生成的计划任务列表
        return planTasks;

    }

    /**
     * 解析Tavily搜索结果JSON，提取搜索结果并收集到参考来源列表
     * JSON结构：[{ text: { results: [{ url, title, content }, ...] } }]
     * 并发任务下多个任务可能搜到相同页面，按url去重
     *
     * @param resultJson 工具返回的JSON字符串
     */
    private void parseSearchResult(String resultJson) {
        try {
            // 解析根节点数组
            JSONArray root = JSON.parseArray(resultJson);
            // 根节点为空时直接返回
            if (root == null || root.isEmpty()) {
                return;
            }
            // 获取数组第一个元素
            JSONObject first = root.getJSONObject(0);
            // 获取text字段（可能是嵌套JSON字符串或JSON对象）
            Object textNode = first.get("text");
            // text字段不存在时直接返回
            if (textNode == null) {
                return;
            }
            // 字符串类型时二次解析，否则直接使用
            JSONObject textJson = textNode instanceof String s ? JSON.parseObject(s) : first.getJSONObject("text");
            // 获取results数组
            JSONArray results = textJson.getJSONArray("results");
            // results不存在时直接返回
            if (results == null) {
                return;
            }
            // 遍历每条搜索结果
            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                String url = item.getString("url");
                // 只收集有URL的结果
                if (url == null || url.isBlank()) {
                    continue;
                }
                // 同步块保证去重检查与添加的原子性
                synchronized (allReferences) {
                    // 按url去重，避免多任务重复收集同一页面
                    boolean exists = allReferences.stream().anyMatch(r -> url.equals(r.url()));
                    if (!exists) {
                        // 创建SearchResult并添加到参考来源列表
                        allReferences.add(new SearchResult(item.getString("title"), item.getString("content"), url));
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败不影响主流程，仅记录警告日志
            log.warn("解析 tavily 搜索结果失败: {}", e.getMessage());
        }
    }

    /**
     * 渲染工具描述文本
     * 将所有可用工具的名称和描述格式化为文本列表
     *
     * @return 格式化后的工具描述文本
     */
    private String renderToolDescriptions() {
        // 如果没有可用工具
        if (tools == null || tools.length == 0) {
            // 返回无工具提示
            return "（当前无可用工具）";
        }

        // 创建字符串构建器
        StringBuilder sb = new StringBuilder();
        // 遍历每个工具
        for (ToolCallback tool : tools) {
            // 添加列表前缀
            sb.append("- ")
                    // 添加工具名称
                    .append(tool.getToolDefinition().name())
                    // 添加分隔符
                    .append(": ")
                    // 添加工具描述
                    .append(tool.getToolDefinition().description())
                    // 添加换行符
                    .append("\n");
        }
        // 返回格式化后的工具描述文本
        return sb.toString();
    }

    public static class Builder {

        // 聊天模型字段
        private ChatModel chatModel;
        // 工具数组字段，默认为空数组
        private ToolCallback[] tools = new ToolCallback[0];
        // 最大轮次字段，默认为3轮
        private int maxRounds = 3;
        // 上下文字符限制字段，默认50000字符
        private int contextCharLimit = 50000;
        // 工具重试次数字段，默认为2次
        private int maxToolRetries = 2;
        // 会话服务字段，用于数据库操作
        private ChatConversationService chatConversationService;
        // 消息服务字段，用于数据库操作
        private ChatMessageService chatMessageService;
        private AgentPromptService agentPromptService;
        // 任务管理器字段，用于管理任务生命周期
        private AgentTaskManager taskManager;
        // 每日 Token 限制服务字段
        private ChatTokenLimitService chatTokenLimitService;

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder tools(ToolCallback[] tools) {
            this.tools = tools;
            return this;
        }

        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public Builder contextCharLimit(int contextCharLimit) {
            this.contextCharLimit = contextCharLimit;
            return this;
        }

        public Builder maxToolRetries(int maxToolRetries) {
            this.maxToolRetries = maxToolRetries;
            return this;
        }

        public Builder chatConversationService(ChatConversationService chatConversationService) {
            this.chatConversationService = chatConversationService;
            return this;
        }

        public Builder chatMessageService(ChatMessageService chatMessageService) {
            this.chatMessageService = chatMessageService;
            return this;
        }
        public Builder agentPromptService(AgentPromptService agentPromptService) {
            this.agentPromptService = agentPromptService;
            return this;
        }

        public Builder taskManager(AgentTaskManager taskManager) {
            this.taskManager = taskManager;
            return this;
        }

        public Builder chatTokenLimitService(ChatTokenLimitService chatTokenLimitService) {
            this.chatTokenLimitService = chatTokenLimitService;
            return this;
        }

        /**
         * 构建PlanExecuteAgent实例
         *
         * @return 配置完成的PlanExecuteAgent实例
         */
        public PlanExecuteAgent build() {
            // 校验chatModel不能为空
            Objects.requireNonNull(chatModel, "chatModel must not be null");
            // 使用当前Builder配置创建Agent实例
            return new PlanExecuteAgent(this);
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
