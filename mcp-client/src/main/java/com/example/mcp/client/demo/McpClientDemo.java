package com.example.mcp.client.demo;

import com.example.mcp.client.manager.McpServerConfig;
import com.example.mcp.client.manager.McpServerManager;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端演示主程序
 *
 * <p>本类演示三种使用 MCP 工具的方式：
 * <ol>
 *   <li><b>直接调用</b>：通过 McpClient.executeTool() 直接调用工具（无 AI 介入）</li>
 *   <li><b>多服务管理</b>：注册多个 MCP 服务器，统一查询所有工具</li>
 *   <li><b>AI 驱动调用</b>：通过 AiServices + McpToolProvider 让 AI 自动选择调用工具</li>
 * </ol>
 *
 * <p>运行前提：mcp-server 必须已启动（mvn spring-boot:run -pl mcp-server）
 */
public class McpClientDemo {

    private static final Logger log = LoggerFactory.getLogger(McpClientDemo.class);

    // MCP 服务端地址（与 mcp-server 的 application.yml 对应）
    private static final String SERVER_SSE_URL = "http://localhost:18080/sse";

    public static void main(String[] args) throws Exception {
        log.info("╔══════════════════════════════════════════════╗");
        log.info("║        MCP Client Demo 启动                  ║");
        log.info("╚══════════════════════════════════════════════╝");

        // ── Demo 1：基础工具直接调用 ────────────────────────────────────────
        demo1_directToolCall();

        // ── Demo 2：多 MCP 服务器管理 ──────────────────────────────────────
        demo2_multiServerManagement();

        // ── Demo 3：AI 驱动工具调用（需要配置 OpenAI API Key）─────────────
        // demo3_aiDrivenToolCall();  // 如有 Key 可取消注释

        log.info("╔══════════════════════════════════════════════╗");
        log.info("║        所有 Demo 执行完毕                    ║");
        log.info("╚══════════════════════════════════════════════╝");
    }

    // =========================================================================
    //  Demo 1：直接调用工具（最基础的方式，不经过 AI 模型）
    // =========================================================================

    /**
     * Demo 1：通过 McpServerManager 直接调用工具
     *
     * <p>适合场景：
     * <ul>
     *   <li>系统集成测试，验证工具是否可用</li>
     *   <li>已知需要调用哪个工具，直接调用（跳过 AI 决策）</li>
     *   <li>工具调用结果需要在代码中进一步处理</li>
     * </ul>
     */
    private static void demo1_directToolCall() throws Exception {
        log.info("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━��━━━━━━━");
        log.info("Demo 1：直接调用 MCP 工具（无 AI 介入）");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // try-with-resources：Manager 实现了 AutoCloseable，退出时自动关闭所有连接
        try (McpServerManager manager = new McpServerManager()) {

            // Step 1：注册 MCP 服务器（内部建立 SSE 连接 + MCP 握手）
            McpServerConfig config = McpServerConfig.http(
                    "main-server",          // key：唯一标识
                    "主 MCP 服务器",         // name：显示名称
                    SERVER_SSE_URL,         // SSE 端点 URL
                    "提供计算器和天气查询工具"  // 描述
            );
            manager.register(config);

            // Step 2：查看已注册的工具列表
            log.info("\n【工具列表】");
            List<ToolSpecification> tools = manager.listTools("main-server");
            tools.forEach(t ->
                    log.info("  工具名: {}  |  描述: {}", t.name(), t.description())
            );

            // Step 3：直接调用 calculator 工具
            log.info("\n【调用 calculator 工具：计算 123.5 × 456.7】");
            // McpClient 需要通过 Manager 内部获取，这里我们演示通过 Manager 封装的方法
            // 实际生产中可以直接持有 McpClient 引用调用 executeTool()
            var calcClient = manager.getClient("main-server");
            if (calcClient != null) {
                ToolExecutionRequest calcRequest = ToolExecutionRequest.builder()
                        .name("calculator")
                        // arguments 是 JSON 字符串，必须与工具 Schema 匹配
                        .arguments("{\"operation\":\"multiply\",\"a\":123.5,\"b\":456.7}")
                        .build();
                String calcResult = calcClient.executeTool(calcRequest).resultText();
                log.info("  calculator 返回: {}", calcResult);
            }

            // Step 4：直接调用 weather 工具
            log.info("\n【调用 weather 工具：查询北京天气】");
            var weatherClient = manager.getClient("main-server");
            if (weatherClient != null) {
                ToolExecutionRequest weatherRequest = ToolExecutionRequest.builder()
                        .name("weather")
                        .arguments("{\"city\":\"北京\",\"unit\":\"celsius\"}")
                        .build();
                String weatherResult = weatherClient.executeTool(weatherRequest).resultText();
                log.info("  weather 返回:\n{}", weatherResult);
            }

            // Step 5：健康检查
            log.info("\n【健康检查】");
            boolean healthy = manager.isHealthy("main-server");
            log.info("  main-server 状态: {}", healthy ? "✅ 正常" : "❌ 异常");
        }
        // Manager.close() 被自动调用，SSE 连接断开
    }

    // =========================================================================
    //  Demo 2：多 MCP 服务器管理
    // =========================================================================

    /**
     * Demo 2：演示如何管理多个 MCP 服务器
     *
     * <p>真实生产场景中，一个 AI 应用通常需要连接多个 MCP 服务器，例如：
     * <ul>
     *   <li>weather-server：提供天气、地图类工具</li>
     *   <li>db-server：提供数据库查询、报表工具</li>
     *   <li>fs-server：提供文件读写、代码执行工具</li>
     * </ul>
     *
     * <p>McpServerManager 统一管理所有连接，McpToolProvider 将工具聚合后
     * 注入 AiServices，AI 模型可以跨服务器自由选择并调用工具。
     *
     * <p>本 Demo 仅注册同一个服务端的两个"逻辑别名"以演示多服务管理机制，
     * 实际使用时替换为不同的 sseUrl 即可。
     */
    private static void demo2_multiServerManagement() {
        log.info("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("Demo 2：多 MCP 服务器管理");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        try (McpServerManager manager = new McpServerManager()) {

            // 注册多个 MCP 服务器（这里用同一服务端演示，实际场景用不同 URL）
            manager.register(McpServerConfig.http(
                    "server-a", "计算服务", SERVER_SSE_URL, "提供计算工具"));

            // 注意：实际项目中第二个服务器应该是不同的地址
            // manager.register(McpServerConfig.http(
            //     "server-b", "天气服务", "http://localhost:8081/sse", "提供天气工具"));

            log.info("\n【已注册服务器】: {}", manager.getRegisteredKeys());

            // 查询所有服务器的工具（聚合视图）
            log.info("\n【所有服务器工具汇总】");
            Map<String, List<ToolSpecification>> allTools = manager.listAllTools();
            allTools.forEach((serverKey, toolList) -> {
                log.info("  服务器 [{}]（共 {} 个工具）:", serverKey, toolList.size());
                toolList.forEach(t -> log.info("    • {} — {}", t.name(), t.description()));
            });

            // 全局健康检查
            log.info("\n【健康状态检查】");
            Map<String, Boolean> health = manager.checkAllHealth();
            health.forEach((key, ok) ->
                    log.info("  [{}]: {}", key, ok ? "✅ 健康" : "❌ 异常"));

            // 构建聚合 ToolProvider（所有服务器工具统一入口）
            McpToolProvider toolProvider = manager.buildToolProvider();
            log.info("\n【McpToolProvider 构建成功】");
            log.info("  可将此 toolProvider 注入 AiServices，AI 模型将自动拥有以上所有工具能力");

            // 模拟动态注销（运行时移除某个服务器）
            log.info("\n【演示动态注销】");
            manager.unregister("server-a");
            log.info("  注销 server-a 后，剩余服务器: {}", manager.getRegisteredKeys());

        }
    }

    // =========================================================================
    //  Demo 3：AI 驱动的工具调用（需要 OpenAI API Key）
    // =========================================================================

    /**
     * Demo 3：结合 AiServices 让 AI 模型自动调用 MCP 工具
     *
     * <p>完整的 AI + MCP 工具调用流程：
     * <pre>
     *   用户输入 → AiServices → AI模型判断需要工具
     *           → 调用 McpToolProvider.provideTools() 获取工具列表
     *           → AI 模型生成 tools/call 请求
     *           → LangChain4j 路由到 McpClient.executeTool()
     *           → 工具结果注入 AI 上下文
     *           → AI 模型生成最终回复
     * </pre>
     *
     * <p>使用前需设置环境变量：OPENAI_API_KEY
     */
    @SuppressWarnings("unused")
    private static void demo3_aiDrivenToolCall() {
        log.info("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("Demo 3：AI 驱动的 MCP 工具调用");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未设置 OPENAI_API_KEY 环境变量，跳过 Demo 3");
            log.warn("设置方式：export OPENAI_API_KEY=sk-xxx  或在 IDE 的运行配置中添加环境变量");
            return;
        }

        try (McpServerManager manager = new McpServerManager()) {
            manager.register(McpServerConfig.http("demo", "Demo服务器", SERVER_SSE_URL));

            // 构建聚合工具提供者
            McpToolProvider toolProvider = manager.buildToolProvider();

            // 创建 AI 模型（OpenAI GPT-4o-mini，支持工具调用）
            var model = dev.langchain4j.model.openai.OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName("gpt-4o-mini")
                    .build();

            // 定义 AI 服务接口（LangChain4j AiServices 约定）
            // AiServices 会自动处理工具调用的完整生命周期
            interface Assistant {
                String chat(String userMessage);
            }

            Assistant assistant = dev.langchain4j.service.AiServices.builder(Assistant.class)
                    .chatModel(model)
                    .toolProvider(toolProvider)   // 注入 MCP 工具
                    .build();

            // 测试 1：让 AI 使用计算器工具
            log.info("\n【用户提问】：帮我计算 (100 + 200) × 3 的结果");
            String resp1 = assistant.chat("帮我计算 (100 + 200) × 3 的结果");
            log.info("【AI 回复】：{}", resp1);

            // 测试 2：让 AI 使用天气工具
            log.info("\n【用户提问】：上海今天天气怎么样？");
            String resp2 = assistant.chat("上海今天天气怎么样？");
            log.info("【AI 回复】：{}", resp2);
        }
    }
}
