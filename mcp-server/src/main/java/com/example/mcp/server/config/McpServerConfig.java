package com.example.mcp.server.config;

import com.example.mcp.server.tools.CalculatorTool;
import com.example.mcp.server.tools.WeatherTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * MCP 服务端核心配置类
 *
 * 负责三件事：
 * 1. 创建 HTTP+SSE 传输层（WebMvcSseServerTransportProvider）
 * 2. 注册 Spring MVC 路由（GET /sse 和 POST /mcp/message 端点）
 * 3. 组装 McpSyncServer，注册所有工具
 *
 * MCP HTTP+SSE 通信时序：
 *
 *   客户端                          服务端
 *     │─── GET /sse (建立SSE长连接) ──▶│
 *     │◀── SSE: endpoint (POST URL) ───│  服务端推送消息接收地址
 *     │─── POST /mcp/message ─────────▶│  {method: initialize}
 *     │◀── SSE: response ─────────────│  服务端通过 SSE 推回响应
 *     │─── POST /mcp/message ─────────▶│  {method: tools/list}
 *     │◀── SSE: [calculator, weather]─│
 *     │─── POST /mcp/message ─────────▶│  {method: tools/call}
 *     │◀── SSE: {result: "..."}───────│
 */
@Slf4j
@Configuration
public class McpServerConfig {

    /**
     * 创建 MCP SSE 传输层
     *
     * WebMvcSseServerTransportProvider 自动暴露两个端点：
     *   GET  /sse         — 客户端建立 SSE 长连接，持续接收服务端推送
     *   POST /mcp/message — 客户端发送 JSON-RPC 请求
     *
     * 构造参数 "/mcp/message" 是 POST 端点路径（可自定义）。
     */
    @Bean
    public WebMvcSseServerTransportProvider transportProvider(ObjectMapper objectMapper) {
        // 0.10.0 版本构造器需要 (ObjectMapper, messageEndpointPath)
        return new WebMvcSseServerTransportProvider(objectMapper, "/mcp/message");
    }

    /**
     * 将 MCP 端点注册进 Spring MVC 路由系统
     *
     * transportProvider.getRouterFunction() 返回包含 GET /sse 路由的 RouterFunction，
     * Spring Boot 自动发现 RouterFunction Bean 并注册到 DispatcherServlet。
     */
    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(
            WebMvcSseServerTransportProvider transportProvider) {
        return transportProvider.getRouterFunction();
    }

    /**
     * 创建并启动 MCP 同步服务器
     *
     * McpSyncServer 是服务端核心，负责：
     *   - MCP 握手协议（initialize 请求/响应）
     *   - 响应 tools/list（返回已注册工具的 Schema 列表）
     *   - 路由 tools/call（根据工具名找 Handler 并执行）
     *
     * Sync 模式：每个请求在当前线程同步处理，适合普通 Spring MVC 应用。
     */
    @Bean
    public McpSyncServer mcpSyncServer(
            WebMvcSseServerTransportProvider transportProvider,
            CalculatorTool calculatorTool,
            WeatherTool weatherTool) {

        // 服务端标识（握手阶段发送给客户端，对应 MCP Implementation 对象）
        McpSchema.Implementation serverInfo = new McpSchema.Implementation(
                "mcp-demo-server", "1.0.0"
        );

        // 能力声明：告知客户端本服务端支持哪些 MCP 特性
        // tools(true) 表示开启工具支持，客户端才会发起 tools/list 请求
        McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build();

        // 获取每个工具的注册规格（Tool 定义 + Handler 函数）
        McpServerFeatures.SyncToolSpecification calcSpec   = calculatorTool.buildToolSpec();
        McpServerFeatures.SyncToolSpecification weatherSpec = weatherTool.buildToolSpec();

        // 构建并返回 McpSyncServer
        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo(serverInfo)
                .capabilities(capabilities)
                .tools(calcSpec, weatherSpec)   // varargs：注册多个工具
                .build();

        log.info("==================================================");
        log.info("MCP Server 启动完成");
        log.info("  工具列表: calculator, weather");
        log.info("  SSE 端点:  GET  http://localhost:8080/sse");
        log.info("  消息端点:  POST http://localhost:8080/mcp/message");
        log.info("==================================================");

        return server;
    }
}
