package cn.john.dh.assistant.agent.deepresearch;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.agent.AgentTaskManager;
import cn.john.dh.assistant.agent.BaseAgent;
import cn.john.dh.assistant.agent.deepresearch.context.PlanExecuteContext;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.common.AgentResponse;
import cn.john.dh.assistant.constant.AgentType;
import cn.john.dh.assistant.constant.PromptKey;
import cn.john.dh.assistant.entity.SearchResult;
import cn.john.dh.assistant.utils.ThinkTagParser;
import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * 内部调用方法，用于执行计划-执行代理
     *
     * @param conversationId
     * @param question
     * @return
     */
    public Flux<String> chat(String conversationId, String question) {
        return null;
    }


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
        // 设置聊天记忆（从BaseAgent继承的字段）
        this.chatMemory = builder.chatMemory;
        // 设置会话服务（从BaseAgent继承的字段）
        this.chatConversationService = builder.chatConversationService;
        // 设置消息服务（从BaseAgent继承的字段）
        this.chatMessageService = builder.chatMessageService;
        // 设置任务管理器（从BaseAgent继承的字段）
        this.taskManager = builder.taskManager;
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
        Flux<String> checkResult = checkRunningTask(conversationId);
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
        // 清除之前记录的工具使用记录
        clearUsedTools();
        //设置当前会话问题
        currentQuestion = question;
        // 初始化全局状态并将问题保存到数据库
        PlanExecuteContext ptx = getPlanExecuteContext(question, conversationId);

        //开始需求澄清->需求澄清之后启动研究主题生成->研究主题生成之后启动执行循环
        clarificationRequirements(sink, ptx, () -> {
        });
        return null;
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
        log.info("正在执行需求澄清阶段，问题：{}", context.getQuery());
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
                .content()
                .doOnNext(chunk -> {
                    // 解析每一段chunk，如果有<think>标签则截取放到parse的segment中
                    ThinkTagParser.ParseResult parse = ThinkTagParser.parse(chunk, context.isInThink());
                    context.setInThink(parse.inThink());
                    for (ThinkTagParser.Segment segment : parse.segments()) {
                        //先把<think>标签的内容数据推给页面
                        emit(sink, context.getHasSentFinalResult(), segment.content(), AgentResponse.TYPE_THINKING);
                        //如果没有<think>标签的内容当做正文内容，先保存到response中。
                        if (!parse.inThink()) {
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
     * @param response
     * @param sink
     * @param context
     * @param onComplete
     */
    private void clarificationRequirementsComplete(StringBuilder response, Sinks.Many<String> sink,
                                                   PlanExecuteContext context, Runnable onComplete) {
        // 获取LLM的完整响应文本
        String responseStr = response.toString();
        // 发送需求分析完成的思考消息
        emit(sink, context.getHasSentFinalResult(), "\n✅ 需求分析完成\n", AgentResponse.TYPE_THINKING);
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
        // 聊天记忆字段，用于管理对话历史
        private ChatMemory chatMemory;
        // 会话服务字段，用于数据库操作
        private ChatConversationService chatConversationService;
        // 消息服务字段，用于数据库操作
        private ChatMessageService chatMessageService;
        // 任务管理器字段，用于管理任务生命周期
        private AgentTaskManager taskManager;

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

        public Builder taskManager(AgentTaskManager taskManager) {
            this.taskManager = taskManager;
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
}
