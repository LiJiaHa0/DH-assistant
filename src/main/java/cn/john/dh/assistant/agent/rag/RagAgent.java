package cn.john.dh.assistant.agent.rag;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.agent.AgentTaskManager;
import cn.john.dh.assistant.agent.BaseAgent;
import cn.john.dh.assistant.chat.domain.entity.ChatConversation;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.chat.service.ChatTokenLimitService;
import cn.john.dh.assistant.chat.util.ChatTokenUsageUtil;
import cn.john.dh.assistant.common.AgentResponse;
import cn.john.dh.assistant.constant.AgentType;
import cn.john.dh.assistant.constant.ChatMessageType;
import cn.john.dh.assistant.constant.DataQueryConstant;
import cn.john.dh.assistant.constant.MetadataKeyConstant;
import cn.john.dh.assistant.constant.PromptKey;
import cn.john.dh.assistant.entity.SearchResult;
import cn.john.dh.assistant.prompt.RagAgentPrompts;
import cn.john.dh.assistant.prompt.service.AgentPromptService;
import cn.john.dh.assistant.rag.config.KnowledgeBase;
import cn.john.dh.assistant.rag.config.VectorStoreRouter;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeSegment;
import cn.john.dh.assistant.rag.domain.entity.TableMeta;
import cn.john.dh.assistant.rag.domain.enums.KnowledgeBaseType;
import cn.john.dh.assistant.rag.mapper.TableMetaMapper;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.rag.service.KnowledgeSegmentService;
import cn.john.dh.assistant.rag.service.impl.FileStorageService;
import cn.john.dh.assistant.utils.ThinkTagParser;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;



/**
 * RAG 知识库问答Agent
 * 实现关键词提取、查询重写、意图识别、向量检索、BM25关键词检索、
 * 用户ID过滤、结果去重、RRF融合等功能，并通过sink与前端进行每一步的交互提示
 *
 * @Author John
 * @Date 2026-08-07
 */
@Slf4j
public class RagAgent extends BaseAgent {

    // 聊天客户端实例，用于流式回答生成
    private ChatClient chatClient;

    // 向量存储路由器，用于路由到不同知识库的Milvus Collection
    private final VectorStoreRouter vectorStoreRouter;

    // 知识文档服务，用于按用户ID查询文档
    private final KnowledgeDocumentService knowledgeDocumentService;

    // 知识片段服务，用于BM25关键词检索
    private final KnowledgeSegmentService knowledgeSegmentService;

    // 表元数据 Mapper，用于 DATA_QUERY 类型知识库的 Text2SQL 查询
    private final TableMetaMapper tableMetaMapper;

    // 文件存储服务，用于将私有 MinIO URL 转换为预签名公开 URL（参考来源展示）
    private final FileStorageService fileStorageService;

    // 联网搜索工具回调数组，用于 RAG 检索无结果时提示用户联网搜索
    private final ToolCallback[] webSearchTools;

    // 是否启用联网搜索提示（true：前端选了联网，RAG无结果时提示用户确认是否联网；false：不提示）
    private final boolean enableWebSearchFallback;

    // Text2SQL 最大重试次数（生成+校验+执行循环）
    private static final int MAX_SQL_RETRY = 3;

    // 向量检索每个知识库的topK
    private final int vectorTopK;

    // BM25检索返回的最大片段数
    private final int bm25TopK;

    // RRF融合后返回的最大结果数
    private final int fusedTopK;

    // RRF常量参数
    private static final int RRF_K = 60;

    // BM25参数
    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;

    /**
     * 私有构造方法，通过Builder模式创建实例
     *
     * @param builder 构建器实例，包含所有配置参数
     */
    private RagAgent(Builder builder) {
        // 调用父类构造，设置Agent名称、模型和类型
        super("RagAgent", builder.chatModel, "rag");
        // 设置向量存储路由器
        this.vectorStoreRouter = builder.vectorStoreRouter;
        // 设置知识文档服务
        this.knowledgeDocumentService = builder.knowledgeDocumentService;
        // 设置知识片段服务
        this.knowledgeSegmentService = builder.knowledgeSegmentService;
        // 设置表元数据 Mapper（用于 DATA_QUERY Text2SQL 查询）
        this.tableMetaMapper = builder.tableMetaMapper;
        // 设置文件存储服务（用于预签名 URL）
        this.fileStorageService = builder.fileStorageService;
        // 设置联网搜索工具回调
        this.webSearchTools = builder.webSearchTools;
        // 设置联网搜索提示开关
        this.enableWebSearchFallback = builder.enableWebSearchFallback;
        // 设置检索参数
        this.vectorTopK = builder.vectorTopK;
        this.bm25TopK = builder.bm25TopK;
        this.fusedTopK = builder.fusedTopK;
        // 设置会话服务（从BaseAgent继承的字段）
        this.chatConversationService = builder.chatConversationService;
        // 设置消息服务（从BaseAgent继承的字段）
        this.chatMessageService = builder.chatMessageService;
        // 设置提示词服务
        this.agentPromptService = builder.agentPromptService;
        // 设置每日 Token 限制服务（从BaseAgent继承的字段）
        this.chatTokenLimitService = builder.chatTokenLimitService;
        // 设置任务管理器（从BaseAgent继承的字段）
        this.taskManager = builder.taskManager;
        // 初始化已使用工具名称集合（从BaseAgent继承的字段）
        this.usedTools = new HashSet<>();
        // 初始化ChatClient
        initChatClient();
        if (this.chatClient == null) {
            throw new IllegalStateException("ChatClient 初始化失败！");
        }
    }

    /**
     * 初始化ChatClient实例
     */
    private void initChatClient() {
        try {
            this.chatClient = ChatClient.builder(chatModel).build();
        } catch (Exception e) {
            throw new RuntimeException("ChatClient 初始化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 执行Agent，返回SSE流式响应
     *
     * @param conversationId 会话ID
     * @param question       用户问题
     * @return SSE流式响应的Flux
     */
    @Override
    public Flux<String> execute(String conversationId, String question) {
        return chat(conversationId, question);
    }

    /**
     * 流式输出的核心实现
     * 负责构建消息列表、执行RAG检索流程、流式生成回答
     *
     * @param conversationId 会话ID
     * @param question       用户问题
     * @return 流式响应Flux
     */
    public Flux<String> chat(String conversationId, String question) {
        // 解析会话ID，为空时创建新会话
        final String convId = resolveConversationId(conversationId, question);
        // 检查是否已有任务在执行
        Flux<String> checkResult = checkRunningTask(convId);
        if (checkResult != null) {
            return checkResult;
        }
        // 创建单播Sink并启用背压缓冲
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        // 注册任务到管理器
        AgentTaskManager.TaskInfo taskInfo = registerTask(convId, sink);
        if (taskInfo == null && convId != null && taskManager != null) {
            return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
        }
        try {
        // 清除之前记录的工具使用记录
        clearUsedTools();
        // 设置当前会话问题
        currentQuestion = question;
        // 在请求线程中获取用户ID（Sa-Token基于ThreadLocal，切换线程后不可用）
        final String userId = StpUtil.getLoginIdAsString();
        // 构建初始消息列表（System Prompt + 历史记忆 + 当前问题），并保存用户问题
        List<Message> messages = buildInitialMessages(convId, question);
        // 创建本次流式调用的上下文状态
        ChatStreamContext ctx = new ChatStreamContext();
        // 发送任务开始提示
        emit(sink, ctx.hasSentFinalResult, "\n📚 知识库问答开始\n", AgentResponse.TYPE_THINKING);

        // 在弹性线程池中执行RAG检索流程，完成后流式生成回答
        Disposable disposable = Schedulers.boundedElastic().schedule(() -> {
            try {
                // 执行RAG检索流程，获取上下文
                String context = performRagPipeline(question, userId, sink, ctx);
                // 检查是否已被停止
                if (ctx.hasSentFinalResult.get()) {
                    return;
                }
                // 检索不到相关内容：根据联网搜索回退策略决定下一步操作
                if (!StringUtils.hasText(context)) {
                    handleEmptyRagContext(question, messages, sink, ctx, convId);
                    return;
                }
                // 流式生成回答
                streamAnswer(messages, context, sink, ctx, convId);
            } catch (Exception e) {
                // 检查是否被用户停止
                if (Thread.currentThread().isInterrupted()
                        || (e.getMessage() != null && e.getMessage().contains("interrupted"))) {
                    log.info("RagAgent 执行被用户停止: {}", e.getMessage());
                    sink.tryEmitNext(createTextResponse("⏹ 用户已停止生成\n"));
                    complete(sink, ctx.hasSentFinalResult);
                } else {
                    log.error("RagAgent 执行异常", e);
                    error(sink, ctx.hasSentFinalResult, e);
                }
            }
        });
        // 保存Disposable引用到任务管理器，支持外部取消
        if (convId != null && taskManager != null) {
            taskManager.setDisposable(convId, disposable);
        }
        // 组装并返回响应流
        return assembleResponseFlux(sink, ctx, convId);
        } catch (Exception e) {
            // 同步阶段异常：此时响应流尚未返回，doFinally不会触发，必须主动清理任务，避免会话被永久锁定
            if (taskManager != null) {
                taskManager.stopTask(convId);
            }
            log.error("RagAgent 启动异常", e);
            // 返回错误流
            return Flux.error(e);
        }
    }

    /**
     * 解析会话ID，为空时创建新会话
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
     * 构建初始消息列表，加载System Prompt、历史记忆，保存用户问题并追加到消息列表
     *
     * @param conversationId 会话ID
     * @param question       用户问题
     * @return 消息列表
     */
    private List<Message> buildInitialMessages(String conversationId, String question) {
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        // 加载System Prompt
        messages.add(new SystemMessage(agentPromptService.getPromptContentAndBasePrompt(AgentType.RAG, PromptKey.SYSTEM_PROMPT)));
        // 加载历史记忆
        loadChatHistory(messages, conversationId, 10);
        // 保存用户问题
        chatMessageService.saveMessage(conversationId, ChatMessageType.USER, question);
        // 拼接当前会话问题
        messages.add(new UserMessage("<question>" + question + "</question>"));
        return messages;
    }

    /**
     * 组装响应流，绑定响应块累积、取消清理和最终落库的回调逻辑
     *
     * @param sink           响应流信号发射器
     * @param ctx            流式会话上下文
     * @param conversationId 会话ID
     * @return 组装完成的响应流
     */
    private Flux<String> assembleResponseFlux(Sinks.Many<String> sink, ChatStreamContext ctx, String conversationId) {
        return sink.asFlux()
                .doOnNext(chunk -> accumulateChunk(chunk, ctx))
                .doOnCancel(() -> handleStreamCancel(conversationId, ctx))
                .doFinally(finalStatus -> finalizeStream(conversationId, ctx));
    }

    /**
     * 累积响应块内容，解析响应块JSON，将文本内容和思考内容分别追加到对应缓冲区
     *
     * @param chunk 响应块
     * @param ctx   流式会话上下文
     */
    private void accumulateChunk(String chunk, ChatStreamContext ctx) {
        try {
            JSONObject json = JSON.parseObject(chunk);
            String type = json.getString("type");
            if ("text".equals(type)) {
                ctx.finalAnswerBuffer.append(json.getString("content"));
            } else if ("thinking".equals(type)) {
                ctx.thinkingBuffer.append(json.getString("content"));
            }
        } catch (Exception e) {
            ctx.finalAnswerBuffer.append(chunk);
        }
    }

    /**
     * 流取消时的清理逻辑，标记已发送最终结果并停止任务
     *
     * @param conversationId 会话ID
     * @param ctx            流式会话上下文
     */
    private void handleStreamCancel(String conversationId, ChatStreamContext ctx) {
        ctx.hasSentFinalResult.set(true);
        if (taskManager != null) {
            taskManager.stopTask(conversationId);
        }
    }

    /**
     * 流结束时的收尾逻辑，保存助手消息并清理任务
     *
     * @param conversationId 会话ID
     * @param ctx            流式会话上下文
     */
    private void finalizeStream(String conversationId, ChatStreamContext ctx) {
        log.info("RagAgent 最终答案长度: {}, 思考过程长度: {}",
                ctx.finalAnswerBuffer.length(), ctx.thinkingBuffer.length());
        // 构建 metadata
        JSONObject metadata = new JSONObject();
        if (ctx.thinkingBuffer.length() > 0) {
            metadata.put("thinking", ctx.thinkingBuffer.toString());
        }
        if (!ctx.references.isEmpty()) {
            metadata.put("references", ctx.references);
        }
        if (currentRecommendations != null) {
            metadata.put("recommend", currentRecommendations);
        }
        String metadataStr = metadata.isEmpty() ? null : metadata.toJSONString();
        // 保存Assistant消息
        chatMessageService.saveMessage(conversationId, ChatMessageType.ASSISTANT,
                ctx.finalAnswerBuffer.toString(), metadataStr);
        // 扣减当日 Token 限额
        consumeTokenLimit(conversationId, ctx);
        // 流结束时从任务管理器中移除任务
        if (taskManager != null) {
            taskManager.stopTask(conversationId);
        }
    }

    /**
     * 根据会话ID查询用户并扣减当日 Token 限额。
     *
     * @param conversationId 会话ID
     * @param ctx            流式会话上下文
     */
    private void consumeTokenLimit(String conversationId, ChatStreamContext ctx) {
        try {
            if (chatTokenLimitService == null || chatConversationService == null) {
                return;
            }
            ChatConversation conversation = chatConversationService.getByConversationId(conversationId);
            if (conversation == null || !StringUtils.hasText(conversation.getUserId())) {
                return;
            }
            long totalTokens = ChatTokenUsageUtil.getTotalTokens(ctx.promptTokens, ctx.generationTokens);
            if (totalTokens > 0) {
                chatTokenLimitService.consume(conversation.getUserId(), totalTokens);
                log.info("RagAgent 会话 {} 本次消耗 token: {}, model: {}",
                        conversationId, totalTokens, getModelName());
            }
        } catch (Exception e) {
            log.warn("RagAgent 扣减 token 限额失败: conversationId={}", conversationId, e);
        }
    }

    // ==================== RAG 检索流程 ====================

    /**
     * 执行RAG检索流程，根据知识库文档类型分别执行不同检索策略：
     * - DOCUMENT_SEARCH：关键词提取 + 查询重写 + 意图识别 + 向量检索 + BM25检索 + RRF融合
     * - DATA_QUERY：Text2SQL 动态 SQL 生成 + 校验 + 执行（最多重试3次）
     * 每一步都通过sink向前端发送交互提示
     *
     * @param question 用户原始问题
     * @param userId   用户ID
     * @param sink     响应流信号发射器
     * @param ctx      流式会话上下文
     * @return 检索结果拼接的上下文字符串，为空表示未检索到相关内容
     */
    private String performRagPipeline(String question, String userId,
                                       Sinks.Many<String> sink, ChatStreamContext ctx) {
        // 1. 获取用户文档列表，按类型分流
        emit(sink, ctx.hasSentFinalResult, "\n🔍 正在检索您的知识库文档...\n", AgentResponse.TYPE_THINKING);
        List<KnowledgeDocument> userDocs = getUserDocuments(userId);
        if (userDocs.isEmpty()) {
            emit(sink, ctx.hasSentFinalResult, "⚠️ 您的知识库中暂无文档，无法基于知识库回答\n", AgentResponse.TYPE_THINKING);
            return "";
        }
        emit(sink, ctx.hasSentFinalResult, "📄 共找到 " + userDocs.size() + " 篇文档\n", AgentResponse.TYPE_THINKING);

        // 按知识库类型分组：DOCUMENT_SEARCH vs DATA_QUERY
        List<KnowledgeDocument> docSearchDocs = new ArrayList<>();
        List<KnowledgeDocument> dataQueryDocs = new ArrayList<>();
        for (KnowledgeDocument doc : userDocs) {
            if (doc.getKnowledgeBaseType() == KnowledgeBaseType.DATA_QUERY) {
                dataQueryDocs.add(doc);
            } else {
                docSearchDocs.add(doc);
            }
        }

        StringBuilder fullContext = new StringBuilder();

        // ============ DOCUMENT_SEARCH 路径：向量 + BM25 混合检索 ============
        if (!docSearchDocs.isEmpty()) {
            String docSearchContext = performDocumentSearchPipeline(question, docSearchDocs, sink, ctx);
            if (docSearchContext != null && !docSearchContext.isEmpty()) {
                fullContext.append(docSearchContext);
            }
        }

        // ============ DATA_QUERY 路径：Text2SQL 动态查询 ============
        if (!dataQueryDocs.isEmpty() && tableMetaMapper != null) {
            String dataQueryContext = performDataQueryPipeline(question, dataQueryDocs, docSearchDocs, sink, ctx);
            if (dataQueryContext != null && !dataQueryContext.isEmpty()) {
                if (fullContext.length() > 0) {
                    fullContext.append("\n");
                }
                fullContext.append(dataQueryContext);
            }
        }

        String context = fullContext.toString();
        if (context.isEmpty()) {
            emit(sink, ctx.hasSentFinalResult, "⚠️ 知识库中未检索到相关内容，无法基于知识库回答\n", AgentResponse.TYPE_THINKING);
        } else {
            emit(sink, ctx.hasSentFinalResult, "✅ 检索完成，正在生成回答...\n", AgentResponse.TYPE_THINKING);
        }

        return context;
    }

    /**
     * DOCUMENT_SEARCH 检索流程：关键词提取 → 查询重写 → 意图识别 → 向量检索 → BM25检索 → RRF融合
     *
     * @param question      用户原始问题
     * @param docSearchDocs DOCUMENT_SEARCH 类型文档列表
     * @param sink          响应流信号发射器
     * @param ctx           流式会话上下文
     * @return 文档检索上下文字符串
     */
    private String performDocumentSearchPipeline(String question, List<KnowledgeDocument> docSearchDocs,
                                                  Sinks.Many<String> sink, ChatStreamContext ctx) {
        // 提取用户文档ID集合（仅 DOCUMENT_SEARCH 类型）
        Set<Long> userDocIds = new HashSet<>();
        for (KnowledgeDocument doc : docSearchDocs) {
            if (doc.getDocId() != null) {
                userDocIds.add(doc.getDocId());
            }
        }
        if (userDocIds.isEmpty()) {
            return "";
        }

        // 关键词提取
        emit(sink, ctx.hasSentFinalResult, "\n🔑 正在提取关键词...\n", AgentResponse.TYPE_THINKING);
        ctx.keywords = extractKeywords(question, sink, ctx);
        emit(sink, ctx.hasSentFinalResult, "提取到关键词: " + String.join(", ", ctx.keywords) + "\n", AgentResponse.TYPE_THINKING);

        // 查询重写
        emit(sink, ctx.hasSentFinalResult, "\n✏️ 正在重写查询...\n", AgentResponse.TYPE_THINKING);
        ctx.rewrittenQuery = rewriteQuery(question, sink, ctx);
        emit(sink, ctx.hasSentFinalResult, "重写后的查询: " + ctx.rewrittenQuery + "\n", AgentResponse.TYPE_THINKING);

        // 意图识别
        emit(sink, ctx.hasSentFinalResult, "\n🎯 正在识别问题意图...\n", AgentResponse.TYPE_THINKING);
        JSONObject intentResult = recognizeIntent(question, sink, ctx);
        ctx.intent = intentResult.getString("intent");
        ctx.intentDescription = intentResult.getString("description");
        emit(sink, ctx.hasSentFinalResult, "意图类型: " + ctx.intent + "（" + ctx.intentDescription + "）\n", AgentResponse.TYPE_THINKING);

        // 向量语义检索（跨所有知识库，后过滤用户文档）
        emit(sink, ctx.hasSentFinalResult, "\n📐 正在进行向量语义检索...\n", AgentResponse.TYPE_THINKING);
        List<RetrievalResult> vectorResults = vectorSearch(ctx.rewrittenQuery, userDocIds, sink, ctx);
        emit(sink, ctx.hasSentFinalResult, "向量检索到 " + vectorResults.size() + " 条结果\n", AgentResponse.TYPE_THINKING);

        // BM25关键词检索
        emit(sink, ctx.hasSentFinalResult, "\n🔎 正在进行BM25关键词检索...\n", AgentResponse.TYPE_THINKING);
        List<RetrievalResult> bm25Results = bm25Search(ctx.keywords, userDocIds, sink, ctx);
        emit(sink, ctx.hasSentFinalResult, "BM25检索到 " + bm25Results.size() + " 条结果\n", AgentResponse.TYPE_THINKING);

        // 去重 + RRF融合
        emit(sink, ctx.hasSentFinalResult, "\n🔀 正在进行结果融合与去重...\n", AgentResponse.TYPE_THINKING);
        List<RetrievalResult> fusedResults = deduplicateAndRRFFusion(vectorResults, bm25Results);
        emit(sink, ctx.hasSentFinalResult, "融合后得到 " + fusedResults.size() + " 条结果\n", AgentResponse.TYPE_THINKING);

        // 收集参考来源（去重，按文件名/URL过滤；私有 URL 转为预签名公开 URL 供前端展示）
        Set<String> refKeys = new HashSet<>();
        for (RetrievalResult result : fusedResults) {
            String refKey = result.fileName() != null ? result.fileName() : result.url();
            if (refKey != null && !refKey.isEmpty() && refKeys.add(refKey)) {
                ctx.references.add(new SearchResult(
                        result.fileName(),
                        result.content().length() > 200 ? result.content().substring(0, 200) + "..." : result.content(),
                        toPublicUrl(result.url())));
            }
        }

        // 构建文档检索上下文
        return buildContext(fusedResults);
    }

    /**
     * DATA_QUERY 检索流程：根据文档 description 和表结构，通过 Text2SQL 生成并执行查询
     * 对每个 DATA_QUERY 类型的文档，生成 SQL → 安全校验 → 执行 → 结果校验，最多重试 3 次
     *
     * @param question      用户原始问题
     * @param dataQueryDocs DATA_QUERY 类型文档列表
     * @param sink          响应流信号发射器
     * @param ctx           流式会话上下文
     * @return 数据查询结果上下文字符串
     */
    private String performDataQueryPipeline(String question, List<KnowledgeDocument> dataQueryDocs,
                                             List<KnowledgeDocument> docSearchDocs,
                                             Sinks.Many<String> sink, ChatStreamContext ctx) {
        emit(sink, ctx.hasSentFinalResult, "\n📊 检测到 " + dataQueryDocs.size() + " 个数据查询类型知识库，正在分析表结构...\n", AgentResponse.TYPE_THINKING);

        // 收集所有可用的查询表信息
        List<QueryTableInfo> queryTables = new ArrayList<>();
        for (KnowledgeDocument doc : dataQueryDocs) {
            String tableName = doc.getTableName();
            if (tableName == null || tableName.isEmpty()) {
                continue;
            }
            // knowledge_document.table_name 存储的是不带前缀的业务表名，
            // table_meta 与物理表均使用 dh_data_query_ 前缀，查询前需补全（已带前缀时不重复拼接）
            String physicalTableName = tableName.startsWith(DataQueryConstant.TABLE_PREFIX)
                    ? tableName
                    : DataQueryConstant.TABLE_PREFIX + tableName;
            try {
                TableMeta tableMeta = tableMetaMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TableMeta>()
                                .eq(TableMeta::getTableName, physicalTableName));
                if (tableMeta != null) {
                    String description = doc.getDescription() != null ? doc.getDescription() : tableMeta.getDescription();
                    queryTables.add(new QueryTableInfo(
                            doc.getDocId(),
                            doc.getDocTitle(),
                            description,
                            physicalTableName,
                            tableMeta.getColumnsInfo(),
                            tableMeta.getCreateSql()));
                }
            } catch (Exception e) {
                log.warn("查询表元数据失败: tableName={}, error={}", physicalTableName, e.getMessage());
            }
        }

        if (queryTables.isEmpty()) {
            emit(sink, ctx.hasSentFinalResult, "⚠️ 未找到可用的数据查询表\n", AgentResponse.TYPE_THINKING);
            return "";
        }

        // ===== 数据查询路由：先判断用户问题是否真的需要查询这些数据表 =====
        // 修复：原实现只要存在 DATA_QUERY 文档就无条件执行 Text2SQL，
        // 导致"技术选型对比"这类非数据检索问题也会去查表（甚至重试查全表）。
        if (!shouldQueryDataTables(question, queryTables, docSearchDocs, sink, ctx)) {
            return "";
        }

        // 打印可用表信息
        for (QueryTableInfo table : queryTables) {
            emit(sink, ctx.hasSentFinalResult, "📋 数据表: " + table.tableName()
                    + "（" + (table.description() != null ? table.description() : table.docTitle()) + "）\n",
                    AgentResponse.TYPE_THINKING);
        }

        // 收集当前用户文档允许查询的物理表名（SQL 安全校验白名单）
        Set<String> allowedTables = queryTables.stream()
                .map(QueryTableInfo::tableName)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        // 对每个表尝试 Text2SQL 查询（最多重试 3 次）
        StringBuilder dataContext = new StringBuilder();
        for (QueryTableInfo table : queryTables) {
            emit(sink, ctx.hasSentFinalResult, "\n💡 正在为表 " + table.tableName() + " 生成查询SQL...\n", AgentResponse.TYPE_THINKING);
            String result = generateAndExecuteSql(question, table, allowedTables, sink, ctx);
            if (result != null && !result.isEmpty()) {
                dataContext.append(result).append("\n");
            }
        }

        return dataContext.toString().trim();
    }

    /**
     * 数据查询路由判断：判断用户问题是否需要查询这些结构化数据表。
     * <p>结合用户已有文档描述与数据表的业务描述，用快速模型做一次轻量分类，
     * 要求"问题主题与数据表领域相关"才查表，避免"团建预算 vs 直播明细"这类
     * 领域不匹配的问题也去执行 Text2SQL（浪费 token 且可能查全表混入无关数据）。</p>
     *
     * @param question     用户问题
     * @param queryTables  候选数据表信息
     * @param docSearchDocs 用户已有的文档检索类文档（用于判断问题主题归属）
     * @param sink         响应流信号发射器
     * @param ctx          流式会话上下文
     * @return true 需要查询数据表；false 无需查询（跳过 Text2SQL）
     */
    private boolean shouldQueryDataTables(String question, List<QueryTableInfo> queryTables,
                                          List<KnowledgeDocument> docSearchDocs,
                                          Sinks.Many<String> sink, ChatStreamContext ctx) {
        try {
            emit(sink, ctx.hasSentFinalResult, "\n🧭 正在判断问题是否需要查询数据表...\n", AgentResponse.TYPE_THINKING);
            String promptContent = agentPromptService.getPromptContent(AgentType.RAG, PromptKey.DATA_QUERY_GATE);
            if (promptContent == null) {
                promptContent = RagAgentPrompts.DATA_QUERY_GATE;
            }
            // 携带用户已有文档描述 + 数据表业务描述一起判断（文档描述用于判断问题主题与表的领域是否相关）
            StringBuilder tablesDesc = new StringBuilder();
            for (QueryTableInfo t : queryTables) {
                tablesDesc.append("- 表名: ").append(t.tableName())
                        .append("，描述: ").append(t.description() != null ? t.description() : t.docTitle())
                        .append("\n");
            }
            StringBuilder docsDesc = new StringBuilder();
            if (docSearchDocs != null) {
                for (KnowledgeDocument d : docSearchDocs) {
                    String desc = d.getDescription() != null && !d.getDescription().isEmpty()
                            ? d.getDescription() : d.getDocTitle();
                    docsDesc.append("- ").append(d.getDocTitle()).append("（").append(desc).append("）\n");
                }
            }
            String userMessage = "## 用户已有的文档\n" + docsDesc
                    + "\n## 数据表信息\n" + tablesDesc
                    + "\n## 用户问题\n" + question;
            String response = callFastModel(promptContent, userMessage, ctx);
            if (StringUtils.hasText(response)) {
                response = response.trim();
                if (response.startsWith("```")) {
                    response = response.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
                }
                JSONObject gateResult = JSON.parseObject(response);
                boolean needsDataQuery = gateResult != null && Boolean.TRUE.equals(gateResult.getBoolean("needsDataQuery"));
                if (needsDataQuery) {
                    emit(sink, ctx.hasSentFinalResult, "✅ 判断需要查询数据表\n", AgentResponse.TYPE_THINKING);
                } else {
                    String reason = (gateResult != null && StringUtils.hasText(gateResult.getString("reason")))
                            ? gateResult.getString("reason")
                            : "问题与数据表无关";
                    emit(sink, ctx.hasSentFinalResult, "⏭️ 判断无需查询数据表（" + reason + "），跳过数据查询\n", AgentResponse.TYPE_THINKING);
                }
                return needsDataQuery;
            }
        } catch (Exception e) {
            log.warn("数据查询路由判断失败: {}", e.getMessage());
        }
        // 判断失败（模型未返回/解析异常）时保守返回 true，避免漏掉真实的数据查询需求
        return true;
    }

    /**
     * 调用快速模型（qwen-turbo）提取关键词
     * 从用户问题中提取3-5个核心关键词用于BM25检索
     *
     * @param question 用户原始问题
     * @param sink     响应流信号发射器
     * @param ctx      流式会话上下文
     * @return 关键词列表，提取失败时回退为按空格分词
     */
    private List<String> extractKeywords(String question, Sinks.Many<String> sink, ChatStreamContext ctx) {
        try {
            String promptContent = agentPromptService.getPromptContent(AgentType.RAG, PromptKey.KEYWORD_EXTRACTION);
            if (promptContent == null) {
                promptContent = RagAgentPrompts.KEYWORD_EXTRACTION;
            }
            String response = callFastModel(promptContent, question, ctx);
            if (StringUtils.hasText(response)) {
                // 清理可能的markdown代码块包裹
                response = response.trim();
                if (response.startsWith("```")) {
                    response = response.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
                }
                List<String> keywords = JSON.parseArray(response, String.class);
                if (keywords != null && !keywords.isEmpty()) {
                    // 过滤空关键词
                    keywords = keywords.stream()
                            .filter(StringUtils::hasText)
                            .map(String::trim)
                            .collect(Collectors.toList());
                    if (!keywords.isEmpty()) {
                        return keywords;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("关键词提取失败: {}", e.getMessage());
        }
        // 回退：按空格分词
        return Arrays.asList(question.split("\\s+"));
    }

    /**
     * 调用快速模型（qwen-turbo）重写查询
     * 将用户原始问题重写为更适合知识库向量检索的查询语句
     *
     * @param question 用户原始问题
     * @param sink     响应流信号发射器
     * @param ctx      流式会话上下文
     * @return 重写后的查询语句，重写失败时返回原始问题
     */
    private String rewriteQuery(String question, Sinks.Many<String> sink, ChatStreamContext ctx) {
        try {
            String promptContent = agentPromptService.getPromptContent(AgentType.RAG, PromptKey.QUERY_REWRITE);
            if (promptContent == null) {
                promptContent = RagAgentPrompts.QUERY_REWRITE;
            }
            String response = callFastModel(promptContent, question, ctx);
            if (StringUtils.hasText(response)) {
                return response.trim();
            }
        } catch (Exception e) {
            log.warn("查询重写失败: {}", e.getMessage());
        }
        return question;
    }

    /**
     * 调用快速模型（qwen-turbo）识别问题意图
     * 分析用户问题的意图类型（事实查询、操作指南、分析型、故障排查、通用对话）
     *
     * @param question 用户原始问题
     * @param sink     响应流信号发射器
     * @param ctx      流式会话上下文
     * @return 包含intent和description的JSON对象，识别失败时返回默认意图
     */
    private JSONObject recognizeIntent(String question, Sinks.Many<String> sink, ChatStreamContext ctx) {
        try {
            String promptContent = agentPromptService.getPromptContent(AgentType.RAG, PromptKey.INTENT_RECOGNITION);
            if (promptContent == null) {
                promptContent = RagAgentPrompts.INTENT_RECOGNITION;
            }
            String response = callFastModel(promptContent, question, ctx);
            if (StringUtils.hasText(response)) {
                response = response.trim();
                if (response.startsWith("```")) {
                    response = response.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
                }
                return JSON.parseObject(response);
            }
        } catch (Exception e) {
            log.warn("意图识别失败: {}", e.getMessage());
        }
        // 回退：默认意图
        JSONObject fallback = new JSONObject();
        fallback.put("intent", "FACTUAL_QUERY");
        fallback.put("description", "事实查询");
        return fallback;
    }

    /**
     * 查询用户拥有的知识库文档列表
     * 通过knowledge_document表的user_id字段过滤，实现用户级别的知识库隔离
     *
     * @param userId 用户ID
     * @return 文档列表，无文档时返回空列表
     */
    private List<KnowledgeDocument> getUserDocuments(String userId) {
        QueryWrapper<KnowledgeDocument> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        return knowledgeDocumentService.list(wrapper);
    }

    /**
     * 向量语义检索
     * 跨所有知识库（GENERAL、PRODUCT、TECH）进行向量相似度检索，
     * 然后按用户文档ID进行后过滤，仅保留属于当前用户文档的检索结果
     *
     * @param query      重写后的查询语句
     * @param userDocIds 用户文档ID集合
     * @param sink       响应流信号发射器
     * @param ctx        流式会话上下文
     * @return 检索结果列表，按相似度分数降序排列
     */
    private List<RetrievalResult> vectorSearch(String query, Set<Long> userDocIds,
                                               Sinks.Many<String> sink, ChatStreamContext ctx) {
        List<RetrievalResult> results = new ArrayList<>();
        if (!StringUtils.hasText(query) || userDocIds.isEmpty()) {
            return results;
        }
        // 跨所有知识库检索
        for (KnowledgeBase kb : KnowledgeBase.values()) {
            try {
                VectorStore store = vectorStoreRouter.route(kb);
                SearchRequest request = SearchRequest.builder()
                        .query(query)
                        .topK(vectorTopK)
                        .similarityThreshold(0.5)
                        .build();
                List<Document> docs = store.similaritySearch(request);
                for (Document doc : docs) {
                    // 后过滤：仅保留属于当前用户文档的检索结果
                    Object docIdObj = doc.getMetadata().get(MetadataKeyConstant.DOC_ID);
                    Long docId = null;
                    if (docIdObj != null) {
                        try {
                            // Milvus 返回的数值型 metadata 为 Double（如 "123.0"），
                            // Long.parseLong 无法解析带小数点的字符串，改用 BigDecimal 兼容整数/浮点/字符串格式
                            docId = new BigDecimal(docIdObj.toString()).longValue();
                        } catch (NumberFormatException e) {
                            // docId格式异常，跳过
                            log.warn("文档ID格式异常: {}", docIdObj);
                        }
                    }
                    if (docId == null || !userDocIds.contains(docId)) {
                        continue;
                    }
                    String chunkId = getMetadataString(doc, MetadataKeyConstant.CHUNK_ID);
                    String fileName = getMetadataString(doc, MetadataKeyConstant.FILE_NAME);
                    String url = getMetadataString(doc, MetadataKeyConstant.URL);
                    // 获取相似度分数
                    double score = 0.0;
                    Object distance = doc.getMetadata().get("distance");
                    if (distance != null) {
                        try {
                            score = Double.parseDouble(distance.toString());
                        } catch (NumberFormatException e) {
                            // 分数解析异常，默认0
                        }
                    }
                    results.add(new RetrievalResult(doc.getText(), chunkId, docId, fileName, url, score, "vector"));
                }
            } catch (Exception e) {
                log.warn("向量检索知识库 [{}] 失败: {}", kb.getDescription(), e.getMessage());
            }
        }
        // 按分数降序排列
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    /**
     * BM25关键词检索
     * 通过MySQL LIKE查询匹配包含关键词的知识片段，然后在Java端计算BM25分数
     * BM25参数：k1=1.2（词频饱和控制），b=0.75（文档长度归一化）
     *
     * @param keywords   关键词列表
     * @param userDocIds  用户文档ID集合
     * @param sink       响应流信号发射器
     * @param ctx        流式会话上下文
     * @return 检索结果列表，按BM25分数降序排列，最多返回bm25TopK条
     */
    private List<RetrievalResult> bm25Search(List<String> keywords, Set<Long> userDocIds,
                                             Sinks.Many<String> sink, ChatStreamContext ctx) {
        List<RetrievalResult> results = new ArrayList<>();
        if (keywords.isEmpty() || userDocIds.isEmpty()) {
            return results;
        }
        try {
            // 查询属于用户文档且包含任一关键词的分段
            QueryWrapper<KnowledgeSegment> wrapper = new QueryWrapper<>();
            wrapper.in("document_id", userDocIds)
                    .and(w -> {
                        for (int i = 0; i < keywords.size(); i++) {
                            if (i > 0) {
                                w.or();
                            }
                            // LIKE 参数化 + 通配符转义（% _ \），避免关键词中的特殊字符被当作通配符
                            w.apply("`text` LIKE {0} ESCAPE '\\\\'", "%" + escapeLikeKeyword(keywords.get(i)) + "%");
                        }
                    });
            List<KnowledgeSegment> segments = knowledgeSegmentService.list(wrapper);

            if (segments.isEmpty()) {
                return results;
            }

            // BM25评分计算
            int N = segments.size();
            // 统计每个关键词的文档频率（df）和总文档长度
            double totalLength = 0;
            Map<String, Integer> docFreqMap = new HashMap<>();
            for (KnowledgeSegment seg : segments) {
                String text = seg.getText();
                if (text != null) {
                    totalLength += text.length();
                    for (String kw : keywords) {
                        if (text.contains(kw)) {
                            docFreqMap.merge(kw, 1, Integer::sum);
                        }
                    }
                }
            }
            double avgDocLength = N > 0 ? totalLength / N : 1.0;

            for (KnowledgeSegment seg : segments) {
                String text = seg.getText();
                if (text == null || text.isEmpty()) {
                    continue;
                }
                int docLength = text.length();
                double score = 0.0;
                for (String kw : keywords) {
                    int tf = countOccurrences(text, kw);
                    if (tf == 0) {
                        continue;
                    }
                    int df = docFreqMap.getOrDefault(kw, 0);
                    // IDF公式：log((N - df + 0.5) / (df + 0.5) + 1)
                    double idf = Math.log((double) (N - df + 0.5) / (df + 0.5) + 1.0);
                    // TF归一化公式：tf * (k1 + 1) / (tf + k1 * (1 - b + b * dl/avgdl))
                    double tfNorm = (tf * (BM25_K1 + 1)) /
                            (tf + BM25_K1 * (1 - BM25_B + BM25_B * (docLength / avgDocLength)));
                    score += idf * tfNorm;
                }
                // 从分段元数据中提取文件名和URL
                String fileName = null;
                String url = null;
                String chunkId = seg.getChunkId();
                if (seg.getMetadata() != null) {
                    try {
                        JSONObject meta = JSON.parseObject(seg.getMetadata());
                        fileName = meta.getString(MetadataKeyConstant.FILE_NAME);
                        url = meta.getString(MetadataKeyConstant.URL);
                        if (chunkId == null) {
                            chunkId = meta.getString(MetadataKeyConstant.CHUNK_ID);
                        }
                    } catch (Exception e) {
                        // 元数据解析异常，跳过
                    }
                }
                results.add(new RetrievalResult(text, chunkId, seg.getDocumentId(), fileName, url, score, "bm25"));
            }

            // 按BM25分数降序排列
            results.sort((a, b) -> Double.compare(b.score(), a.score()));
            // 最多返回bm25TopK条
            if (results.size() > bm25TopK) {
                results = new ArrayList<>(results.subList(0, bm25TopK));
            }
        } catch (Exception e) {
            log.warn("BM25检索失败: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 结果去重与RRF（Reciprocal Rank Fusion）融合
     * 以chunkId（或内容哈希）作为去重键，对向量检索和BM25检索结果进行合并去重，
     * 并通过RRF算法计算融合分数：score = Σ 1/(k + rank)，最终按融合分数降序排列
     *
     * @param vectorResults 向量检索结果
     * @param bm25Results    BM25检索结果
     * @return 融合去重后的结果列表，最多返回fusedTopK条
     */
    private List<RetrievalResult> deduplicateAndRRFFusion(List<RetrievalResult> vectorResults,
                                                           List<RetrievalResult> bm25Results) {
        // 以chunkId（或内容哈希）作为去重键
        Map<String, RetrievalResult> dedupMap = new LinkedHashMap<>();
        Map<String, Double> scoreMap = new HashMap<>();

        // 融合向量检索结果，RRF分数 = 1/(k + rank)
        for (int i = 0; i < vectorResults.size(); i++) {
            RetrievalResult r = vectorResults.get(i);
            String key = getDedupKey(r);
            double rrfScore = 1.0 / (RRF_K + i + 1);
            scoreMap.merge(key, rrfScore, Double::sum);
            if (!dedupMap.containsKey(key)) {
                dedupMap.put(key, r);
            }
        }

        // 融合BM25检索结果，RRF分数 = 1/(k + rank)
        for (int i = 0; i < bm25Results.size(); i++) {
            RetrievalResult r = bm25Results.get(i);
            String key = getDedupKey(r);
            double rrfScore = 1.0 / (RRF_K + i + 1);
            scoreMap.merge(key, rrfScore, Double::sum);
            if (!dedupMap.containsKey(key)) {
                dedupMap.put(key, r);
            }
        }

        // 按RRF融合分数降序排列
        List<RetrievalResult> fused = new ArrayList<>(dedupMap.values());
        fused.sort((a, b) -> Double.compare(
                scoreMap.getOrDefault(getDedupKey(b), 0.0),
                scoreMap.getOrDefault(getDedupKey(a), 0.0)
        ));

        // 最多返回fusedTopK条
        if (fused.size() > fusedTopK) {
            fused = new ArrayList<>(fused.subList(0, fusedTopK));
        }
        return fused;
    }

    /**
     * 获取检索结果的去重键
     * 优先使用chunkId，其次使用内容哈希
     *
     * @param r 检索结果
     * @return 去重键字符串
     */
    private String getDedupKey(RetrievalResult r) {
        if (r.chunkId() != null && !r.chunkId().isEmpty()) {
            return r.chunkId();
        }
        return Integer.toHexString(r.content().hashCode());
    }

    /**
     * 统计关键词在文本中的出现次数（词频）
     *
     * @param text    文本内容
     * @param keyword 关键词
     * @return 出现次数
     */
    private int countOccurrences(String text, String keyword) {
        if (text == null || keyword == null || keyword.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }

    /**
     * 构建上下文字符串
     * 将融合后的检索结果格式化为可注入到Prompt的上下文
     *
     * @param results 融合后的检索结果列表
     * @return 格式化的上下文字符串
     */
    private String buildContext(List<RetrievalResult> results) {
        if (results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult r = results.get(i);
            sb.append("[来源").append(i + 1).append(": ");
            if (r.fileName() != null && !r.fileName().isEmpty()) {
                sb.append(r.fileName());
            } else {
                sb.append("未知文档");
            }
            if (r.url() != null && !r.url().isEmpty()) {
                sb.append(" | 链接: ").append(r.url());
            }
            sb.append("]\n").append(r.content()).append("\n");
            if (i < results.size() - 1) {
                sb.append("---\n");
            }
        }
        return sb.toString();
    }

    /**
     * 从Document元数据中安全获取字符串值
     *
     * @param doc Document对象
     * @param key 元数据键
     * @return 字符串值，不存在时返回null
     */
    private String getMetadataString(Document doc, String key) {
        Object value = doc.getMetadata().get(key);
        return value == null ? null : value.toString();
    }

    // ==================== Text2SQL 核心方法 ====================

    /**
     * 为指定数据表生成并执行 SQL 查询，带安全校验和结果校验，最多重试 MAX_SQL_RETRY 次
     * 流程：生成 SQL → 安全校验 → 执行查询 → 结果校验 → 不合格则重试
     *
     * @param question 用户原始问题
     * @param table    查询表信息（包含表名、描述、列信息）
     * @param sink     响应流信号发射器
     * @param ctx      流式会话上下文
     * @return 格式化的查询结果上下文，全部重试失败时返回 null
     */
    private String generateAndExecuteSql(String question, QueryTableInfo table, Set<String> allowedTables,
                                         Sinks.Many<String> sink, ChatStreamContext ctx) {
        String schemaDescription = buildSchemaDescription(table);
        // 采样真实数据行（前 3 行）注入 schema，让 LLM 直接看到各列的值格式，无需猜测
        List<Map<String, Object>> sampleRows = fetchSampleRows(table.tableName());
        if (!sampleRows.isEmpty()) {
            schemaDescription = schemaDescription
                    + "\n表数据样例(前" + sampleRows.size() + "行):\n"
                    + formatSampleRows(sampleRows);
        }
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_SQL_RETRY; attempt++) {
            try {
                // 生成或修正 SQL
                String sql;
                if (attempt == 1) {
                    // 首次：根据表结构和用户问题生成 SQL
                    sql = generateSql(question, schemaDescription, ctx);
                    emit(sink, ctx.hasSentFinalResult,
                            "📝 第" + attempt + "次生成SQL: " + sql + "\n", AgentResponse.TYPE_THINKING);
                    // 兜底：LLM 首次即认为问题与表无关时，通常返回不含 FROM 的"声明式"SQL
                    // （如 SELECT '...' AS notice）。此时不执行、不重试，直接判定无需查表，
                    // 避免重试后变成 SELECT * FROM 全表查询。
                    if (!containsFromClause(sql)) {
                        emit(sink, ctx.hasSentFinalResult,
                                "⏭️ 模型判断该问题与数据表无关，跳过数据查询\n", AgentResponse.TYPE_THINKING);
                        return null;
                    }
                } else {
                    // 重试：带上一次错误信息重新生成
                    sql = regenerateSql(question, schemaDescription, lastError, ctx);
                    emit(sink, ctx.hasSentFinalResult,
                            "🔄 第" + attempt + "次重新生成SQL: " + sql + "\n", AgentResponse.TYPE_THINKING);
                }

                // 安全校验：确保是只读 SELECT 语句，且只允许查询当前用户文档对应的物理表
                String validationError = validateSqlSafety(sql, allowedTables);
                if (validationError != null) {
                    emit(sink, ctx.hasSentFinalResult,
                            "⚠️ SQL安全校验未通过: " + validationError + "\n", AgentResponse.TYPE_THINKING);
                    log.warn("Text2SQL 安全校验未通过: {}", validationError);
                    // 安全违规（非 SELECT 语句）直接终止，不重试
                    if (validationError.contains("禁止")) {
                        return null;
                    }
                    lastError = validationError;
                    continue;
                }

                // 执行 SQL 查询
                emit(sink, ctx.hasSentFinalResult, "⚡ 正在执行查询...\n", AgentResponse.TYPE_THINKING);
                List<Map<String, Object>> results = filterValidRows(tableMetaMapper.executeQuery(sql));

                // 结果校验
                if (results.isEmpty()) {
                    emit(sink, ctx.hasSentFinalResult,
                            "⚠️ 第" + attempt + "次查询结果为空\n", AgentResponse.TYPE_THINKING);
                    lastError = "查询结果为空（WHERE 条件未匹配到任何行）。请重新对照列映射表的【含义】和【示例值】："
                            + "确认数字ID、人名、日期等条件是否用对了列（不要把ID写到名称列），"
                            + "日期格式是否与示例值一致（示例为 2026/3/17 时按月过滤应写 LIKE '2026/5/%'）";
                    continue;
                }

                // 调用 LLM 校验结果是否有效回答了用户问题
                boolean resultValid = checkSqlResults(question, results, ctx);
                if (!resultValid && attempt < MAX_SQL_RETRY) {
                    emit(sink, ctx.hasSentFinalResult,
                            "⚠️ 第" + attempt + "次查询结果未能有效回答问题，尝试重新生成...\n",
                            AgentResponse.TYPE_THINKING);
                    lastError = "查询结果未能有效回答用户问题。请对照列映射表的【含义】和【示例值】检查是否选错了列或统计口径"
                            + "（例如“销量/卖了多少”应选单量/数量类列，而不是金额列）";
                    continue;
                }

                // 查询成功，格式化结果
                emit(sink, ctx.hasSentFinalResult,
                        "✅ 查询成功，获取到 " + results.size() + " 条数据\n", AgentResponse.TYPE_THINKING);
                return buildDataQueryContext(table, sql, results);

            } catch (Exception e) {
                lastError = e.getMessage();
                emit(sink, ctx.hasSentFinalResult,
                        "❌ 第" + attempt + "次SQL执行失败: " + lastError + "\n", AgentResponse.TYPE_THINKING);
                log.warn("Text2SQL 第{}次执行失败: {}", attempt, lastError);
            }
        }

        emit(sink, ctx.hasSentFinalResult,
                "⚠️ 表 " + table.tableName() + " 经过" + MAX_SQL_RETRY + "次尝试仍无法获取有效结果\n",
                AgentResponse.TYPE_THINKING);
        return null;
    }

    /**
     * 过滤查询结果中的无效行，返回只含有有效数据的行列表（永不返回 null）。
     * <p>
     * 由于 MyBatis 默认 callSettersOnNulls=false，当聚合查询（如 SUM/COUNT）因
     * WHERE 条件未匹配到任何记录而返回全 NULL 的一行时，映射出的 Map 元素会变成
     * null（而非空 Map），进而导致后续 {@code results.get(0).keySet()} 抛 NPE。
     * 此处把 null 行与所有列值均为 null 的“空行”一并剔除，使结果能正确走“查询为空”分支。
     *
     * @param rawResults 原始查询结果（可能为 null 或含 null 行）
     * @return 过滤后的有效数据行列表
     */
    private List<Map<String, Object>> filterValidRows(List<Map<String, Object>> rawResults) {
        List<Map<String, Object>> valid = new ArrayList<>();
        if (rawResults == null) {
            return valid;
        }
        for (Map<String, Object> row : rawResults) {
            if (row == null) {
                continue;
            }
            boolean hasValue = false;
            for (Object value : row.values()) {
                if (value != null) {
                    hasValue = true;
                    break;
                }
            }
            if (hasValue) {
                valid.add(row);
            }
        }
        return valid;
    }

    /**
     * 首次调用 LLM 根据用户问题和表结构生成 SELECT SQL
     *
     * @param question         用户问题
     * @param schemaDescription 表结构描述（DDL + 列信息 + 表描述）
     * @param ctx              流式会话上下文
     * @return 生成的 SQL 语句
     */
    private String generateSql(String question, String schemaDescription, ChatStreamContext ctx) {
        String promptContent = agentPromptService.getPromptContent(AgentType.RAG, PromptKey.TEXT2SQL_GENERATE);
        if (promptContent == null) {
            throw new RuntimeException("缺少 Text2SQL 生成提示词，请先执行 rag_prompt_init.sql 初始化 agent_prompt 表");
        }
        String userMessage = "## 表结构信息\n" + schemaDescription + "\n\n## 用户问题\n" + question;
        String sql = callFastModel(promptContent, userMessage, ctx);
        if (sql == null) {
            throw new RuntimeException("LLM 未返回 SQL");
        }
        return cleanSqlOutput(sql);
    }

    /**
     * 重试时调用 LLM，带上一次错误信息重新生成修正后的 SQL
     *
     * @param question         用户问题
     * @param schemaDescription 表结构描述
     * @param lastError        上次执行失败的错误信息
     * @param ctx              流式会话上下文
     * @return 修正后的 SQL 语句
     */
    private String regenerateSql(String question, String schemaDescription,
                                  String lastError, ChatStreamContext ctx) {
        String promptContent = agentPromptService.getPromptContent(AgentType.RAG, PromptKey.TEXT2SQL_VALIDATE);
        if (promptContent == null) {
            throw new RuntimeException("缺少 Text2SQL 校验反馈提示词，请先执行 rag_prompt_init.sql 初始化 agent_prompt 表");
        }
        String userMessage = "## 表结构信息\n" + schemaDescription
                + "\n\n## 用户问题\n" + question
                + "\n\n## 上次错误信息\n" + lastError;
        String sql = callFastModel(promptContent, userMessage, ctx);
        if (sql == null) {
            throw new RuntimeException("LLM 未返回修正后的 SQL");
        }
        return cleanSqlOutput(sql);
    }

    /**
     * 调用 LLM 校验 SQL 查询结果是否能有效回答用户问题
     *
     * @param question 用户原始问题
     * @param results  SQL 查询结果
     * @param ctx      流式会话上下文
     * @return true 表示结果有效，false 表示结果不相关
     */
    private boolean checkSqlResults(String question, List<Map<String, Object>> results, ChatStreamContext ctx) {
        try {
            String promptContent = agentPromptService.getPromptContent(AgentType.RAG, PromptKey.TEXT2SQL_CHECK);
            if (promptContent == null) {
                throw new RuntimeException("缺少 Text2SQL 结果校验提示词，请先执行 rag_prompt_init.sql 初始化 agent_prompt 表");
            }
            // 构建结果摘要（最多取前 5 行作为采样，跳过 null 行）
            StringBuilder sampleBuilder = new StringBuilder();
            int sampleSize = Math.min(results.size(), 5);
            for (int i = 0; i < sampleSize; i++) {
                Map<String, Object> row = results.get(i);
                sampleBuilder.append("行").append(i + 1).append(": ")
                        .append(row == null ? "null" : row).append("\n");
            }
            if (results.size() > 5) {
                sampleBuilder.append("... 共 ").append(results.size()).append(" 行\n");
            }
            String userMessage = "## 用户问题\n" + question
                    + "\n\n## SQL 查询结果（采样）\n" + sampleBuilder;
            String response = callFastModel(promptContent, userMessage, ctx);
            if (response != null) {
                response = response.trim();
                if (response.startsWith("```")) {
                    response = response.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
                }
                JSONObject checkResult = JSON.parseObject(response);
                return checkResult != null && Boolean.TRUE.equals(checkResult.getBoolean("valid"));
            }
        } catch (Exception e) {
            log.warn("Text2SQL 结果校验失败，默认视为有效: {}", e.getMessage());
        }
        // 校验失败时默认认为结果有效（避免不必要的重试）
        return true;
    }

    /**
     * 清理 LLM 输出的 SQL：去除 markdown 代码块标记、首尾空白和末尾分号
     *
     * @param sql LLM 原始输出
     * @return 清理后的纯 SQL 语句
     */
    private String cleanSqlOutput(String sql) {
        sql = sql.trim();
        // 去除 markdown 代码块标记
        if (sql.startsWith("```")) {
            sql = sql.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
        }
        // 去除末尾分号
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    /**
     * SQL 安全校验：确保只允许 SELECT 查询，禁止任何数据修改操作。
     * 校验基于"去注释后的 SQL"（防止行注释 -- / # 与块注释绕过关键词与表名校验），
     * 并校验 FROM/JOIN 引用的表名必须属于当前用户文档允许的白名单。
     *
     * @param sql           待校验的 SQL 语句
     * @param allowedTables 允许查询的物理表名集合（小写）
     * @return null 表示校验通过，非 null 返回错误描述
     */
    private String validateSqlSafety(String sql, Set<String> allowedTables) {
        if (sql == null || sql.trim().isEmpty()) {
            return "SQL 为空";
        }
        // 0. 去除 SQL 注释，防止通过注释绕过后续关键词/表名检查（如 IN/**/TO、SELE/**/CT）
        String normalizedSql = stripSqlComments(sql).trim();
        if (normalizedSql.isEmpty()) {
            return "SQL 为空";
        }

        // 1. 首关键词必须是 SELECT（不区分大小写）
        String upperSql = normalizedSql.toUpperCase();
        if (!upperSql.startsWith("SELECT")) {
            return "SQL 不是 SELECT 语句，禁止执行非查询操作";
        }

        // 2. 禁止多语句（包含未转义的分号分隔符）
        if (normalizedSql.contains(";")) {
            return "禁止执行多条 SQL 语句";
        }

        // 3. 禁止数据修改关键词（仅检查 SQL 主体中的独立关键词，避免误匹配列名如 drop_rate）
        String[] dangerousKeywords = {
                "INSERT ", "UPDATE ", "DELETE ", "DROP ", "ALTER ", "TRUNCATE ",
                "CREATE ", "GRANT ", "REVOKE ", "REPLACE ", "RENAME ", "LOCK "
        };
        // 在 SQL 的 FROM/WHERE 等子句部分检查（跳过首词 SELECT）
        String sqlBody = upperSql.substring(6); // 跳过 "SELECT"
        for (String keyword : dangerousKeywords) {
            // 使用空格或开头匹配，避免 "UPDATE" 匹配到 "UPDATED_AT" 等列名
            if (sqlBody.contains(" " + keyword) || sqlBody.startsWith(keyword)) {
                return "SQL 包含禁止的数据修改操作: " + keyword.trim();
            }
        }

        // 4. 禁止危险函数 / 文件读写
        String[] dangerousFunctions = {
                "INTO OUTFILE", "INTO DUMPFILE", "LOAD_FILE", "BENCHMARK",
                "SLEEP", "PG_SLEEP", "INTO"
        };
        for (String func : dangerousFunctions) {
            if (upperSql.contains(func)) {
                return "SQL 包含禁止的危险函数或语句: " + func;
            }
        }

        // 5. 表名白名单：FROM/JOIN 引用的表必须属于当前用户文档的物理表
        if (allowedTables != null && !allowedTables.isEmpty()) {
            Set<String> referencedTables = extractTableNames(normalizedSql);
            for (String table : referencedTables) {
                if (!allowedTables.contains(table)) {
                    return "SQL 引用了未授权的表: " + table + "（仅允许查询当前知识库对应的数据表）";
                }
            }
        }

        return null; // 校验通过
    }

    /**
     * 去除 SQL 中的注释（块注释、行注释 -- 与 #），防止注释绕过关键词/表名校验
     */
    private String stripSqlComments(String sql) {
        String s = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("--[^\\r\\n]*", " ");
        s = s.replaceAll("#[^\\r\\n]*", " ");
        return s;
    }

    /**
     * 提取 SQL 中 FROM/JOIN 引用的表名（支持反引号包裹），返回小写集合
     */
    private Set<String> extractTableNames(String sql) {
        Set<String> tables = new HashSet<>();
        Matcher m = Pattern.compile("(?:FROM|JOIN)\\s+`?([A-Za-z0-9_]+)`?",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        while (m.find()) {
            tables.add(m.group(1).toLowerCase());
        }
        return tables;
    }

    /**
     * 判断 SQL 是否包含 FROM 子句。
     * 真正的数据查询必然包含 FROM；若 LLM 返回的 SQL 不含 FROM（如 SELECT '...' AS notice），
     * 说明其认为问题与表无关，应跳过查表。
     */
    private boolean containsFromClause(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        return Pattern.compile("\\bFROM\\b", Pattern.CASE_INSENSITIVE).matcher(sql).find();
    }

    /**
     * 转义 LIKE 关键字中的通配符（% _ \），避免 BM25 检索时被当作通配符
     */
    private String escapeLikeKeyword(String keyword) {
        return keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * 将私有 MinIO URL 转换为预签名公开 URL（用于前端展示参考来源），失败时原样返回
     */
    private String toPublicUrl(String url) {
        if (fileStorageService == null || url == null || url.isBlank()) {
            return url;
        }
        return fileStorageService.toPublicUrl(url);
    }

    /**
     * 构建表结构描述字符串，用于 LLM 生成 SQL。
     * 动态解析 columns_info 渲染成"物理列 → 含义 → 类型 → 示例值"的映射表，
     * 并附上建表 DDL（含 COMMENT 语义）作为对照。适用于任意 DATA_QUERY 表，不写死任何字段。
     *
     * @param table 查询表信息
     * @return 表结构描述文本
     */
    private String buildSchemaDescription(QueryTableInfo table) {
        StringBuilder sb = new StringBuilder();
        sb.append("表名: `").append(table.tableName()).append("`\n");
        if (table.description() != null && !table.description().isEmpty()) {
            sb.append("表描述: ").append(table.description()).append("\n");
        }
        // 列映射表：物理列名通常无语义（如 col_1），必须依据【含义】和【示例值】选列
        String columnMapping = renderColumnMapping(table.columnsInfoJson());
        if (StringUtils.hasText(columnMapping)) {
            sb.append("列映射:\n").append(columnMapping).append("\n");
        }
        if (table.createSql() != null) {
            sb.append("建表语句(DDL):\n").append(table.createSql()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 解析 columns_info JSON，渲染成 markdown 列映射表。
     * 兼容新旧两种字段：新字段 inferredType/formatHint/sampleValues，旧字段仅 columnName/originalHeader/dataType。
     *
     * @param columnsInfoJson 列信息 JSON 字符串
     * @return markdown 表格；解析失败或为空时返回空串
     */
    private String renderColumnMapping(String columnsInfoJson) {
        if (!StringUtils.hasText(columnsInfoJson)) {
            return "";
        }
        try {
            List<JSONObject> cols = JSON.parseArray(columnsInfoJson, JSONObject.class);
            if (cols == null || cols.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("| 物理列名 | 含义 | 类型 | 示例值 |\n");
            sb.append("| --- | --- | --- | --- |\n");
            for (JSONObject col : cols) {
                if (col == null) {
                    continue;
                }
                String columnName = col.getString("columnName");
                String meaning = col.getString("originalHeader");
                String type = col.getString("inferredType");
                if (!StringUtils.hasText(type)) {
                    type = col.getString("dataType");
                }
                String formatHint = col.getString("formatHint");
                String typeStr = StringUtils.hasText(type) ? type : "TEXT";
                if (StringUtils.hasText(formatHint)) {
                    typeStr = typeStr + "(" + formatHint + ")";
                }
                sb.append("| ").append(columnName == null ? "" : columnName)
                        .append(" | ").append(meaning == null ? "" : meaning)
                        .append(" | ").append(typeStr)
                        .append(" | ").append(formatSamples(col.get("sampleValues")))
                        .append(" |\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("解析列信息 JSON 失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 将采样示例值（List/JSONArray）格式化为逗号分隔字符串
     */
    private String formatSamples(Object samplesObj) {
        if (!(samplesObj instanceof java.util.List<?> list)) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) {
                out.add(o.toString());
            }
        }
        return String.join(", ", out);
    }

    /**
     * 采样表的前 3 行真实数据，用于让 LLM 了解各列的值格式（日期、ID、名称等）。
     * 失败时返回空列表（不阻断 Text2SQL 流程）。
     */
    private List<Map<String, Object>> fetchSampleRows(String tableName) {
        try {
            List<Map<String, Object>> rows = tableMetaMapper.executeQuery(
                    "SELECT * FROM `" + tableName + "` LIMIT 3");
            if (rows == null) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> valid = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                if (row != null) {
                    valid.add(row);
                }
            }
            return valid;
        } catch (Exception e) {
            log.warn("采样表 {} 数据失败: {}", tableName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 将采样行格式化为 markdown 表格（过滤系统字段，截断长值）
     */
    private String formatSampleRows(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        Set<String> skip = Set.of("id", "created_at", "updated_at");
        List<String> headers = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r == null) {
                continue;
            }
            for (String key : r.keySet()) {
                if (!skip.contains(key.toLowerCase()) && !headers.contains(key)) {
                    headers.add(key);
                }
            }
            if (!headers.isEmpty()) {
                break;
            }
        }
        if (headers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("| ").append(headers.stream().map(h -> "---").collect(Collectors.joining(" | "))).append(" |\n");
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            sb.append("| ");
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                Object value = row.get(headers.get(i));
                String valueStr = value == null ? "" : value.toString();
                if (valueStr.length() > 60) {
                    valueStr = valueStr.substring(0, 60) + "...";
                }
                sb.append(valueStr);
            }
            sb.append(" |\n");
        }
        return sb.toString();
    }

    /**
     * 将 SQL 查询结果格式化为上下文字符串，包含表头、数据表格和 SQL 语句
     *
     * @param table   查询表信息
     * @param sql     执行的 SQL 语句
     * @param results 查询结果（行 → 列名:值 的 Map 列表）
     * @return 格式化的数据查询上下文
     */
    private String buildDataQueryContext(QueryTableInfo table, String sql, List<Map<String, Object>> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("[数据查询: ").append(table.docTitle());
        if (table.description() != null) {
            sb.append(" - ").append(table.description());
        }
        sb.append("]\n");
        sb.append("执行SQL: ").append(sql).append("\n");
        sb.append("查询结果（").append(results.size()).append("条）:\n\n");

        if (!results.isEmpty()) {
            // 收集列名（过滤掉系统字段 id, created_at, updated_at），并跳过 null 行
            Set<String> skipColumns = Set.of("id", "created_at", "updated_at");
            List<String> headers = new ArrayList<>();
            for (Map<String, Object> r : results) {
                if (r == null) {
                    continue;
                }
                for (String key : r.keySet()) {
                    if (!skipColumns.contains(key.toLowerCase()) && !headers.contains(key)) {
                        headers.add(key);
                    }
                }
                if (!headers.isEmpty()) {
                    break;
                }
            }

            if (!headers.isEmpty()) {
                // Markdown 表头
                sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
                sb.append("| ").append(headers.stream().map(h -> "---").collect(Collectors.joining(" | "))).append(" |\n");

                // 数据行（最多展示 20 行，避免上下文过长）
                int maxRows = Math.min(results.size(), 20);
                for (int i = 0; i < maxRows; i++) {
                    Map<String, Object> row = results.get(i);
                    if (row == null) {
                        continue;
                    }
                    sb.append("| ");
                    for (int j = 0; j < headers.size(); j++) {
                        if (j > 0) {
                            sb.append(" | ");
                        }
                        Object value = row.get(headers.get(j));
                        String valueStr = value != null ? value.toString() : "";
                        // 截断过长的值
                        if (valueStr.length() > 100) {
                            valueStr = valueStr.substring(0, 100) + "...";
                        }
                        sb.append(valueStr);
                    }
                    sb.append(" |\n");
                }

                if (results.size() > maxRows) {
                    sb.append("\n... 共 ").append(results.size()).append(" 条记录，仅展示前 ").append(maxRows).append(" 条\n");
                }
            }
        }

        return sb.toString();
    }

    // ==================== 流式回答生成 ====================

    /**
     * 流式生成回答
     * 将检索上下文注入消息列表，使用ChatClient流式生成回答，
     * 解析think标签区分思考内容和文本内容，完成后发送参考来源和推荐问题
     *
     * @param messages       消息列表
     * @param context        检索上下文
     * @param sink           响应流信号发射器
     * @param ctx            流式会话上下文
     * @param conversationId 会话ID
     */
    private void streamAnswer(List<Message> messages, String context,
                              Sinks.Many<String> sink, ChatStreamContext ctx, String conversationId) {
        // 添加RAG回答系统提示词
        String answerPrompt = agentPromptService.getPromptContent(AgentType.RAG, PromptKey.RAG_ANSWER);
        if (answerPrompt == null) {
            answerPrompt = RagAgentPrompts.RAG_ANSWER;
        }
        messages.add(new SystemMessage(answerPrompt));

        // 添加检索到的上下文
        if (StringUtils.hasText(context)) {
            messages.add(new UserMessage("<context>\n" + context + "\n</context>"));
        }

        // 流式生成回答
        Disposable disposable = chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    // 记录 token 使用量（流式 Usage 通常只在最后一个 chunk 出现，用 max 覆盖）
                    ChatTokenUsageUtil.recordUsage(chunk, ctx.promptTokens, ctx.generationTokens);
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                        return;
                    }
                    String text = chunk.getResult().getOutput().getText();
                    if (text != null && !ctx.hasSentFinalResult.get()) {
                        // 解析think标签，区分思考内容和文本内容
                        ThinkTagParser.ParseResult parseResult = ThinkTagParser.parse(text, ctx.inThink);
                        ctx.inThink = parseResult.inThink();
                        for (ThinkTagParser.Segment segment : parseResult.segments()) {
                            if (segment.thinking()) {
                                sink.tryEmitNext(createThinkingResponse(segment.content()));
                            } else {
                                sink.tryEmitNext(createTextResponse(segment.content()));
                                ctx.currentAnswerBuffer.append(segment.content());
                            }
                        }
                    }
                })
                .doOnComplete(() -> finishAnswer(sink, ctx, conversationId, messages))
                .doOnError(error -> {
                    if (!ctx.hasSentFinalResult.get()) {
                        ctx.hasSentFinalResult.set(true);
                        sink.tryEmitError(error);
                    }
                })
                .subscribe();

        // 保存Disposable引用到任务管理器，支持外部取消
        if (conversationId != null && taskManager != null) {
            taskManager.setDisposable(conversationId, disposable);
        }
    }

    /**
     * 完成回答，发送参考来源、推荐问题和流式结束标记
     *
     * @param sink           响应流信号发射器
     * @param ctx            流式会话上下文
     * @param conversationId 会话ID
     * @param messages       消息列表（用于生成推荐问题）
     */
    private void finishAnswer(Sinks.Many<String> sink, ChatStreamContext ctx,
                              String conversationId, List<Message> messages) {
        String finalText = ctx.currentAnswerBuffer.toString();

        // 发送参考来源（知识库检索、关键词检索的相关参考来源）
        if (!ctx.references.isEmpty()) {
            String reference = JSON.toJSONString(ctx.references);
            String referenceJson = createReferenceResponse(reference);
            sink.tryEmitNext(referenceJson);
        }

        //Rag不生成推荐问题
//        // 生成并发送推荐问题
//        if (enableRecommendations) {
//            String recommendations = generateRecommendations(currentQuestion, finalText, messages,
//                    ctx.promptTokens, ctx.generationTokens);
//            if (recommendations != null) {
//                currentRecommendations = recommendations;
//                String recommendJson = createRecommendResponse(recommendations);
//                sink.tryEmitNext(recommendJson);
//            }
//        }

        // 标记已发送最终结果并发送结束标记
        ctx.hasSentFinalResult.set(true);
        sink.tryEmitNext(createCompleteResponse());
        sink.tryEmitComplete();
    }

    // ==================== 联网搜索回退 ====================

    /**
     * 处理 RAG 检索无结果的情况
     * 根据联网搜索回退策略决定：自动联网搜索 / 提示用户确认 / 直接返回未找到提示
     *
     * @param question 用户原始问题
     * @param messages 消息列表
     * @param sink     响应流信号发射器
     * @param ctx      流式会话上下文
     * @param convId   会话ID
     */
    private void handleEmptyRagContext(String question, List<Message> messages,
                                       Sinks.Many<String> sink, ChatStreamContext ctx, String convId) {
        if (enableWebSearchFallback && hasWebSearchTools()) {
            // 前端选了联网搜索：提示用户确认是否联网搜索（Human in the Loop）
            emit(sink, ctx.hasSentFinalResult,
                    "❓ 知识库中未检索到相关内容，是否允许使用联网搜索？\n", AgentResponse.TYPE_THINKING);
            // 发送联网搜索确认提示
            JSONObject promptData = new JSONObject();
            promptData.put("type", AgentResponse.TYPE_WEB_SEARCH_PROMPT);
            promptData.put("content", "知识库中未检索到相关内容，是否允许使用联网搜索来查找答案？");
            promptData.put("data", question);
            sink.tryEmitNext(promptData.toJSONString());
            complete(sink, ctx.hasSentFinalResult);
        } else {
            // 前端未选联网搜索，或无联网搜索工具：直接返回未找到提示
            emit(sink, ctx.hasSentFinalResult,
                    "很抱歉，知识库中未检索到与您的问题相关的内容，无法基于知识库回答。\n\n"
                            + "建议您：\n"
                            + "1. 更换关键词或换一种提问方式\n"
                            + "2. 确认相关文档已上传到知识库\n"
                            + "3. 如需实时资讯、体育赛事等时效性信息，请切换到「联网」或「深度思考」模式",
                    AgentResponse.TYPE_TEXT);
            complete(sink, ctx.hasSentFinalResult);
        }
    }

    /**
     * 判断是否有可用的联网搜索工具
     *
     * @return true表示有可用的搜索工具
     */
    private boolean hasWebSearchTools() {
        return webSearchTools != null && webSearchTools.length > 0;
    }

    /**
     * 完成流式响应，标记结束并发送结束标记
     * 使用CAS确保只发送一次
     *
     * @param sink     响应流信号发射器
     * @param finished 完成标记
     */
    private void complete(Sinks.Many<String> sink, AtomicBoolean finished) {
        if (finished.compareAndSet(false, true)) {
            sink.tryEmitNext(createCompleteResponse());
            sink.tryEmitComplete();
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 调用快速模型（qwen-turbo，禁用思考功能）
     * 用于关键词提取、查询重写、意图识别等需要快速响应的场景
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @return 模型响应内容
     */
    private String callFastModel(String systemPrompt, String userMessage, ChatStreamContext ctx) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userMessage));
        ChatClientResponse response = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .messages(messages)
                .call()
                .chatClientResponse();
        if (response == null || response.chatResponse() == null) {
            return null;
        }
        ChatTokenUsageUtil.recordUsage(response.chatResponse(), ctx.promptTokens, ctx.generationTokens);
        return response.chatResponse().getResult().getOutput().getText();
    }

    // ==================== 内部类 ====================

    /**
     * 流式会话上下文
     * 封装单次流式调用过程中需要跨方法传递的状态对象，
     * 避免在方法签名中层层传递多个独立变量
     */
    private static class ChatStreamContext {
        // 是否已发送最终结果的标记位
        final AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);
        // 收集最终答案（纯文本），用于存储到数据库
        final StringBuilder finalAnswerBuffer = new StringBuilder();
        // 收集思考过程，用于存储到数据库
        final StringBuilder thinkingBuffer = new StringBuilder();
        // 收集当前回答文本，用于生成推荐问题
        final StringBuilder currentAnswerBuffer = new StringBuilder();
        // 参考来源列表（知识库检索、关键词检索的相关参考来源）
        final List<SearchResult> references = new ArrayList<>();
        // Token 使用计数器
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong generationTokens = new AtomicLong();
        // 提取的关键词列表
        List<String> keywords = Collections.emptyList();
        // 重写后的查询语句
        String rewrittenQuery;
        // 识别到的意图类型
        String intent;
        // 意图描述
        String intentDescription;
        // 是否处于think标签内（用于流式解析）
        boolean inThink = false;
    }

    /**
     * 检索结果记录类
     * 统一向量检索和BM25检索的结果格式，便于后续去重和RRF融合
     *
     * @param content   文本内容
     * @param chunkId   分片ID（去重键）
     * @param docId     文档ID
     * @param fileName  文件名
     * @param url       文件URL
     * @param score     检索分数（向量相似度或BM25分数）
     * @param source    检索来源（vector/bm25）
     */
    record RetrievalResult(String content, String chunkId, Long docId,
                           String fileName, String url, double score, String source) {
    }

    /**
     * DATA_QUERY 数据查询表信息记录类
     * 封装 DATA_QUERY 类型知识库文档对应的动态表元数据，用于 Text2SQL 流程
     *
     * @param docId          文档ID
     * @param docTitle       文档标题
     * @param description    文档/表描述（用于 LLM 理解表的业务含义）
     * @param tableName      物理表名（dh_data_query_xxx）
     * @param columnsInfoJson 列信息 JSON 字符串
     * @param createSql      建表 DDL
     */
    record QueryTableInfo(Long docId, String docTitle, String description,
                          String tableName, String columnsInfoJson, String createSql) {
    }

    /**
     * RagAgent的构建器
     * 提供链式API设置所有配置参数，最终通过build()方法创建Agent实例
     */
    public static class Builder {

        // AI聊天模型
        private ChatModel chatModel;
        // 向量存储路由器
        private VectorStoreRouter vectorStoreRouter;
        // 知识文档服务
        private KnowledgeDocumentService knowledgeDocumentService;
        // 知识片段服务
        private KnowledgeSegmentService knowledgeSegmentService;
        // 表元数据 Mapper（用于 DATA_QUERY Text2SQL 查询）
        private TableMetaMapper tableMetaMapper;
        // 文件存储服务（用于预签名 URL 转换）
        private FileStorageService fileStorageService;
        // 聊天会话服务
        private ChatConversationService chatConversationService;
        // 聊天消息服务
        private ChatMessageService chatMessageService;
        // Agent提示词服务
        private AgentPromptService agentPromptService;
        // 任务管理器
        private AgentTaskManager taskManager;
        // 每日 Token 限制服务
        private ChatTokenLimitService chatTokenLimitService;
        // 向量检索每个知识库的topK，默认5
        private int vectorTopK = 5;
        // BM25检索返回的最大片段数，默认10
        private int bm25TopK = 10;
        // RRF融合后返回的最大结果数，默认5
        private int fusedTopK = 5;
        // 联网搜索工具回调数组
        private ToolCallback[] webSearchTools;
        // 是否启用联网搜索提示
        private boolean enableWebSearchFallback = false;

        /**
         * 设置聊天模型
         */
        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        /**
         * 设置向量存储路由器
         */
        public Builder vectorStoreRouter(VectorStoreRouter vectorStoreRouter) {
            this.vectorStoreRouter = vectorStoreRouter;
            return this;
        }

        /**
         * 设置知识文档服务
         */
        public Builder knowledgeDocumentService(KnowledgeDocumentService knowledgeDocumentService) {
            this.knowledgeDocumentService = knowledgeDocumentService;
            return this;
        }

        /**
         * 设置知识片段服务
         */
        public Builder knowledgeSegmentService(KnowledgeSegmentService knowledgeSegmentService) {
            this.knowledgeSegmentService = knowledgeSegmentService;
            return this;
        }

        /**
         * 设置表元数据 Mapper（用于 DATA_QUERY Text2SQL 查询）
         */
        public Builder tableMetaMapper(TableMetaMapper tableMetaMapper) {
            this.tableMetaMapper = tableMetaMapper;
            return this;
        }

        /**
         * 设置文件存储服务（用于预签名 URL 转换）
         */
        public Builder fileStorageService(FileStorageService fileStorageService) {
            this.fileStorageService = fileStorageService;
            return this;
        }

        /**
         * 设置聊天会话服务
         */
        public Builder chatConversationService(ChatConversationService chatConversationService) {
            this.chatConversationService = chatConversationService;
            return this;
        }

        /**
         * 设置聊天消息服务
         */
        public Builder chatMessageService(ChatMessageService chatMessageService) {
            this.chatMessageService = chatMessageService;
            return this;
        }

        /**
         * 设置Agent提示词服务
         */
        public Builder agentPromptService(AgentPromptService agentPromptService) {
            this.agentPromptService = agentPromptService;
            return this;
        }

        /**
         * 设置任务管理器
         */
        public Builder taskManager(AgentTaskManager taskManager) {
            this.taskManager = taskManager;
            return this;
        }

        /**
         * 设置每日 Token 限制服务
         */
        public Builder chatTokenLimitService(ChatTokenLimitService chatTokenLimitService) {
            this.chatTokenLimitService = chatTokenLimitService;
            return this;
        }

        /**
         * 设置向量检索每个知识库的topK
         */
        public Builder vectorTopK(int vectorTopK) {
            this.vectorTopK = vectorTopK;
            return this;
        }

        /**
         * 设置BM25检索返回的最大片段数
         */
        public Builder bm25TopK(int bm25TopK) {
            this.bm25TopK = bm25TopK;
            return this;
        }

        /**
         * 设置RRF融合后返回的最大结果数
         */
        public Builder fusedTopK(int fusedTopK) {
            this.fusedTopK = fusedTopK;
            return this;
        }

        /**
         * 设置联网搜索工具回调数组
         */
        public Builder webSearchTools(ToolCallback[] webSearchTools) {
            this.webSearchTools = webSearchTools;
            return this;
        }

        /**
         * 设置是否启用联网搜索提示（前端选了联网时为 true）
         */
        public Builder enableWebSearchFallback(boolean enableWebSearchFallback) {
            this.enableWebSearchFallback = enableWebSearchFallback;
            return this;
        }

        /**
         * 构建RagAgent实例
         * 校验必填参数并创建Agent实例
         *
         * @return 配置完成的RagAgent实例
         * @throws IllegalArgumentException 如果必填参数为空
         */
        public RagAgent build() {
            if (chatModel == null) {
                throw new IllegalArgumentException("chatModel 不能为空！");
            }
            if (vectorStoreRouter == null) {
                throw new IllegalArgumentException("vectorStoreRouter 不能为空！");
            }
            if (knowledgeDocumentService == null) {
                throw new IllegalArgumentException("knowledgeDocumentService 不能为空！");
            }
            if (knowledgeSegmentService == null) {
                throw new IllegalArgumentException("knowledgeSegmentService 不能为空！");
            }
            return new RagAgent(this);
        }
    }

    /**
     * 获取Builder实例的静态工厂方法
     *
     * @return 新的Builder实例
     */
    public static Builder builder() {
        return new Builder();
    }

}
