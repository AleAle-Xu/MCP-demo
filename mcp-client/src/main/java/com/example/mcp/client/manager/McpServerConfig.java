package com.example.mcp.client.manager;

import java.util.List;

/**
 * MCP 服务器配置（值对象）
 *
 * <p>描述一个 MCP 服务器的连接信息和元数据。
 * 类比数据库配置（DataSourceConfig），一个应用可以配置并连接多个 MCP 服务器，
 * 每个服务器提供不同领域的工具能力（天气、文件系统、数据库查询等）。
 *
 * <p>支持两种传输模式：
 * <ul>
 *   <li><b>HTTP/SSE 模式</b>：填写 {@code sseUrl}，连接已部署的 MCP HTTP 服务</li>
 *   <li><b>Stdio 模式</b>：填写 {@code command}，启动本地子进程（Node.js/Python 脚本等）</li>
 * </ul>
 *
 * @param key                    服务器唯一标识（Manager 内部路由依赖此 key）
 * @param name                   服务器显示名称（用于日志）
 * @param sseUrl                 HTTP/SSE 传输的 SSE 端点，如 http://localhost:8080/sse
 * @param command                Stdio 传输的启动命令，如 ["node", "server.js"]
 * @param connectTimeoutSeconds  连接和握手超时（秒）
 * @param toolExecutionTimeoutSeconds 单次工具调用超时（秒）
 * @param description            服务器描述（可选）
 */
public record McpServerConfig(
        String key,
        String name,
        String sseUrl,
        List<String> command,
        int connectTimeoutSeconds,
        int toolExecutionTimeoutSeconds,
        String description
) {
    /** 快速创建 HTTP/SSE 服务器配置（使用默认超时） */
    public static McpServerConfig http(String key, String name, String sseUrl) {
        return new McpServerConfig(key, name, sseUrl, null, 30, 60, null);
    }

    /** 快速创建 HTTP/SSE 服务器配置（带描述） */
    public static McpServerConfig http(String key, String name, String sseUrl, String desc) {
        return new McpServerConfig(key, name, sseUrl, null, 30, 60, desc);
    }

    /** 快速创建 Stdio 服务器配置 */
    public static McpServerConfig stdio(String key, String name, List<String> command) {
        return new McpServerConfig(key, name, null, command, 30, 60, null);
    }

    /** 判断是否为 HTTP 传输模式 */
    public boolean isHttp() {
        return sseUrl != null && !sseUrl.isBlank();
    }
}
