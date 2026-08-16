package cn.john.dh.assistant.chat.controller;

import cn.john.dh.assistant.agent.AgentTaskManager;
import cn.john.dh.assistant.agent.deepresearch.PlanExecuteAgent;
import cn.john.dh.assistant.agent.rag.RagAgent;
import cn.john.dh.assistant.agent.websearch.WebSearchReactAgent;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.chat.service.ChatTokenLimitService;
import cn.john.dh.assistant.common.AgentResponse;
import cn.john.dh.assistant.common.R;
import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.prompt.service.AgentPromptService;
import cn.john.dh.assistant.rag.config.VectorStoreRouter;
import cn.john.dh.assistant.rag.mapper.TableMetaMapper;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.rag.service.KnowledgeSegmentService;
import cn.john.dh.assistant.rag.service.impl.FileStorageService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

/**
 * @Author John
 * @Date 2026-07-18 14:19
 */
@RestController
@RequestMapping("/agent")
@Slf4j
public class AgentChatController implements InitializingBean {

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    // 本地Ollama聊天模型，用于会话标题生成等轻量场景，避免消耗云端模型配额
    @Autowired
    private OllamaChatModel ollamaChatModel;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private AgentPromptService agentPromptService;

    // 每日聊天 Token 限制服务
    @Autowired
    private ChatTokenLimitService chatTokenLimitService;

    // 向量存储路由器，用于RAG知识库检索
    @Autowired
    private VectorStoreRouter vectorStoreRouter;

    // 知识文档服务，用于按用户ID查询文档
    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    // 知识片段服务，用于BM25关键词检索
    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    // 表元数据 Mapper，用于 DATA_QUERY 类型知识库的 Text2SQL 查询
    @Autowired
    private TableMetaMapper tableMetaMapper;

    // 文件存储服务，用于 RAG 参考来源的预签名 URL 转换
    @Autowired
    private FileStorageService fileStorageService;

    // Tavily 搜索引擎 API Key
    @Value("${tavily.api-key:}")
    private String tavilyApiKey;

    // Tavily MCP 服务地址
    @Value("${tavily.mcp-url:https://mcp.tavily.com/mcp/}")
    private String tavilyMcpUrl;

    // Skills 技能目录路径
    @Value("${skills.directory:}")
    private String skillsDirectory;

    // MCP搜索工具回调数组
    private ToolCallback[] webSearchToolCallbacks;

    // Tavily API Key 是否已配置（未配置时不做重试，避免无意义的重复初始化）
    private boolean tavilyKeyConfigured = false;

    // 任务管理器
    private final AgentTaskManager taskManager;


    /**
     * 构造方法，通过依赖注入初始化核心服务
     *
     * @param chatModel          AI聊天模型
     * @param chatConversationService     会话服务
     * @param taskManager        任务管理器
     */
    public AgentChatController(@Qualifier("openAiChatModel") ChatModel chatModel,
                           ChatConversationService chatConversationService,
                           AgentTaskManager taskManager) {
        // 设置聊天模型
        this.chatModel = chatModel;
        // 设置会话服务
        this.chatConversationService = chatConversationService;
        // 设置任务管理器
        this.taskManager = taskManager;
    }


    /**
     * 网页搜索流式端点
     * 接收用户查询并返回SSE流式响应，使用Tavily联网搜索获取信息
     *
     * @param query          用户查询内容
     * @param conversationId 对话ID
     * @return SSE流式响应
     */
    @GetMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> chatStream(
            @RequestParam String query,
            @RequestParam String conversationId) {
        // 记录请求日志
        log.info("收到网页搜索请求: query={}, conversationId={}", query, conversationId);

        // 校验查询参数非空
        if (query == null || query.trim().isEmpty()) {
            log.warn("查询参数为空或无效");
            return Flux.error(new IllegalArgumentException("查询参数不能为空"));
        }

        // 每日 Token 限额前置校验
        Flux<String> limitCheck = checkTokenLimit();
        if (limitCheck != null) {
            return limitCheck;
        }

        try {
            // 初始化网页搜索Agent
            WebSearchReactAgent agent = initWebSearchAgent();
            // 执行Agent并返回SSE流
            return agent.execute(conversationId, query);
        } catch (Exception e) {
            // 记录处理错误日志
            log.error("处理网页搜索请求时发生错误: ", e);
            // 兜底清理：停止可能已注册的任务，避免残留任务锁定会话
            cleanupTask(conversationId);
            return Flux.error(e);
        }
    }

    /**
     * 深度研究流式端点
     * 接收用户查询并返回SSE流式响应，使用计划-执行模式进行深度研究
     *
     * @param query          用户研究问题
     * @param conversationId 对话ID
     * @return SSE流式响应
     */
    @GetMapping(value = "/deep/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> deepStream(
            @RequestParam String query,
            @RequestParam String conversationId) {
        // 记录请求日志
        log.info("收到深度研究请求: query={}, conversationId={}", query, conversationId);

        // 校验查询参数非空
        if (query == null || query.trim().isEmpty()) {
            log.warn("查询参数为空或无效");
            return Flux.error(new IllegalArgumentException("查询参数不能为空"));
        }

        // 每日 Token 限额前置校验
        Flux<String> limitCheck = checkTokenLimit();
        if (limitCheck != null) {
            return limitCheck;
        }

        try {
            // 初始化深度研究Agent
            PlanExecuteAgent agent = initPlanExecuteAgent();
            // 执行Agent并返回SSE流
            return agent.execute(conversationId, query);
        } catch (Exception e) {
            // 记录处理错误日志
            log.error("处理深度研究请求时发生错误: ", e);
            // 兜底清理：停止可能已注册的任务，避免残留任务锁定会话
            cleanupTask(conversationId);
            return Flux.error(e);
        }
    }

    /**
     * 知识库问答流式端点
     * 接收用户查询并返回SSE流式响应，使用RAG模式从知识库中检索相关信息
     *
     * @param query          用户查询内容
     * @param conversationId 对话ID
     * @return SSE流式响应
     */
    @GetMapping(value = "/rag/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> ragStream(
            @RequestParam String query,
            @RequestParam String conversationId) {
        // 记录请求日志
        log.info("收到知识库问答请求: query={}, conversationId={}", query, conversationId);

        // 校验查询参数非空
        if (query == null || query.trim().isEmpty()) {
            log.warn("查询参数为空或无效");
            return Flux.error(new IllegalArgumentException("查询参数不能为空"));
        }

        // 每日 Token 限额前置校验
        Flux<String> limitCheck = checkTokenLimit();
        if (limitCheck != null) {
            return limitCheck;
        }

        try {
            // 初始化知识库问答Agent
            RagAgent agent = initRagAgent();
            // 执行Agent并返回SSE流
            return agent.execute(conversationId, query);
        } catch (Exception e) {
            // 记录处理错误日志
            log.error("处理知识库问答请求时发生错误: ", e);
            // 兜底清理：停止可能已注册的任务，避免残留任务锁定会话
            cleanupTask(conversationId);
            return Flux.error(e);
        }
    }

    /**
     * 初始化RagAgent（知识库问答）
     * 配置ChatModel、会话服务、任务管理器、向量存储路由器、知识文档服务和知识片段服务
     *
     * @return 配置完成的RagAgent实例
     */
    private RagAgent initRagAgent() {
        // 记录初始化日志
        log.info("初始化知识库问答 Agent...");

        // 构建知识库问答Agent
        RagAgent ragAgent = RagAgent.builder()
                // 设置聊天模型
                .chatModel(chatModel)
                // 设置会话服务
                .chatConversationService(chatConversationService)
                .chatMessageService(chatMessageService)
                .agentPromptService(agentPromptService)
                // 设置任务管理器
                .taskManager(taskManager)
                // 设置每日 Token 限制服务
                .chatTokenLimitService(chatTokenLimitService)
                // 设置向量存储路由器
                .vectorStoreRouter(vectorStoreRouter)
                // 设置知识文档服务
                .knowledgeDocumentService(knowledgeDocumentService)
                // 设置知识片段服务
                .knowledgeSegmentService(knowledgeSegmentService)
                // 设置表元数据 Mapper（用于 DATA_QUERY Text2SQL 查询）
                .tableMetaMapper(tableMetaMapper)
                // 设置文件存储服务（用于预签名 URL 转换）
                .fileStorageService(fileStorageService)
                .build();
        // 设置标题生成专用模型（本地Ollama）
        ragAgent.setTitleModel(ollamaChatModel);
        return ragAgent;
    }

    /**
     * 初始化PlanExecute Agent（深度研究）
     * 配置ChatModel、会话服务、任务管理器、搜索工具和最大研究轮次
     *
     * @return 配置完成的PlanExecuteAgent实例
     */
    private PlanExecuteAgent initPlanExecuteAgent() {
        // 记录初始化日志
        log.info("初始化 PlanExecute Agent...");

        // 构建深度研究Agent
        PlanExecuteAgent planExecuteAgent = PlanExecuteAgent.builder()
                // 设置聊天模型
                .chatModel(chatModel)
                // 设置会话服务
                .chatConversationService(chatConversationService)
                .chatMessageService(chatMessageService)
                .agentPromptService(agentPromptService)
                // 设置任务管理器
                .taskManager(taskManager)
                // 设置每日 Token 限制服务
                .chatTokenLimitService(chatTokenLimitService)
                // 设置MCP搜索工具（懒加载：启动时初始化失败则在此重试）
                .tools(ensureWebSearchToolCallbacks())
                // 设置最大研究轮次为3
                .maxRounds(3)
                .build();
        // 设置标题生成专用模型（本地Ollama）
        planExecuteAgent.setTitleModel(ollamaChatModel);
        return planExecuteAgent;
    }


    /**
     * 停止指定会话的Agent执行
     * 中断底层调用，释放订阅资源
     *
     * @param conversationId 会话ID
     * @return 操作结果
     */
    @GetMapping("/stop")
    public R<Boolean> stopTask(@RequestParam String conversationId) {
        // 记录停止请求日志
        log.info("收到停止请求: conversationId={}", conversationId);
        // 调用任务管理器停止任务
        boolean stopped = taskManager.stopTask(conversationId);
        // 返回操作结果
        return R.ok(stopped);
    }

    /**
     * 异常时的兜底任务清理
     * Agent内部已对同步阶段异常做了清理，此处作为双重保障，
     * 防止任务残留导致会话被"正在执行中"锁定
     *
     * @param conversationId 会话ID，为空时不做处理
     */
    private void cleanupTask(String conversationId) {
        // 会话ID为空时跳过（新会话的ID由Agent内部创建，其清理已在Agent内部完成）
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        // 停止任务（不存在时返回false，无副作用）
        taskManager.stopTask(conversationId);
    }

    /**
     * 校验用户当日 Token 限额。
     * 额度不足时返回 AI 风格的 SSE 提示流，否则返回 null。
     *
     * @return 限额超限时的 SSE Flux，或 null（未超限）
     */
    private Flux<String> checkTokenLimit() {
        String userId = StpUtil.getLoginIdAsString();
        if (!chatTokenLimitService.isAvailable(userId)) {
            log.warn("用户 {} 当日聊天 token 已达限制", userId);
            return Flux.just(
                    AgentResponse.text("今日聊天token已达限制，请联系管理员（wx:John-Lee9564）或明日再试。"),
                    AgentResponse.complete()
            );
        }
        return null;
    }



    /**
     * 初始化网页搜索Agent
     * 配置ChatModel、会话服务、任务管理器和Tavily MCP搜索工具
     *
     * @return 配置完成的WebSearchReactAgent实例
     */
    private WebSearchReactAgent initWebSearchAgent() {
        // 记录初始化日志
        log.info("初始化网页搜索 Agent...");

        // 构建网页搜索Agent
        WebSearchReactAgent webSearchAgent = WebSearchReactAgent.builder()
                // 设置聊天模型
                .chatModel(chatModel)
                // 设置会话服务
                .conversationService(chatConversationService)
                .chatMessageService(chatMessageService)
                .agentPromptService(agentPromptService)
                // 设置任务管理器
                .taskManager(taskManager)
                // 设置每日 Token 限制服务
                .chatTokenLimitService(chatTokenLimitService)
                // 设置MCP搜索工具（懒加载：启动时初始化失败则在此重试）
                .tools(ensureWebSearchToolCallbacks())
                .build();
        // 设置标题生成专用模型（本地Ollama）
        webSearchAgent.setTitleModel(ollamaChatModel);
        return webSearchAgent;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        // 打印当前使用的模型信息
        log.info("当前ChatModel类型: {}", chatModel.getClass().getName());
        log.info("当前ChatModel默认配置: {}", chatModel.getDefaultOptions());
        // 记录初始化开始日志
        log.info("开始初始化工具 ToolCallback");

        // 初始化网页搜索工具回调（Tavily MCP）
        initWebSearchToolCallbacks();

        // 记录初始化完成日志
        log.info("工具 ToolCallback 初始化完成");
    }

    /**
     * 确保网页搜索工具回调可用（懒加载重试）
     * 启动时若因网络抖动/SSL握手失败导致初始化失败，工具数组会为空；
     * 此处发现为空且API Key已配置时重新初始化一次，避免工具能力永久失效
     *
     * @return 可用的工具回调数组
     */
    private synchronized ToolCallback[] ensureWebSearchToolCallbacks() {
        // API Key已配置但工具数组为空，说明之前初始化失败，尝试重新初始化
        if (tavilyKeyConfigured && (webSearchToolCallbacks == null || webSearchToolCallbacks.length == 0)) {
            log.warn("网页搜索工具不可用，尝试重新初始化 Tavily MCP 工具...");
            initWebSearchToolCallbacks();
        }
        return webSearchToolCallbacks;
    }

    /**
     * 初始化网页搜索工具回调
     * 通过Tavily MCP协议连接搜索引擎，获取搜索工具回调
     */
    private void initWebSearchToolCallbacks() {
        // 记录初始化开始日志
        log.info("初始化网页搜索工具回调...");
        // 检查API Key是否配置
        if (tavilyApiKey == null || tavilyApiKey.isEmpty() || tavilyApiKey.equals("tvly-dev-placeholder")) {
            // API Key未配置时记录警告日志
            log.warn("Tavily API key 未配置，网页搜索功能将不可用");
            // 标记未配置，后续不再重试
            tavilyKeyConfigured = false;
            // 使用空工具数组
            webSearchToolCallbacks = new ToolCallback[0];
            return;
        }
        // 标记API Key已配置
        tavilyKeyConfigured = true;
        try {
            // 构建Tavily MCP认证请求头
            String authorizationHeader = "Bearer " + tavilyApiKey;

            // 创建带认证头的HTTP请求构建器
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .header("Authorization", authorizationHeader);

            // 创建Tavily MCP传输层（使用Streamable HTTP协议）
            // 禁用启动时自动建立SSE GET连接，Tavily MCP端点不支持GET请求，会导致405错误
            HttpClientStreamableHttpTransport tavTransport = HttpClientStreamableHttpTransport.builder(tavilyMcpUrl)
                    .requestBuilder(requestBuilder)
                    .openConnectionOnStartup(false)
                    .build();

            // 创建MCP同步客户端，设置请求超时300秒
            McpSyncClient tavilyMcp = McpClient.sync(tavTransport)
                    .requestTimeout(Duration.ofSeconds(300))
                    .build();
            // 初始化MCP连接
            tavilyMcp.initialize();

            // 通过SyncMcpToolCallbackProvider将MCP工具转换为Spring AI工具回调
            List<McpSyncClient> mcpClients = List.of(tavilyMcp);
            // 构建工具回调提供者
            SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder().mcpClients(mcpClients).build();

            // 获取工具回调数组
            webSearchToolCallbacks = provider.getToolCallbacks();
            // 记录初始化完成日志
            log.info("网页搜索工具回调初始化完成，工具数量: {}", webSearchToolCallbacks.length);
        } catch (Exception e) {
            // 初始化失败时记录错误日志
            log.error("初始化Tavily MCP工具失败: {}", e.getMessage());
            // 失败时使用空工具数组
            webSearchToolCallbacks = new ToolCallback[0];
        }
    }
}
