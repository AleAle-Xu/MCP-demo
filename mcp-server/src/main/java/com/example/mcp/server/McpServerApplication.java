package com.example.mcp.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP 服务端启动类
 *
 * MCP (Model Context Protocol) 是 Anthropic 于 2024 年发布的开放协议，
 * 旨在标准化 AI 模型与外部工具/数据源之间的交互方式。
 *
 * 本服务启动后：
 *   - 监听端口 8080
 *   - 暴露 SSE 端点：{@code GET  http://localhost:8080/sse}
 *   - 暴露消息端点：{@code POST http://localhost:8080/mcp/message}
 *   - 可供 MCP 客户端连接并调用 calculator 和 weather 工具
 * 
 * 启动方式：
 *   mvn spring-boot:run -pl mcp-server
 */
@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
