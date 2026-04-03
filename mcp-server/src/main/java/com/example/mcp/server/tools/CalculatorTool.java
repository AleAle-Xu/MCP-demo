package com.example.mcp.server.tools;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计算器 MCP 工具
 *
 * 每个 Tool 包含：
 * - name（唯一标识）
 * - description（AI理解用途）
 * - inputSchema（参数格式）
 * 
 * 工具注册三要素：
 * - 定义输入 Schema（JSON Schema）：让 AI 知道应该传什么参数
 * - 定义 Tool（name + description + schema）：AI 依此决定是否调用
 * - 定义 Handler 函数（BiFunction）：参数已由框架解析为 Map，执行后返回 CallToolResult
 */
@Slf4j
@Component
public class CalculatorTool {

    /**
     * 构建计算器工具的 MCP 注册规格（SyncToolSpecification）
     * SyncToolSpecification = Tool定义 + Handler函数 的封装对象，
     * 传给 McpServer.sync().tools() 完成注册。
     */
    public McpServerFeatures.SyncToolSpecification buildToolSpec() {

        // Step 1：定义输入参数的 JSON Schema
        // properties 必须是 Map<String, Object>，每个属性描述是一个嵌套 Map
        Map<String, Object> properties = new HashMap<>();
        properties.put("operation", mapOf(
                "type", "string",
                "description", "运算类型：add(加) subtract(减) multiply(乘) divide(除)",
                "enum", List.of("add", "subtract", "multiply", "divide")
        ));
        properties.put("a", mapOf(
                "type", "number",
                "description", "第一个操作数"
        ));
        properties.put("b", mapOf(
                "type", "number",
                "description", "第二个操作数"
        ));

        // JsonSchema 构造器：(type, properties, required, additionalProperties, defs, definitions)
        var inputSchema = new McpSchema.JsonSchema(
                "object",
                properties,
                List.of("operation", "a", "b"),  // required 字段
                null, null, null
        );

        // Step 2：创建 Tool 定义（名称 + 描述 + Schema）
        var tool = new McpSchema.Tool(
                "calculator",
                "执行数学四则运算（加减乘除），支持整数和浮点数",
                inputSchema
        );

        // Step 3：实现 Handler（BiFunction<McpSyncServerExchange, Map<String,Object>, CallToolResult>）
        // exchange：服务端上下文（可获取客户端信息、发通知等）
        // params  ：框架已从 JSON-RPC 解析好的参数 Map
        return new McpServerFeatures.SyncToolSpecification(tool,
                (McpSyncServerExchange exchange, Map<String, Object> params) -> {
                    log.info("[Calculator] 调用参数: {}", params);
                    try {
                        String operation = (String) params.get("operation");
                        double a = toDouble(params.get("a"));
                        double b = toDouble(params.get("b"));

                        double result = calculate(operation, a, b);
                        String msg = formatResult(operation, a, b, result);
                        log.info("[Calculator] 结果: {}", msg);

                        // CallToolResult(content列表, isError)
                        // isError=false 表示调用成功
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(msg)), false
                        );

                    } catch (ArithmeticException e) {
                        log.warn("[Calculator] 计算错误: {}", e.getMessage());
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("计算错误: " + e.getMessage())), true
                        );
                    } catch (Exception e) {
                        log.error("[Calculator] 异常", e);
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("系统错误: " + e.getMessage())), true
                        );
                    }
                });
    }

    private double calculate(String op, double a, double b) {
        return switch (op) {
            case "add"      -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide"   -> {
                if (b == 0) throw new ArithmeticException("除数不能为零");
                yield a / b;
            }
            default -> throw new IllegalArgumentException("不支持的运算: " + op);
        };
    }

    /** JSON 数字可能反序列化为 Integer/Long/Double，统一转 double */
    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    private String formatResult(String op, double a, double b, double result) {
        String symbol = switch (op) {
            case "add" -> "+"; case "subtract" -> "-";
            case "multiply" -> "*"; case "divide" -> "/";
            default -> "?";
        };
        String resultStr = (result == Math.floor(result) && !Double.isInfinite(result))
                ? String.valueOf((long) result) : String.valueOf(result);
        return String.format("%s %s %s = %s", formatNum(a), symbol, formatNum(b), resultStr);
    }

    private String formatNum(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v))
                ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** 辅助方法：创建 Map<String, Object> */
    @SafeVarargs
    private static Map<String, Object> mapOf(Object... kvPairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kvPairs.length - 1; i += 2) {
            map.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return map;
    }
}
