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
            └── McpClientDemo.java        # 完整客户端演示（含 AI 驱动）
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

- Java 17+（`java -version` 确认）
- Maven 3.6+（`mvn -version` 确认）

### 第一步：启动 MCP 服务端

在项目根目录执行（前台运行）：

```bash
cd /home/xujiale/javacode/MCP-demo
mvn spring-boot:run -pl mcp-server
```

启动成功后会看到：

```
MCP Server 启动完成
  工具列表: calculator, weather
  SSE 端点:  GET  http://localhost:18080/sse
  消息端点:  POST http://localhost:18080/mcp/message
```

> ⚠️ 服务端口为 **18080**（见 `mcp-server/src/main/resources/application.yml`）

### 第二步：运行客户端 Demo

新开一个终端，在项目根目录执行：

```bash
cd /home/xujiale/javacode/MCP-demo
mvn exec:java -pl mcp-client \
  -Dexec.mainClass="com.example.mcp.client.demo.DirectCallDemo" \
  -Dhttp.proxyHost= -Dhttps.proxyHost= \
  -Dhttp.nonProxyHosts="localhost|127.0.0.1"
```

> ⚠️ 必须加 `-Dhttp.proxyHost=` 等参数，否则系统 `http_proxy` 环境变量会导致对 localhost 的请求经过代理，服务端返回 `Bad Request`。

---

## 停止服务

### 方式一：优雅停止（推荐）

通过 Spring Boot Actuator 触发平滑关闭，不丢失正在处理的请求：

```bash
curl -X POST http://localhost:18080/actuator/shutdown
```

服务端控制台会输出 `Graceful shutdown complete` 后自动退出。

### 方式二：Ctrl+C 强制终止

在运行 `mvn spring-boot:run` 的终端直接按 `Ctrl+C`，进程立即结束。

### 方式三：按 PID 终止

```bash
# 查找进程
lsof -i :18080

# 按 PID 终止
kill <PID>          # 优雅信号 SIGTERM
kill -9 <PID>       # 强制终止 SIGKILL（端口仍被占用时使用）
```

### 验证已停止

```bash
curl -s http://localhost:18080/actuator/health
# 应返回连接失败，说明服务已停止
```

---

## 手动测试（curl）

服务端使用 **MCP over HTTP+SSE** 协议，响应通过 SSE 流推送，手动测试分两步：
先建立 SSE 长连接获取 sessionId，再用该 sessionId 发送 JSON-RPC 请求。

> 💡 **原理**：POST 请求的 HTTP 响应体为空（200），真正的结果从 SSE 连接推送过来。
> 因此测试时需要**同时开两个终端**。

### Step 1：终端 A — 建立 SSE 连接并获取 sessionId

```bash
curl -N http://localhost:18080/sse
```

输出示例：

```
id: abc-123
event: endpoint
data: /mcp/message?sessionId=abc-123-...
```

记录 `sessionId=` 后面的完整值，后续所有请求都需要带上它。  
**保持此终端不要关闭**（SSE 长连接，关闭则 session 失效）。

### Step 2：终端 B — MCP 握手（initialize）

```bash
SESSION="<替换为你的sessionId>"

curl -X POST "http://localhost:18080/mcp/message?sessionId=$SESSION" \
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

在终端 A 的 SSE 流中可以看到服务端返回的握手结果。

### Step 3：发送 initialized 通知

```bash
curl -X POST "http://localhost:18080/mcp/message?sessionId=$SESSION" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "method": "notifications/initialized"}'
```

### Step 4：查询工具列表（tools/list）

```bash
curl -X POST "http://localhost:18080/mcp/message?sessionId=$SESSION" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 2, "method": "tools/list"}'
```

### Step 5：调用 calculator 工具

```bash
curl -X POST "http://localhost:18080/mcp/message?sessionId=$SESSION" \
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

终端 A 中可看到：`"text": "12 * 34 = 408"`

### Step 6：调用 weather 工具

```bash
curl -X POST "http://localhost:18080/mcp/message?sessionId=$SESSION" \
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

### Step 7：健康检查（ping）

```bash
curl -X POST "http://localhost:18080/mcp/message?sessionId=$SESSION" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 5, "method": "ping"}'
```

---

## MCP 交互时序图

```
客户端                              服务端(:18080)
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
A: 服务端用的是旧进程（Jackson 版本错误）。先停止服务（actuator shutdown 或 Ctrl+C），再重新 `mvn spring-boot:run -pl mcp-server`。

**Q: curl 发送 POST 后没有响应体**  
A: 正常。MCP 的响应通过 SSE 连接推送，不在 HTTP 响应体中。需要同时在另一个终端保持 `curl -N http://localhost:18080/sse` 连接才能接收到推送内容。

**Q: 提示 Session not found**  
A: SSE 连接已断开（session 失效）。重新执行 `curl -N http://localhost:18080/sse` 获取新的 sessionId。

**Q: `mvn` 命令找不到**  
A: 执行 `source ~/.bashrc` 重新加载配置（已将 Maven 路径加入 PATH），或新开一个终端。
