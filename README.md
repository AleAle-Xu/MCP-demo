# MCP Demo — 基于 LangChain4j 的 MCP 完整示例

## 项目结构

```
mcp-demo/
├── pom.xml                  # 父 POM（BOM 版本管理）
├── mcp-server/              # MCP 服务端模块
│   └── src/main/java/com/example/mcp/server/
│       ├── McpServerApplication.java     # Spring Boot 启动类
│       ├── config/McpServerConfig.java   # MCP Server 注册配置
│       └── tools/
│           ├── CalculatorTool.java       # 计算器工具（四则运算）
│           └── WeatherTool.java          # 天气查询工具
└── mcp-client/              # MCP 客户端模块
    └── src/main/java/com/example/mcp/client/
        ├── manager/
        │   ├── McpServerConfig.java      # 服务器连接配置（值对象）
        │   └── McpServerManager.java     # 多服务器管理器
        └── demo/
            ├── DirectCallDemo.java       # 直接调用演示（不走 AI）
            └── McpClientDemo.java        # 完整客户端演示
```

## 关键依赖版本

| 依赖 | 版本 | 说明 |
|---|---|---|
| `langchain4j-bom` | 1.0.0 | LangChain4j 版本管理 |
| `langchain4j-mcp` | 1.0.0-beta5 | MCP 客户端（由 BOM 管理） |
| `mcp-spring-webmvc` | 0.10.0 | MCP 官方 Java SDK 服务端 |
| `spring-boot` | 3.4.4 | 服务端 HTTP 框架 |
| Jackson | **2.19.0** | ⚠️ 需覆盖 Spring Boot BOM 的 2.18.x，MCP SDK 要求 2.19+ |

---

## 启动方式

### 前置条件

- Java 17+
- Maven 3.6+

### 第一步：启动 MCP 服务端

在项目根目录执行（前台运行，**`Ctrl+C` 即可关闭**）：

```bash
mvn spring-boot:run -pl mcp-server
```

启动成功后会看到：

```
MCP Server 启动完成
  工具���表: calculator, weather
  SSE 端点:  GET  http://localhost:8080/sse
  消息端点:  POST http://localhost:8080/mcp/message
```

**优雅关闭**（新开终端执行，服务会平滑停止）：

```bash
curl -X POST http://localhost:8080/actuator/shutdown
```

**端口被占用时强制释放**（如上次未正常关闭）：

```bash
# Windows PowerShell
powershell -Command "Stop-Process -Id (Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess -Force"
```

### 第二步：运行客户端 Demo

新开一个终端，在项目根目录执行：

```bash
mvn exec:java -pl mcp-client -Dexec.mainClass="com.example.mcp.client.demo.DirectCallDemo"
```

---

## 手动测试（curl）

服务端使用 **MCP over HTTP+SSE** 协议，手动测试分两步：先建立 SSE 连接获取 sessionId，再用该 sessionId 发送 JSON-RPC 请求。

### Step 1：获取 SessionId

```bash
curl -s --max-time 3 http://localhost:8080/sse
```

输出示例：
```
id: abc-123
event: endpoint
data: /mcp/message?sessionId=abc-123-...
```

提取 `sessionId=...` 部分，后续请求都带上这个参数。

### Step 2：MCP 握手（initialize）

```bash
curl -X POST "http://localhost:8080/mcp/message?sessionId=<你的sessionId>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": {"name": "test", "version": "1.0"}
    }
  }'
```

> 响应通过 SSE 推送回来，HTTP 返回 200 空响应体是正常的。

### Step 3：查询工具列表（tools/list）

```bash
curl -X POST "http://localhost:8080/mcp/message?sessionId=<你的sessionId>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 2, "method": "tools/list"}'
```

### Step 4：调用 calculator 工具

```bash
curl -X POST "http://localhost:8080/mcp/message?sessionId=<你的sessionId>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "calculator",
      "arguments": {"operation": "multiply", "a": 12, "b": 34}
    }
  }'
```

### Step 5：调用 weather 工具

```bash
curl -X POST "http://localhost:8080/mcp/message?sessionId=<你的sessionId>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "weather",
      "arguments": {"city": "北京", "unit": "celsius"}
    }
  }'
```

### Step 6：健康检查（ping）

```bash
curl -X POST "http://localhost:8080/mcp/message?sessionId=<你的sessionId>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 5, "method": "ping"}'
```

---

## MCP 交互时序图

```
客户端                              服务端(8080)
  │                                     │
  │── GET /sse ─────────────────────────▶│  建立 SSE 长连接
  │◀── event: endpoint ─────────────────│  推送 POST 地址 + sessionId
  │                                     │
  │── POST /mcp/message (initialize) ──▶│  握手请求
  │◀── SSE: {result: serverInfo} ───────│  握手响应（工具能力声明）
  │                                     │
  │── POST /mcp/message (initialized) ─▶│  握手确认通知
  │                                     │
  │── POST /mcp/message (tools/list) ──▶│  查询工具列表
  │◀── SSE: {result: [calculator,...]} ─│  返回工具列表（含 JSON Schema）
  │                                     │
  │── POST /mcp/message (tools/call) ──▶│  调用工具（含参数）
  │◀── SSE: {result: {content: [...]}} ─│  返回工具执行结果
  │                                     │
  │── 断开 SSE 连接 ─────────────────────▶│  会话结束
```

---

## 添加新工具

在 `mcp-server` 中新增工具只需三步：

**1. 新建工具类**（参考 `CalculatorTool.java`）：

```java
@Component
public class MyTool {
    public McpServerFeatures.SyncToolSpecification buildToolSpec() {
        // 定义 inputSchema、Tool 元信息、Handler
    }
}
```

**2. 在 `McpServerConfig` 中注入并注册**：

```java
@Bean
public McpSyncServer mcpSyncServer(..., MyTool myTool) {
    return McpServer.sync(transportProvider)
        .tools(calcSpec, weatherSpec, myTool.buildToolSpec())  // 加这里
        .build();
}
```

**3. 重启服务端即可**，客户端无需任何修改，`tools/list` 自动包含新工具。

---

## 常见问题

**Q: 服务端启动失败，提示 Jackson NoSuchMethodError**  
A: 检查 `pom.xml` 中 Jackson 三件套是否显式声明为 2.19.0，MCP SDK 0.10.0 不兼容 Spring Boot 默认的 2.18.x。

**Q: 客户端连接 500 错误**  
A: 服务端用的是旧进程（Jackson 版本错误）。查端口 `netstat -ano | findstr :8080`，杀掉后重新启动。

**Q: curl 发送 POST 后没有响应体**  
A: 正常。MCP 的响应通过 SSE 连接推送，不在 HTTP 响应体中。需要同时在另一个终端保持 `GET /sse` 连接才能接收到推送内容。
