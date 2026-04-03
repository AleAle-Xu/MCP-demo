package com.example.mcp.server.tools;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 天气查询 MCP 工具
 *
 * 相比 CalculatorTool，本工具演示了：
 *   - 可选参数：unit 不在 required 列表，AI 可以不传
 *   - 枚举约束：通过 Schema 的 enum 限制参数取值
 *   - 结构化返回：以 JSON 字符串返回，便于 AI 解析利用
 *
 * 真实场景中 Handler 内部调用高德/和风等天气 API，
 * 本 Demo 用随机数据模拟，专注展示工具框架本身。
 */
@Slf4j
@Component
public class WeatherTool {

    private static final Map<String, String[]> WEATHER_DB = Map.of(
            "北京", new String[]{"晴", "多云", "霾", "小雨"},
            "上海", new String[]{"多云", "晴", "阴", "小雨"},
            "广州", new String[]{"晴", "雷阵雨", "多云", "阵雨"},
            "深圳", new String[]{"晴", "多云", "小雨", "雷阵雨"},
            "成都", new String[]{"阴", "多云", "小雨", "雾"},
            "DEFAULT", new String[]{"晴", "多云", "阴", "小雨"}
    );

    private final Random random = new Random();

    /**
     * 构建天气查询工具的 MCP 注册规格
     *
     * 可选参数设计：JSON Schema 的 required 数组只列必填参数，
     * 未列出的参数（unit）是可选的，Handler 中用 getOrDefault 处理缺省。
     */
    public McpServerFeatures.SyncToolSpecification buildToolSpec() {

        Map<String, Object> properties = new HashMap<>();
        properties.put("city", mapOf(
                "type", "string",
                "description", "要查询天气的城市名称，例如：北京、上海、广州"
        ));
        properties.put("unit", mapOf(
                "type", "string",
                "description", "温度单位：celsius(摄氏度，默认) 或 fahrenheit(华氏度)",
                "enum", List.of("celsius", "fahrenheit")
        ));

        // JsonSchema 构造器：(type, properties, required, additionalProperties, defs, definitions)
        var inputSchema = new McpSchema.JsonSchema(
                "object",
                properties,
                List.of("city"),   // 只有 city 必填，unit 可选
                null, null, null
        );

        var tool = new McpSchema.Tool(
                "weather",
                "查询指定城市的当前天气，包括天气状态、温度、湿度、风速等信息",
                inputSchema
        );

        return new McpServerFeatures.SyncToolSpecification(tool,
                (McpSyncServerExchange exchange, Map<String, Object> params) -> {
                    log.info("[Weather] 查询请求参数: {}", params);
                    try {
                        String city = (String) params.get("city");
                        String unit = (String) params.getOrDefault("unit", "celsius");

                        String resultJson = buildWeatherJson(city, unit);
                        log.info("[Weather] 查询完成: {}", city);

                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(resultJson)), false
                        );
                    } catch (Exception e) {
                        log.error("[Weather] 查询失败", e);
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("查询失败: " + e.getMessage())), true
                        );
                    }
                });
    }

    private String buildWeatherJson(String city, String unit) {
        String[] options = WEATHER_DB.getOrDefault(city, WEATHER_DB.get("DEFAULT"));
        String weather = options[random.nextInt(options.length)];
        int tempC = 15 + random.nextInt(21);
        Object temp = "celsius".equals(unit) ? tempC : (int) Math.round(tempC * 9.0 / 5 + 32);
        String unitStr = "celsius".equals(unit) ? "°C" : "°F";
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        return String.format("""
                {
                  "city": "%s",
                  "weather": "%s",
                  "temperature": %s,
                  "unit": "%s",
                  "humidity": "%d%%",
                  "windSpeed": "%d km/h",
                  "updateTime": "%s"
                }""",
                city, weather, temp, unitStr,
                40 + random.nextInt(51),
                random.nextInt(30) + 1,
                time);
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
