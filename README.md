# 支付数据智能分析平台

第一步工程骨架：Spring Boot + React + Vite。React 构建产物会自动复制到 Spring Boot `static`，最终输出一个可执行 JAR 和一个 Docker 镜像。

## 环境要求

- JDK 17+
- Maven 3.9+
- Docker Engine / Docker Desktop

$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
$env:Path="$env:JAVA_HOME\bin;" + $env:Path
## Maven 验收

```powershell
mvn clean package
```

成功后生成：

```text
target/payment-analysis.jar
```

运行 JAR：

```powershell
java -jar target/payment-analysis.jar
```

Windows 下建议使用项目启动脚本，按 `Ctrl+C` 后会确保 Java 子进程退出并释放 JAR：
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\run.ps1

脚本会强制启用真实 LLM，并从 Windows 当前用户环境变量读取 `LLM_API_KEY`。若本机
6379 端口没有 Redis，脚本会自动启动已安装的便携版 Redis，并在退出应用时一并停止。
Redis AOF 数据保存在 `%LOCALAPPDATA%\UnionPayDataAgent\redis`。

访问：

- 页面：`http://localhost:8080/`
- 健康检查：`http://localhost:8080/api/health`

## Docker 验收

```powershell
docker build -t payment-analysis:dev .
docker run --rm -p 8080:8080 payment-analysis:dev
```

浏览器访问 `http://localhost:8080/`。

## Docker Compose

Compose 会同时启动应用和 Redis，并用命名卷
`payment-analysis-redis-data` 保存对话历史。先构建镜像，然后启动：

```powershell
docker build -t payment-analysis:dev .
docker compose up -d
docker compose ps
```

页面左侧“历史查询”按当前用户和会话 ID 隔离保存。刷新页面后会恢复当前会话，
也可以选择该用户以前的查询继续追问。默认保留 30 天，可通过
`CHAT_MEMORY_TTL_DAYS` 调整。

停止：

```powershell
docker compose down
```

普通 `docker compose down` 不会删除历史数据。只有明确执行
`docker compose down -v` 才会同时删除 Redis 数据卷。

本机直接运行 JAR 时，Redis 连接配置为：

```text
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DATABASE=0
CHAT_MEMORY_REDIS_ENABLED=true
CHAT_MEMORY_TTL_DAYS=30
```

Redis 不可用或 `CHAT_MEMORY_REDIS_ENABLED=false` 时，应用自动使用 JVM 进程内临时会话；
基本对话、当前上下文和历史列表仍可使用，但应用重启后历史会丢失，多实例之间也不共享。

### 本机 Redis 命令

项目启动脚本 `run.ps1` 会自动启动 Redis；需要单独控制时可使用：

```powershell
# 启动（已安装 redis-server 并已加入 PATH 时）
redis-server --bind 127.0.0.1 --port 6379 --appendonly yes

# 检查
Test-NetConnection 127.0.0.1 -Port 6379

# 停止当前本机 6379 端口上的 Redis
Get-NetTCPConnection -LocalPort 6379 -State Listen |
  Select-Object -ExpandProperty OwningProcess |
  ForEach-Object { Stop-Process -Id $_ -Force }
```

若要模拟 Redis 不可用，先执行停止命令，再设置：

```powershell
$env:CHAT_MEMORY_REDIS_ENABLED = "false"
```

## 前后端分开开发

后端：

```powershell
mvn spring-boot:run
```

前端（需本机安装 Node.js 22+）：

```powershell
cd frontend
npm install
npm run dev
```

Vite 开发服务器为 `http://localhost:5173`，并将 `/api` 代理到 Spring Boot 的 `8080` 端口。

## Mock SmartBI 与 LLM 配置

当前默认使用真实 LLM，SmartBI 在正式地址提供前仍使用应用内 Mock 路由：

- `LLM_MOCK_ENABLED=false`：中文查询由真实大模型解析。
- `SMARTBI_BASE_URL=http://localhost:8080`：`SmartBiClient` 暂时调用本应用的 Mock 路由。
- Mock SmartBI 路由：`POST /api/mock/smartbi/query`。

切换为真实 OpenAI-compatible LLM 时设置：

```text
LLM_MOCK_ENABLED=false
LLM_BASE_URL=http://你的模型服务地址
LLM_CHAT_PATH=/v1/chat/completions
LLM_MODEL=模型名称
LLM_API_KEY=可选密钥
```

请求体使用标准结构：`model`、`messages`、`stream=false`。不要把密钥写进项目或镜像。

### 智谱免费 GLM

项目已按智谱 OpenAI-compatible 接口配置，默认参数为：

```text
LLM_BASE_URL=https://open.bigmodel.cn
LLM_CHAT_PATH=/api/paas/v4/chat/completions
LLM_MODEL=glm-4-flash-250414
LLM_JSON_MODE=true
LLM_THINKING_SUPPORTED=false
LLM_THINKING_ENABLED=false
LLM_MAX_TOKENS=2048
LLM_TEMPERATURE=0.1
LLM_MAX_ATTEMPTS=4
LLM_RETRY_DELAY_MS=1200
```

启用真实模型时，在运行环境中另外设置：

```text
LLM_MOCK_ENABLED=false
LLM_API_KEY=从智谱控制台获取的API Key
```

API Key 不应提交到源码、配置文件或 Docker 镜像。免费模型繁忙时可能返回
HTTP 429 / 智谱错误码 1305，客户端会有限重试，最终仍失败时会明确返回错误，
不会静默降级为 Mock。

切换 SmartBI 地址时设置：

```text
SMARTBI_BASE_URL=http://你的SmartBI适配服务
SMARTBI_QUERY_PATH=/实际查询路由
SMARTBI_DATASET_ID=实际数据集ID
```

### SmartBI 正式接口切换

当前业务流程只依赖通用 `SmartBiClient`。客户端同时兼容项目 Mock 的
`requestId + data + metadata` 响应，以及 SmartBI V11 模型取数接口的
`columnLabels + iterator + totalRowCount`（DataIterator）响应。

正式接入时优先只修改环境变量：

```text
SMARTBI_BASE_URL=http://host:port
SMARTBI_QUERY_PATH=/smartbi/smartbix/api/augmentedQuery/data/
SMARTBI_DATASET_ID=实际数据模型ID
SMARTBI_SESSION_COOKIE=JSESSIONID=已登录会话值
SMARTBI_AUTHORIZATION=
```

该 HTTP 接口仅支持已登录会话。若公司环境使用统一认证或网关，可通过
`SMARTBI_AUTHORIZATION` 传入完整的 Authorization 请求头值；密钥和会话值不要写进代码或镜像。
SmartBI 原始 `CellData` 会在客户端适配层转换成按 `columnLabels` 命名的行数据，
LangGraph4j 流程和前端无需感知 HTTP 返回格式。
