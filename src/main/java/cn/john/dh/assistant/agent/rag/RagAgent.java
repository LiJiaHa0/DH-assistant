package cn.john.dh.assistant.agent.rag;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.agent.AgentTaskManager;
import cn.john.dh.assistant.agent.BaseAgent;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.common.AgentResponse;
import cn.john.dh.assistant.constant.AgentType;
import cn.john.dh.assistant.constant.ChatMessageType;
import cn.john.dh.assistant.constant.MetadataKeyConstant;
import cn.john.dh.assistant.constant.PromptKey;
import cn.john.dh.assistant.entity.SearchResult;
import cn.john.dh.assistant.prompt.RagAgentPrompts;
import cn.john.dh.assistant.prompt.service.AgentPromptService;
import cn.john.dh.assistant.rag.config.KnowledgeBase;
import cn.john.dh.assistant.rag.config.VectorStoreRouter;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeSegment;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.rag.service.KnowledgeSegmentService;
import cn.john.dh.assistant.utils.ThinkTagParser;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
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
        // 流结束时从任务管理器中移除任务
        if (taskManager != null) {
            taskManager.stopTask(conversationId);
        }
    }
    // ==================== RAG 检索流程 ====================

    /**
     * 执行RAG检索流程，编排关键词提取、查询重写、意图识别、向量检索、BM25检索、RRF融合等步骤
     * 每一步都通过sink向前端发送交互提示
     *
     * @param question 用户原始问题
     * @param sink     响应流信号发射器
     * @param ctx      流式会话上下文
     * @return 检索结果拼接的上下文字符串，为空表示未检索到相关内容
     */
    private String performRagPipeline(String question, String userId,
                                       Sinks.Many<String> sink, ChatStreamContext ctx) {
        // 1. 获取用户文档ID集合，用于知识库文档的user_id过滤召回
        emit(sink, ctx.hasSentFinalResult, "\n🔍 正在检索您的知识库文档...\n", AgentResponse.TYPE_THINKING);
        Set<Long> userDocIds = getUserDocIds(userId);
        if (userDocIds.isEmpty()) {
            emit(sink, ctx.hasSentFinalResult, "⚠️ 您的知识库中暂无文档，将基于通用知识回答\n", AgentResponse.TYPE_THINKING);
            return "";
        }
        emit(sink, ctx.hasSentFinalResult, "📄 共找到 " + userDocIds.size() + " 篇文档\n", AgentResponse.TYPE_THINKING);

        // 2. 关键词提取
        emit(sink, ctx.hasSentFinalResult, "\n🔑 正在提取关键词...\n", AgentResponse.TYPE_THINKING);
        ctx.keywords = extractKeywords(question, sink, ctx);
        emit(sink, ctx.hasSentFinalResult, "提取到关键词: " + String.join(", ", ctx.keywords) + "\n", AgentResponse.TYPE_THINKING);

        // 3. 查询重写
        emit(sink, ctx.hasSentFinalResult, "\n✏️ 正在重写查询...\n", AgentResponse.TYPE_THINKING);
        ctx.rewrittenQuery = rewriteQuery(question, sink, ctx);
        emit(sink, ctx.hasSentFinalResult, "重写后的查询: " + ctx.rewrittenQuery + "\n", AgentResponse.TYPE_THINKING);

        // 4. 意图识别
        emit(sink, ctx.hasSentFinalResult, "\n🎯 正在识别问题意图...\n", AgentResponse.TYPE_THINKING);
        JSONObject intentResult = recognizeIntent(question, sink, ctx);
        ctx.intent = intentResult.getString("intent");
        ctx.intentDescription = intentResult.getString("description");
        emit(sink, ctx.hasSentFinalResult, "意图类型: " + ctx.intent + "（" + ctx.intentDescription + "）\n", AgentResponse.TYPE_THINKING);

        // 5. 向量语义检索（跨所有知识库，后过滤用户文档）
        emit(sink, ctx.hasSentFinalResult, "\n📐 正在进行向量语义检索...\n", AgentResponse.TYPE_THINKING);
        List<RetrievalResult> vectorResults = vectorSearch(ctx.rewrittenQuery, userDocIds, sink, ctx);
        emit(sink, ctx.hasSentFinalResult, "向量检索到 " + vectorResults.size() + " 条结果\n", AgentResponse.TYPE_THINKING);

        // 6. BM25关键词检索
        emit(sink, ctx.hasSentFinalResult, "\n🔎 正在进行BM25关键词检索...\n", AgentResponse.TYPE_THINKING);
        List<RetrievalResult> bm25Results = bm25Search(ctx.keywords, userDocIds, sink, ctx);
        emit(sink, ctx.hasSentFinalResult, "BM25检索到 " + bm25Results.size() + " 条结果\n", AgentResponse.TYPE_THINKING);

        // 7. 去重 + RRF融合
        emit(sink, ctx.hasSentFinalResult, "\n🔀 正在进行结果融合与去重...\n", AgentResponse.TYPE_THINKING);
        List<RetrievalResult> fusedResults = deduplicateAndRRFFusion(vectorResults, bm25Results);
        emit(sink, ctx.hasSentFinalResult, "融合后得到 " + fusedResults.size() + " 条结果\n", AgentResponse.TYPE_THINKING);

        // 8. 收集参考来源（去重，按文件名/URL过滤）
        Set<String> refKeys = new HashSet<>();
        for (RetrievalResult result : fusedResults) {
            String refKey = result.fileName() != null ? result.fileName() : result.url();
            if (refKey != null && !refKey.isEmpty() && refKeys.add(refKey)) {
                ctx.references.add(new SearchResult(
                        result.fileName(),
                        result.content().length() > 200 ? result.content().substring(0, 200) + "..." : result.content(),
                        result.url()));
            }
        }

        // 9. 构建上下文
        String context = buildContext(fusedResults);
        if (context.isEmpty()) {
            emit(sink, ctx.hasSentFinalResult, "⚠️ 知识库中未检索到相关内容，将基于通用知识回答\n", AgentResponse.TYPE_THINKING);
        } else {
            emit(sink, ctx.hasSentFinalResult, "✅ 检索完成，正在生成回答...\n", AgentResponse.TYPE_THINKING);
        }

        return context;
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
            String response = callFastModel(promptContent, question);
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
            String response = callFastModel(promptContent, question);
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
            String response = callFastModel(promptContent, question);
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
     * 查询用户拥有的知识库文档ID集合
     * 通过knowledge_document表的user_id字段过滤，实现用户级别的知识库隔离
     *
     * @param userId 用户ID
     * @return 文档ID集合，无文档时返回空集合
     */
    private Set<Long> getUserDocIds(String userId) {
        QueryWrapper<KnowledgeDocument> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId)
                .select("doc_id");
        List<KnowledgeDocument> docs = knowledgeDocumentService.list(wrapper);
        Set<Long> docIds = new HashSet<>();
        for (KnowledgeDocument doc : docs) {
            if (doc.getDocId() != null) {
                docIds.add(doc.getDocId());
            }
        }
        return docIds;
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
                            w.like("`text`", keywords.get(i));
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

        // 生成并发送推荐问题
        if (enableRecommendations) {
            String recommendations = generateRecommendations(currentQuestion, finalText, messages);
            if (recommendations != null) {
                currentRecommendations = recommendations;
                String recommendJson = createRecommendResponse(recommendations);
                sink.tryEmitNext(recommendJson);
            }
        }

        // 标记已发送最终结果并发送结束标记
        ctx.hasSentFinalResult.set(true);
        sink.tryEmitNext(createCompleteResponse());
        sink.tryEmitComplete();
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
    private String callFastModel(String systemPrompt, String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userMessage));
        return ChatClient.builder(chatModel)
                .build()
                .prompt()
                .messages(messages)
                .call()
                .content();
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
        // 聊天会话服务
        private ChatConversationService chatConversationService;
        // 聊天消息服务
        private ChatMessageService chatMessageService;
        // Agent提示词服务
        private AgentPromptService agentPromptService;
        // 任务管理器
        private AgentTaskManager taskManager;
        // 向量检索每个知识库的topK，默认5
        private int vectorTopK = 5;
        // BM25检索返回的最大片段数，默认10
        private int bm25TopK = 10;
        // RRF融合后返回的最大结果数，默认5
        private int fusedTopK = 5;

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
