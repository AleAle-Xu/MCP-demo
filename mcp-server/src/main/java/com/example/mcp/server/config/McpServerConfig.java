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
 * MCP 服务端配置：传输层 → 路由注册 → 工具装配
 *
 * 通信模型：客户端用 GET /sse 建立长连接接收推送，用 POST /mcp/message 发送请求。
 * 响应不走 HTTP 返回值，而是由服务端通过 SSE 连接异步推回。
 */
@Slf4j
@Configuration
public class McpServerConfig {

    /**
     * 传输层：声明 POST 端点路径为 /mcp/message。
     * 该路径有两个用途：
     *   1. 注册 POST /mcp/message 路由（由 mcpRouterFunction 完成）
     *   2. SSE 握手时推送给客户端，告知它往哪里发请求
     */
    @Bean
    public WebMvcSseServerTransportProvider transportProvider(ObjectMapper objectMapper) {
        return new WebMvcSseServerTransportProvider(objectMapper, "/mcp/message");
    }

    /**
     * 将 GET /sse 和 POST /mcp/message 路由注册到 Spring MVC。
     */
    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(
            WebMvcSseServerTransportProvider transportProvider) {
        return transportProvider.getRouterFunction();
    }

    /**
     * 核心服务器：处理 MCP 协议握手、tools/list、tools/call 等请求。
     */
    @Bean
    public McpSyncServer mcpSyncServer(
            WebMvcSseServerTransportProvider transportProvider,
            CalculatorTool calculatorTool,
            WeatherTool weatherTool) {

        // 服务端标识，握手时发送给客户端
        McpSchema.Implementation serverInfo = new McpSchema.Implementation(
                "mcp-demo-server", "1.0.0"
        );

        // 声明支持 tools，客户端才会发起 tools/list
        McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build();

        // 获取工具规格（Tool Schema + Handler）
        McpServerFeatures.SyncToolSpecification calcSpec   = calculatorTool.buildToolSpec();
        McpServerFeatures.SyncToolSpecification weatherSpec = weatherTool.buildToolSpec();

        // 构建服务器并注册工具
        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo(serverInfo)
                .capabilities(capabilities)
                .tools(calcSpec, weatherSpec)   // 注册多个工具
                .build();

        log.info("==================================================");
        log.info("MCP Server 启动完成");
        log.info("  工具列表: calculator, weather");
        log.info("  SSE 端点:  GET  http://localhost:18080/sse");
        log.info("  消息端点:  POST http://localhost:18080/mcp/message");
        log.info("==================================================");

        return server;
    }
}
