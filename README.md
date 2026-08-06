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
- `SMARTBI_MOCK_ENABLED=true`：所有 SmartBI 查询和健康检查只走应用内 Mock，不会连接或登录真实 SmartBI。
- 仅在公司内网连接真实 SmartBI 时显式设置 `SMARTBI_MOCK_ENABLED=false`。
- Mock SmartBI 路由：`POST /api/mock/smartbi/query`。

### 归因开发用 Mock 数据

应用内置一套确定性的归因测试数据，通过同一个 Mock SmartBI 路由查询：

- 时间范围为 2025-01 至 2026-12，包含 16 个交叉业务分群，覆盖生产元数据中的 71 个维度和 24 个度量。
- 所有非时间维度都映射到同一批事实数据，因此一级成员过滤后再按二级维度查询，数据口径保持一致且仍有多个成员可分析。
- 2026-07 预设为交易量环比下降场景：`收单机构A` 是最大负向驱动；在机构 A 内继续下钻时，`英国` 是最大负向发卡市场。
- 2026-03 预设为商户数下降场景，主因是 `收单机构C`；2026-08 是 7 月下降后的恢复场景，最大正向变化来自 `收单机构A`。
- `_m`、`_hb`、`_tb` 都是 Mock SmartBI 事实中的实际度量字段。归因服务直接读取同比、环比字段，不再自行推导。
- Mock 支持分组、排序及 `EQUALS`、`IN`、范围和 AND/OR 过滤，可用于验证主因排名、贡献方向和条件下钻。

归因计算职责固定如下：SmartBI 提供基础及同比、环比等衍生度量；Java 根据查询证据计算变化额、贡献度、方向、排名和 TopN，并执行查询次数、深度及一致性限制；LLM 不计算这些数值，只选择探索方向、形成假设和解释程序产出的 Evidence。

### 智能归因后端 API

- `GET /api/attribution/metadata`：返回允许归因的基础度量、维度白名单及限制。
- `POST /api/attribution/analyze`：执行 LangGraph4j 动态归因循环。

请求示例：

```json
{
  "metricId": "trans_rmb_amt_m",
  "currentPeriod": "2026-07",
  "comparisonPeriod": "2026-06",
  "dimensionFilters": [],
  "maxDepth": 2,
  "maxQueries": 8,
  "topN": 5,
  "model": "glm-4.7-flash"
}
```

前端 `/attribution` 页面直接使用上述元数据和分析接口。用户配置度量、当前/对比周期、深度、查询次数、TopN 以及可选维度过滤后，由 Agent 自行决定探索维度。结果页分为最终报告、证据明细和 Agent 过程三个视图，支持查看主归因路径、Java 计算的贡献度、LangGraph4j 节点、SmartBI 查询轨迹，以及复制或导出 Markdown 报告。

流程先查询整体变化，再由 LLM 从白名单中选择最多三个首轮维度。每轮 SmartBI 结果由 Java 形成 Evidence；LLM 只能从真实 Evidence、成员和剩余维度中选择下一步。响应包含 `overall`、`evidence`、`primaryPath`、`reasoning`、`stop`、`report`、`workflowSteps` 和完整 `smartBiQueries`，可用于审计每个结论。

覆盖性测试位于 `MockAttributionSmartBiDataServiceTest`。修改 mock 场景后应运行：

```powershell
mvn --% test -Dskip.frontend=true
```

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
