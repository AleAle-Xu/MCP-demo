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
 * 直接调用：通过 McpClient.executeTool() 直接调用工具（无 AI 介入）
 * AI 驱动调用：通过 AiServices + McpToolProvider 让 AI 自动选择调用工具
 *
 * 运行前提：mcp-server 必须已启动（mvn spring-boot:run -pl mcp-server）
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

        // ── Demo 2：AI 驱动工具调用（需要配置 OpenAI API Key）─────────────
        demo2_aiDrivenToolCall();  

        log.info("╔══════════════════════════════════════════════╗");
        log.info("║        所有 Demo 执行完毕                     ║");
        log.info("╚══════════════════════════════════════════════╝");
    }

    // =========================================================================
    //  Demo 1：直接调用工具（最基础的方式，不经过 AI 模型）
    // =========================================================================

    /**
     * Demo 1：通过 McpServerManager 直接调用工具
     */
    private static void demo1_directToolCall() throws Exception {
        log.info("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
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
    //  Demo 2：AI 驱动的工具调用（需要 OpenAI API Key）
    // =========================================================================

    /**
     * Demo 2：结合 AiServices 让 AI 模型自动调用 MCP 工具
     *
     * 完整的 AI + MCP 工具调用流程：
     *   用户输入 → AiServices → AI模型判断需要工具
     *           → 调用 McpToolProvider.provideTools() 获取工具列表
     *           → AI 模型生成 tools/call 请求
     *           → LangChain4j 路由到 McpClient.executeTool()
     *           → 工具结果注入 AI 上下文
     *           → AI 模型生成最终回复
     *
     * 使用前需设置环境变量：OPENAI_API_KEY
     */
    @SuppressWarnings("unused")
    private static void demo2_aiDrivenToolCall() {
        log.info("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("Demo 2：AI 驱动的 MCP 工具调用");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未设置 OPENAI_API_KEY 环境变量，跳过 Demo 2");
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
