package com.example.mcp.client;

import com.example.mcp.client.manager.McpServerConfig;
import com.example.mcp.client.manager.McpServerManager;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 客户端集成测试
 *
 * <p><b>运行前提</b>：mcp-server 必须已在 8080 端口运行。
 * <pre>
 *   cd mcp-demo
 *   mvn spring-boot:run -pl mcp-server
 * </pre>
 *
 * <p>测试内容：
 * <ol>
 *   <li>能否成功连接 MCP 服务器</li>
 *   <li>能否正确发现工具（calculator、weather）</li>
 *   <li>calculator 工具各运算是否结果正确</li>
 *   <li>weather 工具是否返回有效 JSON</li>
 *   <li>calculator 除零是否返回错误标志</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpClientIntegrationTest {

    private static final String SSE_URL = "http://localhost:8080/sse";

    /** 整个测试类共用一个 Manager（避免重复建连） */
    private static McpServerManager manager;

    @BeforeAll
    static void setup() {
        manager = new McpServerManager();
        // 注册 MCP 服务器（此处会建立 SSE 连接并完成 MCP 握手）
        manager.register(McpServerConfig.http("test-server", "测试服务器", SSE_URL));
    }

    @AfterAll
    static void teardown() {
        if (manager != null) {
            manager.close();
        }
    }

    // ─── 测试 1：工具发现 ────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("连接成功后应能发现 2 个工具")
    void testToolDiscovery() {
        List<ToolSpecification> tools = manager.listTools("test-server");

        assertFalse(tools.isEmpty(), "工具列表不应为空");
        assertEquals(2, tools.size(), "应发现 2 个工具（calculator 和 weather）");

        List<String> names = tools.stream().map(ToolSpecification::name).toList();
        assertTrue(names.contains("calculator"), "工具列表应包含 calculator");
        assertTrue(names.contains("weather"), "工具列表应包含 weather");
    }

    // ─── 测试 2：calculator 工具 ─────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("calculator - 加法：1 + 1 = 2")
    void testCalculatorAdd() {
        String result = callTool("calculator",
                "{\"operation\":\"add\",\"a\":1,\"b\":1}");
        assertNotNull(result);
        assertTrue(result.contains("2"), "1+1 的结果应包含 2，实际: " + result);
    }

    @Test
    @Order(3)
    @DisplayName("calculator - 减法：10 - 3 = 7")
    void testCalculatorSubtract() {
        String result = callTool("calculator",
                "{\"operation\":\"subtract\",\"a\":10,\"b\":3}");
        assertTrue(result.contains("7"), "10-3 的结果应包含 7，实际: " + result);
    }

    @Test
    @Order(4)
    @DisplayName("calculator - 乘法：6 × 7 = 42")
    void testCalculatorMultiply() {
        String result = callTool("calculator",
                "{\"operation\":\"multiply\",\"a\":6,\"b\":7}");
        assertTrue(result.contains("42"), "6×7 的结果应包含 42，实际: " + result);
    }

    @Test
    @Order(5)
    @DisplayName("calculator - 除法：10 ÷ 4 = 2.5")
    void testCalculatorDivide() {
        String result = callTool("calculator",
                "{\"operation\":\"divide\",\"a\":10,\"b\":4}");
        assertTrue(result.contains("2.5"), "10÷4 的结果应包含 2.5，实际: " + result);
    }

    @Test
    @Order(6)
    @DisplayName("calculator - 除以零应返回错误信息")
    void testCalculatorDivideByZero() {
        String result = callTool("calculator",
                "{\"operation\":\"divide\",\"a\":5,\"b\":0}");
        // 工具返回 isError=true 时，LangChain4j 会将错误内容作为结果字符串返回
        assertTrue(result.contains("错误") || result.contains("error") || result.contains("零"),
                "除以零应返回错误信息，实际: " + result);
    }

    // ─── 测试 3：weather 工具 ────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("weather - 查询北京天气应返回 JSON 且包含 city 字段")
    void testWeatherBeijing() {
        String result = callTool("weather", "{\"city\":\"北京\"}");
        assertNotNull(result);
        assertTrue(result.contains("北京"), "结果应包含城市名，实际: " + result);
        assertTrue(result.contains("temperature"), "结果应包含 temperature 字段，实际: " + result);
        assertTrue(result.contains("weather"), "结果应包含 weather 字段，实际: " + result);
    }

    @Test
    @Order(8)
    @DisplayName("weather - 华氏度模式应返回 °F")
    void testWeatherFahrenheit() {
        String result = callTool("weather",
                "{\"city\":\"上海\",\"unit\":\"fahrenheit\"}");
        assertTrue(result.contains("°F"), "华氏度模式结果应包含 °F，实际: " + result);
    }

    // ─── 测试 4：服务器健康检查 ──────────────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("健康检查 - 已连接的服务器应返回健康")
    void testHealthCheck() {
        assertTrue(manager.isHealthy("test-server"), "已连接的服务器应通过健康检查");
        assertFalse(manager.isHealthy("non-existent"), "不存在的服务器健康检查应返回 false");
    }

    // ─── 辅助方法 ─────────────────────────────────────────────────────────────

    /** 通过 Manager 直接调用指定工具并返回结果字符串 */
    private String callTool(String toolName, String argumentsJson) {
        var client = manager.getClient("test-server");
        assertNotNull(client, "test-server 客户端不应为 null");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name(toolName)
                .arguments(argumentsJson)
                .build();

        return client.executeTool(request);
    }
}
