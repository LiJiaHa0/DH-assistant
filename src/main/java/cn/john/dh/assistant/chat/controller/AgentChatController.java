package cn.john.dh.assistant.chat.controller;

import cn.john.dh.assistant.agent.AgentTaskManager;
import cn.john.dh.assistant.agent.websearch.WebSearchReactAgent;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.common.R;
import cn.john.dh.assistant.prompt.service.AgentPromptService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
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
    private ChatModel chatModel;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private AgentPromptService agentPromptService;

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

    // 任务管理器
    private final AgentTaskManager taskManager;


    /**
     * 构造方法，通过依赖注入初始化核心服务
     *
     * @param chatModel          AI聊天模型
     * @param chatConversationService     会话服务
     * @param taskManager        任务管理器
     */
    public AgentChatController(ChatModel chatModel,
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

        try {
            // 初始化网页搜索Agent
            WebSearchReactAgent agent = initWebSearchAgent();
            // 执行Agent并返回SSE流
            return agent.execute(conversationId, query);
        } catch (Exception e) {
            // 记录处理错误日志
            log.error("处理网页搜索请求时发生错误: ", e);
            return Flux.error(e);
        }
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
     * 初始化网页搜索Agent
     * 配置ChatModel、会话服务、任务管理器和Tavily MCP搜索工具
     *
     * @return 配置完成的WebSearchReactAgent实例
     */
    private WebSearchReactAgent initWebSearchAgent() {
        // 记录初始化日志
        log.info("初始化网页搜索 Agent...");

        // 构建并返回网页搜索Agent
        return WebSearchReactAgent.builder()
                // 设置聊天模型
                .chatModel(chatModel)
                // 设置会话服务
                .conversationService(chatConversationService)
                .chatMessageService(chatMessageService)
                .agentPromptService(agentPromptService)
                // 设置任务管理器
                .taskManager(taskManager)
                // 设置MCP搜索工具
                .tools(webSearchToolCallbacks)
                .build();
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
            // 使用空工具数组
            webSearchToolCallbacks = new ToolCallback[0];
            return;
        }
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
