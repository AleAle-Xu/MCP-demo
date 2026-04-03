package com.example.mcp.client.manager;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 服务器管理器
 *
 * 统一管理多个 MCP 服务器的生命周期（注册、连接、查询、卸载），
 * 并聚合所有服务器的工具，为上层（AiServices 或直接调用方）提供统一入口。
 *
 * 参考大模型应用平台 MCP 工具管理模块的设计思路，关键能力：
 *   - 动态注册：运行时注册/注销 MCP 服务器，无需重启应用
 *   - 多服务聚合：通过 McpToolProvider 把所有已连接服务器的工具聚合成统一的 ToolProvider
 *   - 工具发现：列出指定服务器或全部服务器的工具清单
 *   - 健康检查：检测各 MCP 服务器的连接状态
 *   - 资源释放：实现 AutoCloseable，关闭时自动断开所有连接
 *
 * 典型使用流程：
 *
 *   McpServerManager manager = new McpServerManager();
 *   manager.register(McpServerConfig.http("weather", "天气服务", "http://localhost:8080/sse"));
 *   manager.register(McpServerConfig.http("db",      "数据库服务", "http://localhost:8081/sse"));
 *
 *   McpToolProvider toolProvider = manager.buildToolProvider();
 *   MyAssistant ai = AiServices.builder(MyAssistant.class)
 *       .chatModel(model)
 *       .toolProvider(toolProvider)
 *       .build();
 *
 *   manager.close(); // 使用完毕后释放资源，或用 try-with-resources
 */
public class McpServerManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);

    // 已注册的服务器配置表（key → config）
    // 使用 ConcurrentHashMap 保证多线程下注册/注销的安全
    private final Map<String, McpServerConfig> configs = new ConcurrentHashMap<>();

    // 已建立连接的 MCP 客户端表（key → McpClient）
    // McpClient 是 AutoCloseable，关闭时会断开 SSE 连接或终止子进程
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    // =========================================================================
    //  注册与注销
    // =========================================================================

    /**
     * 注册并连接一个 MCP 服务器
     *
     * 注册即连接：调用此方法后立即建立连接并完成 MCP 握手（initialize）。
     * 握手完成后，服务器的工具列表即可被查询。
     * 若该 key 已注册，先关闭旧连接再重新连接（热更新语义）。
     *
     * @param config MCP 服务器配置
     * @throws RuntimeException 连接失败时抛出（含握手超时、网络不可达等）
     */
    public void register(McpServerConfig config) {
        String key = config.key();
        log.info("[McpManager] 注册服务器: key={}, name={}", key, config.name());

        // 如果已存在同 key 的服务器，先关闭旧连接
        if (clients.containsKey(key)) {
            log.warn("[McpManager] key={} 已存在，将关闭旧连接并重新注册", key);
            unregister(key);
        }

        // 根据配置创建对应的传输层
        McpTransport transport = buildTransport(config);

        // 建立 McpClient（构造时自动执行 initialize 握手，阻塞直到握手完成）
        McpClient client = new DefaultMcpClient.Builder()
                .transport(transport)
                .key(key)
                .clientName("mcp-demo-client")
                .clientVersion("1.0.0")
                .initializationTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                .toolExecutionTimeout(Duration.ofSeconds(config.toolExecutionTimeoutSeconds()))
                .build();

        configs.put(key, config);
        clients.put(key, client);
        log.info("[McpManager] 服务器 [{}] 连接成功，正在发现工具...", key);

        // 打印工具列表（帮助开发者确认工具已就绪）
        try {
            List<ToolSpecification> tools = client.listTools();
            log.info("[McpManager] 服务器 [{}] 共发现 {} 个工具:", key, tools.size());
            tools.forEach(t -> log.info("    - {} : {}", t.name(), t.description()));
        } catch (Exception e) {
            log.warn("[McpManager] 无法获取服务器 [{}] 的工具列表: {}", key, e.getMessage());
        }
    }

    /**
     * 注销并断开一个 MCP 服务器
     * 关闭 McpClient：HTTP 模式断开 SSE 长连接；Stdio 模式终止子进程。
     */
    public void unregister(String key) {
        McpClient client = clients.remove(key);
        configs.remove(key);
        if (client != null) {
            try {
                client.close();
                log.info("[McpManager] 服务器 [{}] 已断开", key);
            } catch (Exception e) {
                log.warn("[McpManager] 关闭服务器 [{}] 时出现异常: {}", key, e.getMessage());
            }
        }
    }

    // =========================================================================
    //  工具查询
    // =========================================================================

    /**
     * 查询指定服务器的工具列表
     *
     * @param key 服务器唯一标识
     * @return 工具规格列表；key 不存在时返回空列表
     */
    public List<ToolSpecification> listTools(String key) {
        McpClient client = clients.get(key);
        if (client == null) {
            log.warn("[McpManager] 服务器 [{}] 不存在", key);
            return List.of();
        }
        return client.listTools();
    }

    /**
     * 查询所有已注册服务器的工具列表
     *
     * @return Map：key → 该服务器的工具列表
     */
    public Map<String, List<ToolSpecification>> listAllTools() {
        Map<String, List<ToolSpecification>> result = new LinkedHashMap<>();
        clients.forEach((key, client) -> {
            try {
                result.put(key, client.listTools());
            } catch (Exception e) {
                log.warn("[McpManager] 获取 [{}] 工具列表失败: {}", key, e.getMessage());
                result.put(key, List.of());
            }
        });
        return result;
    }

    // =========================================================================
    //  核心：构建聚合 McpToolProvider
    // =========================================================================

    /**
     * 构建聚合所有已注册服务器工具的 McpToolProvider
     *
     * McpToolProvider 是 LangChain4j 的桥接器，持有所有 McpClient 引用。
     * AiServices 在每次推理前调用 provideTools() 汇总所有服务器的工具列表，
     * AI 模型选择工具后再路由到对应的 McpClient.executeTool() 执行。
     *
     * failIfOneServerFails=false：某个服务器不可用时跳过而不抛异常（容错策略）。
     *
     * @return 可直接注入 AiServices 的 McpToolProvider
     * @throws IllegalStateException 没有任何已注册服务器时抛出
     */
    public McpToolProvider buildToolProvider() {
        if (clients.isEmpty()) {
            throw new IllegalStateException("没有已注册的 MCP 服务器，请先调用 register()");
        }

        List<McpClient> clientList = new ArrayList<>(clients.values());
        log.info("[McpManager] 构建 McpToolProvider，聚合 {} 个服务器的工具", clientList.size());

        return McpToolProvider.builder()
                .mcpClients(clientList)
                .failIfOneServerFails(false)
                .build();
    }

    // =========================================================================
    //  健康检查与状态查询
    // =========================================================================

    /**
     * 检查指定服务器的连接健康状态
     *
     * @param key 服务器唯一标识
     * @return true=健康，false=不健康或不存在
     */
    public boolean isHealthy(String key) {
        McpClient client = clients.get(key);
        if (client == null) return false;
        try {
            client.checkHealth();
            return true;
        } catch (Exception e) {
            log.warn("[McpManager] 服务器 [{}] 健康检查失败: {}", key, e.getMessage());
            return false;
        }
    }

    /** 检查所有服务器健康状态，返回 Map：key → 是否健康 */
    public Map<String, Boolean> checkAllHealth() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        clients.keySet().forEach(key -> result.put(key, isHealthy(key)));
        return result;
    }

    /** 获取所有已注册服务器的 key 列表 */
    public Set<String> getRegisteredKeys() {
        return Collections.unmodifiableSet(clients.keySet());
    }

    /** 获取已注册服务器数量 */
    public int size() {
        return clients.size();
    }

    /**
     * 获取指定 key 对应的 McpClient（用于直接调用工具）
     *
     * @param key 服务器唯一标识
     * @return McpClient 实例，不存在时返回 null
     */
    public McpClient getClient(String key) {
        return clients.get(key);
    }

    // =========================================================================
    //  内部：根据配置构建传输层
    // =========================================================================

    /**
     * 根据配置创建 MCP 传输层
     *
     * LangChain4j MCP 支持两种传输协议：
     *   - HttpMcpTransport：连接已部署的 MCP HTTP 服务（SSE+POST）
     *   - StdioMcpTransport：启动本地子进程，通过 stdin/stdout 通信（适合本地 Node.js/Python 服务）
     */
    private McpTransport buildTransport(McpServerConfig config) {
        if (config.isHttp()) {
            log.debug("[McpManager] 使用 HTTP 传输，SSE URL: {}", config.sseUrl());
            return new HttpMcpTransport.Builder()
                    .sseUrl(config.sseUrl())
                    .timeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                    .logRequests(true)
                    .logResponses(true)
                    .build();
        } else {
            log.debug("[McpManager] 使用 Stdio 传输，命令: {}", config.command());
            return new StdioMcpTransport.Builder()
                    .command(config.command())
                    .logEvents(true)
                    .build();
        }
    }

    // =========================================================================
    //  资源释放
    // =========================================================================

    /**
     * 关闭所有 MCP 连接，释放资源
     * 支持 try-with-resources 写法，块结束后自动调用此方法。
     */
    @Override
    public void close() {
        log.info("[McpManager] 正在关闭所有 MCP 连接...");
        new ArrayList<>(clients.keySet()).forEach(this::unregister);
        log.info("[McpManager] 所有 MCP 连接已关闭");
    }
}
