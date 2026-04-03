package com.example.mcp.client.demo;

import com.example.mcp.client.manager.McpServerConfig;
import com.example.mcp.client.manager.McpServerManager;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * MCP 直接调用演示
 *
 * <p>本类演示不经过 AI 模型、直接通过 LangChain4j MCP 客户端调用工具的方式。
 * 适用场景：
 * <ul>
 *   <li>开发阶段验证工具是否正常工作</li>
 *   <li>后端服务直接调用 MCP 工具（不走 LLM）</li>
 *   <li>工具测试、集成测试</li>
 * </ul>
 *
 * <p>使用前请先启动 mcp-server 模块：
 * <pre>
 *   mvn spring-boot:run -pl mcp-server
 * </pre>
 */
public class DirectCallDemo {

    private static final Logger log = LoggerFactory.getLogger(DirectCallDemo.class);

    /** MCP 服务端地址 */
    private static final String SERVER_SSE_URL = "http://localhost:8080/sse";

    public static void main(String[] args) throws Exception {
        log.info("========== MCP 直接调用演示 ==========");
        log.info("请确保 mcp-server 已在 http://localhost:8080 启动");
        log.info("");

        // ── 使用 try-with-resources 确保资源自动释放 ───────────────────────
        try (McpServerManager manager = new McpServerManager()) {

            // ── Step 1：注册 MCP 服务器 ──────────────────────────────────────
            // 此步骤会：
            //   1. 建立 SSE 长连接（GET /sse）
            //   2. 执行 MCP 握手（initialize → initialized）
            //   3. 打印已发现的工具列表
            log.info("【Step 1】注册 MCP 服务器...");
            manager.register(McpServerConfig.http(
                    "demo-server",          // key：Manager 内部标识
                    "Demo MCP Server",      // name：显示名称
                    SERVER_SSE_URL,         // SSE 端点 URL
                    "包含计算器和天气查询两个工具"
            ));

            // ── Step 2：查询工具列表（tools/list）────────────────────────────
            log.info("\n【Step 2】查询工具列表...");
            List<ToolSpecification> tools = manager.listTools("demo-server");
            log.info("发现 {} 个工具:", tools.size());
            for (ToolSpecification tool : tools) {
                log.info("  ▶ [{}] {}", tool.name(), tool.description());
                if (tool.parameters() != null) {
                    log.info("    参数: {}", tool.parameters());
                }
            }

            // ── Step 3：直接调用计算器工具（tools/call）──────────────────────
            log.info("\n【Step 3】直接调用工具 calculator...");

            // 获取底层 McpClient（Manager 对外透明，这里用于演示）
            // 实际生产中通过 McpToolProvider 间接调用，无需感知 McpClient
            // 此处为演示目的直接获取
            demo_calculator(manager);

            // ── Step 4：直接调用天气查询工具 ────────────────────────────────
            log.info("\n【Step 4】直接调用工具 weather...");
            demo_weather(manager);

            // ── Step 5：演示多服务管理能力 ──────────────────────────────────
            log.info("\n【Step 5】演示多服务管理...");
            demo_multiServer(manager);

            // ── Step 6：健康检查 ─────────────────────────────────────────────
            log.info("\n【Step 6】健康检查...");
            Map<String, Boolean> health = manager.checkAllHealth();
            health.forEach((k, v) -> log.info("  服务器 [{}]: {}", k, v ? "✅ 健康" : "❌ 异常"));

        }
        // try 块结束后，manager.close() 自动被调用，断开所有 SSE 连接

        log.info("\n========== 演示结束 ==========");
    }

    /**
     * 演示直接调用计算器工具
     * 通过底层 McpClient 构造 ToolExecutionRequest 并执行
     */
    private static void demo_calculator(McpServerManager manager) {
        // 构造工具调用请求
        // ToolExecutionRequest 是 LangChain4j 的标准工具调用请求对象，
        // 包含工具名称和 JSON 格式的参数字符串
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .name("calculator")
                .arguments("{\"operation\": \"multiply\", \"a\": 123.456, \"b\": 78.9}")
                .build();

        // 通过 Manager 获取客户端（演示用，实际推荐用 ToolProvider）
        // 注意：此 API 是为展示底层调用方式，生产中建议用 McpToolProvider
        String result = callTool(manager, "demo-server", req);
        log.info("  计算结果: {}", result);

        // 再测试除法
        ToolExecutionRequest divReq = ToolExecutionRequest.builder()
                .name("calculator")
                .arguments("{\"operation\": \"divide\", \"a\": 100, \"b\": 3}")
                .build();
        String divResult = callTool(manager, "demo-server", divReq);
        log.info("  除法结果: {}", divResult);

        // 测试除以零（演示错误处理）
        ToolExecutionRequest divZeroReq = ToolExecutionRequest.builder()
                .name("calculator")
                .arguments("{\"operation\": \"divide\", \"a\": 5, \"b\": 0}")
                .build();
        String errResult = callTool(manager, "demo-server", divZeroReq);
        log.info("  除以零结果（应报错）: {}", errResult);
    }

    /**
     * 演示直接调用天气查询工具
     */
    private static void demo_weather(McpServerManager manager) {
        // 不传 unit（可选参数），服务端使用默认值 celsius
        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
                .name("weather")
                .arguments("{\"city\": \"北京\"}")
                .build();
        String result1 = callTool(manager, "demo-server", req1);
        log.info("  北京天气（默认摄氏度）:\n{}", result1);

        // 传入 unit=fahrenheit，使用华氏度
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
                .name("weather")
                .arguments("{\"city\": \"上海\", \"unit\": \"fahrenheit\"}")
                .build();
        String result2 = callTool(manager, "demo-server", req2);
        log.info("  上海天气（华氏度）:\n{}", result2);
    }

    /**
     * 演示多服务器管理：动态注册第二个服务器（指向同一个服务，模拟多实例）
     */
    private static void demo_multiServer(McpServerManager manager) {
        // 注册第二个 MCP 服务（指向相同地址，仅演示多服务注册机制）
        // 真实场景：这里可以是另一个提供不同工具的 MCP 服务地址
        try {
            manager.register(McpServerConfig.http(
                    "demo-server-2",
                    "Demo MCP Server 2（模拟第二个服务实例）",
                    SERVER_SSE_URL
            ));

            log.info("  当前已注册服务器: {}", manager.getRegisteredKeys());

            // 获取所有服务器的工具汇总
            Map<String, List<ToolSpecification>> allTools = manager.listAllTools();
            allTools.forEach((key, toolList) ->
                    log.info("  服务器 [{}] 的工具: {}",
                            key, toolList.stream().map(ToolSpecification::name).toList()));

            // 构建聚合 ToolProvider（会合并两个服务器的所有工具）
            McpToolProvider provider = manager.buildToolProvider();
            log.info("  McpToolProvider 已构建，聚合了 {} 个服务器的工具", manager.size());

            // 动态注销第二个服务
            manager.unregister("demo-server-2");
            log.info("  注销 demo-server-2 后，剩余服务器: {}", manager.getRegisteredKeys());

        } catch (Exception e) {
            log.warn("  多服务演示跳过（第二个服务无法连接）: {}", e.getMessage());
        }
    }

    /**
     * 辅助方法：通过 Manager 的底层客户端执行工具调用
     *
     * <p>注意：此方式直接操作 McpClient，绕过了 McpToolProvider。
     * 演示目的是让你理解底层 API。
     * 实际开发中推荐通过 McpToolProvider + AiServices 调用，
     * 或在需要直接调用时使用此方式。
     */
    private static String callTool(McpServerManager manager,
                                   String serverKey,
                                   ToolExecutionRequest req) {
        try {
            // 验证工具存在
            List<ToolSpecification> tools = manager.listTools(serverKey);
            boolean toolExists = tools.stream().anyMatch(t -> t.name().equals(req.name()));
            if (!toolExists) {
                return "工具 [" + req.name() + "] 不存在于服务器 [" + serverKey + "]";
            }
            // 通过底层 McpClient 直接调用工具
            McpClient client = manager.getClient(serverKey);
            if (client == null) return "服务器 [" + serverKey + "] 未连接";
            return client.executeTool(req);

        } catch (Exception e) {
            return "调用失败: " + e.getMessage();
        }
    }
}
