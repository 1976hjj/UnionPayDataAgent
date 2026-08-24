# 业务黑话语义规则召回与标准化方案

## 1. 文档用途

本文档是后续交给 Codex 实施的技术方案和验收依据。本文档只定义设计，不代表相关业务代码已经完成。

目标是在现有“度量、维度、值域”三类元数据检索之前，引入第 4 类“业务语义规则”知识文档，解决以下问题：

- 英文缩写和业务黑话无法依靠通用模型稳定理解，例如 `VCC`。
- 一个业务词实际表示完整过滤规则，而不是单个维度或单个值。
- “总体业务情况”等模糊表达实际表示配置化指标集合，不应由大模型自由展开。
- 第一阶段 LLM 可能把黑话放入不同槽位，导致按槽位检索时无法召回。
- 同一句话可能包含多个黑话，需要分别检索并合并结果。

本方案的核心原则是：

1. LLM 负责从自然语言中提取原文业务元素。
2. 业务语义规则知识库负责解释黑话。
3. 后端负责去重、置信度控制和确定性语义改写。
4. 现有度量、维度、值域知识库继续负责字段映射证据。
5. 最终字段 ID 和过滤条件仍必须通过现有字段白名单及查询校验。

## 2. 当前流程

当前查数解析位于 `ChatQueryInterpreter`：

```text
用户原话 + 当前 QueryContext + 上轮待补齐语义
  → 第一次 LLM：查询语义清单提取器
  → 按 metricTerms/groupTerms/filterTerms 查询三类元数据
  → 第二次 LLM：支付数据查询状态生成器
  → QueryState 校验
  → SmartBI QueryRequest
```

当前第一阶段结构：

```json
{
  "metricTerms": ["度量原词"],
  "groupTerms": ["分组维度原词"],
  "filterTerms": [
    {
      "dimensionTerm": "过滤维度原词",
      "operator": "EQUALS",
      "values": ["用户原值"],
      "context": "过滤条件原句"
    }
  ],
  "sortTerms": [],
  "unmappedTerms": []
}
```

当前 `RagflowMetadataTool.retrieveForQuery()` 只读取：

- `metricTerms`：查询度量文档。
- `groupTerms` 和 `filterTerms.dimensionTerm`：查询维度文档。
- `filterTerms` 的维度和值：查询值域文档。
- `unmappedTerms` 当前不参与检索。

因此一旦第一阶段归槽不准确，黑话可能无法从正确文档召回。

## 3. 最终目标流程

```text
用户原话
  ↓
第一阶段 LLM：输出完整语义清单，同时提取 searchTerms
  ↓
后端构造语义规则检索词
  - 完整用户原话（固定加入）
  - 第一阶段 searchTerms
  - 归一化、去空、去重
  ↓
并行查询第 4 个“业务语义规则”文档
  ↓
按 knowledgeId 合并候选
  ↓
后端按高置信受控规则标准化 semanticIntent
  - FILTER_RULE 转换为单一标准过滤条件
  - FILTER_BUNDLE 转换为保留 AND/OR 关系的组合过滤条件
  - METRIC_BUNDLE 展开为明确 metricTerms
  ↓
使用标准化后的 semanticIntent 查询现有三类文档
  - 度量
  - 维度
  - 值域
  ↓
第二阶段 LLM：生成完整 QueryState
  ↓
后端白名单、格式、完整性和弱证据校验
  ↓
确认并执行 SmartBI
```

不新增额外 LLM 调用。原有第一次 LLM 同时承担 `searchTerms` 提取，业务规则解释和改写由知识库与后端完成。

## 4. 知识文档规划

保留现有三个文档：

1. 度量文档。
2. 维度文档。
3. 值域文档。

新增一个文档：

4. 业务语义规则文档。

黑话规则和指标包规则放在同一个业务语义规则文档中，通过 `conceptType` 区分。不要再单独建立“黑话文档”和“指标包文档”。

建议知识源使用 JSONL：一行一个完整 JSON 对象，一条规则形成一个独立 chunk。不得把多条规则拼进同一个 chunk。

## 5. 业务语义规则统一结构

### 5.1 通用字段

```json
{
  "knowledgeId": "全局唯一且长期稳定的规则ID",
  "term": "标准业务术语",
  "aliases": ["用户可能使用的别名"],
  "conceptType": "FILTER_RULE|FILTER_BUNDLE|METRIC_BUNDLE",
  "description": "面向业务维护者的简短说明",
  "semanticRewrite": {},
  "targetConstraint": {},
  "requiresConfirmation": false,
  "confirmationDisplay": "可选的业务确认摘要；最终仍以逐项渲染 semanticRewrite 为准",
  "confidence": "HIGH|MEDIUM|LOW",
  "examples": ["典型用户表达"],
  "negativeExamples": ["不应命中的表达"],
  "enabled": true,
  "version": "1.0"
}
```

约束：

- `knowledgeId` 是召回合并主键，更新文案时不得随意变化。
- `term` 和 `aliases` 用于检索；别名集中维护在同一条规则中。
- `semanticRewrite` 用于第一阶段语义标准化，不包含自由文本指令。
- `targetConstraint` 用于第二阶段映射证据和后端校验。
- `targetConstraint` 中的字段 ID 必须属于 `QueryMetadataCatalog` 白名单。
- 确认页必须逐项渲染命中规则实际生成的度量和过滤条件；`confirmationDisplay` 只能作为摘要，不能代替或隐藏明细。
- `enabled=false` 的规则不得进入候选。
- `confidence` 是知识规则自身置信等级，不等同于 RAG 相似度。

### 5.2 B2B 产品过滤规则示例

当前对话 JSON 可以明确证明：`B2B产品标识/BB标识 = bi_tag 字段处于 [10,20] 区间`。

`VCC/虚拟商务卡` 不是这条单条件规则的普通别名。业务已确认 VCC 是一个独立组合规则：除本条件外，还必须同时增加发卡机构代码条件。

```json
{"knowledgeId":"semantic_b2b_product_range_001","term":"B2B产品标识","aliases":["BB标识","B2B产品"],"conceptType":"FILTER_RULE","description":"B2B产品相关查询按B2B产品标识10到20闭区间筛选。","semanticRewrite":{"filterTerms":[{"dimensionTerm":"B2B产品标识","operator":"BETWEEN","values":["10","20"]}]},"targetConstraint":{"dimensionFilters":[{"dimensionId":"bi_tag","operator":"BETWEEN","values":["10","20"]}]},"requiresConfirmation":false,"confidence":"HIGH","enabled":true,"version":"1.0"}
```

### 5.3 VCC 组合过滤规则示例

业务已确认 `VCC/虚拟商务卡` 同时表示以下两个条件，关系固定为 AND：

- `bi_tag BETWEEN [10,20]`。
- `iss_ins_cde IN [22090702,47300702]`。

确认页必须把两个条件都展示出来，不得只显示“VCC”或静默省略其中一个条件。

```json
{"knowledgeId":"semantic_vcc_filter_bundle_001","term":"VCC","aliases":["虚拟商务卡"],"conceptType":"FILTER_BUNDLE","description":"VCC同时按B2B产品标识范围和发卡机构代码集合筛选。","semanticRewrite":{"relation":"AND","filterTerms":[{"dimensionTerm":"B2B产品标识","operator":"BETWEEN","values":["10","20"]},{"dimensionTerm":"发卡机构代码","operator":"IN","values":["22090702","47300702"]}]},"targetConstraint":{"relation":"AND","dimensionFilters":[{"dimensionId":"bi_tag","operator":"BETWEEN","values":["10","20"]},{"dimensionId":"iss_ins_cde","operator":"IN","values":["22090702","47300702"]}]},"requiresConfirmation":true,"confidence":"HIGH","enabled":true,"version":"1.0"}
```

### 5.4 “总体业务情况”指标包示例

业务已确认把“总体业务情况”作为通用规则，统一解释为以下三个指标，不要求 VCC/虚拟商务卡上下文：

- 总交易笔数：`trans_cnt_m`。
- 承兑笔数：`acpt_cnt_m`。
- 人民币承兑金额：`acpt_trans_rmb_amt_m`。

指标集合由规则固定配置，不能由大模型自行增加。

```json
{"knowledgeId":"semantic_business_overview_metrics_001","term":"总体业务情况","aliases":["总体业务","整体业务情况"],"conceptType":"METRIC_BUNDLE","description":"总体业务情况统一展开为三个固定度量。","matchPolicy":{"type":"ANY_ALIAS","caseSensitive":false},"semanticRewrite":{"metricTerms":["总交易笔数","承兑笔数","人民币承兑金额"]},"targetConstraint":{"metricIds":["trans_cnt_m","acpt_cnt_m","acpt_trans_rmb_amt_m"]},"requiresConfirmation":true,"confidence":"HIGH","enabled":true,"version":"1.0"}
```

### 5.5 汇款交易过滤规则示例

业务已确认“汇款交易”的统一口径为 `trans_nm EQUALS 汇款交易`。

```json
{"knowledgeId":"semantic_remittance_transaction_001","term":"汇款交易","aliases":[],"conceptType":"FILTER_RULE","semanticRewrite":{"filterTerms":[{"dimensionTerm":"交易代码名称","operator":"EQUALS","values":["汇款交易"]}]},"targetConstraint":{"dimensionFilters":[{"dimensionId":"trans_nm","operator":"EQUALS","values":["汇款交易"]}]},"requiresConfirmation":false,"confidence":"HIGH","enabled":true,"version":"1.0"}
```

## 6. 第一阶段协议修改

在 `RawSemanticIntent` 和第一阶段 JSON 中新增 `searchTerms`：

```json
{
  "searchTerms": [
    {
      "text": "业务原文片段",
      "context": "该片段所在的原句或局部上下文"
    }
  ],
  "metricTerms": [],
  "groupTerms": [],
  "filterTerms": [],
  "sortTerms": [],
  "unmappedTerms": []
}
```

第一阶段提示词增加以下规则：

```text
额外输出 searchTerms，提取用户原话中可能具有独立业务含义的原始片段，包括业务简称、英文缩写、中文业务名称、业务口径和汇总表达。

searchTerms.text 必须直接来自用户原话，不得生成原话中不存在的同义词，不得选择字段 ID，不得填写映射结果。

同一表达中的英文简称和中文解释应分别保留。例如“VCC（虚拟商务卡）总体业务情况”提取 VCC、虚拟商务卡、总体业务情况。

普通语气词、查询动作词以及没有独立业务意义的单字不进入 searchTerms。
```

示例输出：

```json
{
  "searchTerms": [
    {"text":"VCC","context":"VCC（虚拟商务卡）"},
    {"text":"虚拟商务卡","context":"VCC（虚拟商务卡）"},
    {"text":"总体业务情况","context":"总体业务情况"}
  ],
  "metricTerms": ["总体业务情况"],
  "groupTerms": [],
  "filterTerms": [
    {
      "dimensionTerm": "年",
      "operator": "EQUALS",
      "values": ["2026"],
      "context": "今年至今"
    },
    {
      "dimensionTerm": "VCC（虚拟商务卡）",
      "operator": "EQUALS",
      "values": ["VCC（虚拟商务卡）"],
      "context": "VCC（虚拟商务卡）"
    }
  ],
  "sortTerms": [],
  "unmappedTerms": ["至今"]
}
```

后端必须校验 `searchTerms.text` 确实存在于用户原话中。不存在的项直接丢弃，不能用于知识库检索。

## 7. 第 4 文档召回规则

### 7.1 检索词生成

后端固定将完整用户原话加入检索集合，再加入第一阶段 `searchTerms.text`：

```json
[
  "今年至今VCC（虚拟商务卡）总体业务情况",
  "VCC",
  "虚拟商务卡",
  "总体业务情况"
]
```

处理规则：

- 去除首尾空白。
- 空字符串不检索。
- 大小写归一只用于去重，不改变传给 RAGFlow 的原词。
- 完全相同的词只查询一次。
- 必须保留完整原话作为 LLM 漏切时的兜底。

### 7.2 调用方式

每个检索词分别查询业务语义规则 document ID，各请求并行执行。不要把全部词拼成一个长 `question`，避免一个高相似术语压制其他黑话。

概念请求：

```json
{
  "question": "VCC",
  "dataset_ids": ["RAGFlow知识库ID"],
  "document_ids": ["业务语义规则文档ID"],
  "page_size": 5,
  "similarity_threshold": 0.2
}
```

第四个文档与度量、维度、值域文档共用同一套连接、鉴权和知识库配置，只需增加文档 ID：

```text
RAGFLOW_BUSINESS_SEMANTICS_DOCUMENT_ID
```

业务语义规则保留独立的高置信度阈值，返回数量固定为 Top 5：

```text
BUSINESS_SEMANTICS_SIMILARITY_THRESHOLD
```

`RAGFLOW_MOCK_ENABLED=true` 时，四类文档全部使用本地 Mock；设置为 `false` 后，四类文档全部调用公司 retrieval 地址。业务语义远程调用失败时回退本地 JSONL。远程文档应确保每个知识块是一条完整的规则 JSON，避免一条规则被切成多个 chunk。

### 7.3 候选结构

```json
{
  "knowledgeId": "semantic_b2b_product_range_001",
  "matchedTerms": ["B2B产品标识", "BB标识"],
  "bestScore": 0.99,
  "conceptType": "FILTER_RULE",
  "semanticRewrite": {
    "filterTerms": [
      {
        "dimensionTerm": "B2B产品标识",
        "operator": "BETWEEN",
        "values": ["10", "20"]
      }
    ]
  },
  "targetConstraint": {
    "dimensionFilters": [
      {
        "dimensionId": "bi_tag",
        "operator": "BETWEEN",
        "values": ["10", "20"]
      }
    ]
  },
  "confidence": "HIGH",
  "requiresConfirmation": false,
  "source": "ragflow:业务语义规则文档ID"
}
```

### 7.4 按 knowledgeId 合并

同一个规则可能被完整原话、英文简称和中文名称分别召回。必须按 `knowledgeId` 合并：

- 保留最高相似度为 `bestScore`。
- 合并并去重所有 `matchedTerms`。
- `semanticRewrite`、`targetConstraint`、`confidence` 以知识文档中解析出的规则为准。
- 同一 `knowledgeId` 返回互相冲突的规则内容时，将候选标记为无效并写审计日志，不得任选一条。

示例合并结果：

```json
{
  "glossaryCandidates": [
    {
      "knowledgeId": "semantic_b2b_product_range_001",
      "matchedTerms": ["B2B产品标识", "BB标识"],
      "bestScore": 0.99,
      "conceptType": "FILTER_RULE"
    },
    {
      "knowledgeId": "semantic_business_overview_metrics_001",
      "matchedTerms": ["总体业务情况"],
      "bestScore": 0.98,
      "conceptType": "METRIC_BUNDLE"
    }
  ]
}
```

## 8. 语义标准化规则

新增一个后端确定性组件，例如：

```text
BusinessSemanticNormalizer
```

输入：

- 用户原话。
- 第一阶段 `RawSemanticIntent`。
- 合并后的 `GlossaryCandidate` 集合。

输出：

- 标准化后的 `RawSemanticIntent`。
- 已应用规则记录。
- 待确认规则记录。
- 被拒绝规则及原因。

### 8.1 自动应用条件

规则只有同时满足以下条件时才能自动应用：

1. `enabled=true`。
2. `confidence=HIGH`。
3. 命中的 `matchedTerm` 是用户原话片段。
4. RAG 相似度达到业务语义规则阈值。
5. 目标字段存在于 `QueryMetadataCatalog`。
6. 操作符属于查询协议允许集合。
7. 值数量与操作符匹配，例如 `BETWEEN` 必须恰好两个值。
8. 同一个用户原词没有命中多个互相冲突的高置信规则。

`MEDIUM`、`LOW`、冲突候选或 `requiresConfirmation=true` 的规则需要进入待确认信息。对于指标包，可以先生成查询计划，但必须在实际执行前明确展示展开后的指标；现有查询确认步骤可承担这项确认。

### 8.2 FILTER_BUNDLE：VCC

VCC 规则应用前：

```json
{
  "filterTerms": [
    {
      "dimensionTerm": "VCC（虚拟商务卡）",
      "operator": "EQUALS",
      "values": ["VCC（虚拟商务卡）"],
      "context": "VCC（虚拟商务卡）"
    }
  ]
}
```

应用后必须生成两个 AND 条件：

```json
{
  "filterTerms": [
    {
      "dimensionTerm": "B2B产品标识",
      "operator": "BETWEEN",
      "values": ["10", "20"],
      "context": "VCC（虚拟商务卡）"
    },
    {
      "dimensionTerm": "发卡机构代码",
      "operator": "IN",
      "values": ["22090702", "47300702"],
      "context": "VCC（虚拟商务卡）"
    }
  ]
}
```

不能简单追加一条新过滤条件后保留旧黑话条件，否则第二阶段仍会把旧条件放入 `unresolvedItems`。必须基于命中的原文范围替换对应的旧语义项。

#### 8.2.1 其他 FILTER_BUNDLE

源数据中存在一个黑话对应多个过滤条件的情况，例如“扫微信交易”同时包含交易介质和商户代码范围。此类规则使用 `FILTER_BUNDLE`：

```json
{
  "conceptType": "FILTER_BUNDLE",
  "semanticRewrite": {
    "relation": "AND",
    "filterTerms": [
      {
        "dimensionTerm": "交易介质",
        "operator": "IN",
        "values": ["二维码主扫", "二维码被扫"]
      },
      {
        "dimensionTerm": "商户代码",
        "operator": "IN",
        "values": ["842584073990004", "QRC2B4842000100", "842391073990001"]
      }
    ]
  }
}
```

标准化器必须保留规则中的 `AND/OR` 关系。若当前 QueryState 无法无损表达嵌套关系，该规则只能进入待确认或暂不支持状态，不能把关系静默改成默认 AND。

### 8.3 METRIC_BUNDLE

应用前：

```json
{
  "metricTerms": ["总体业务情况"]
}
```

应用后：

```json
{
  "metricTerms": [
    "总交易笔数",
    "承兑笔数",
    "人民币承兑金额"
  ]
}
```

第一阶段提示词中“一个独立度量原词最多映射一个度量字段”的规则继续保留。指标包在进入第二阶段前已经被展开成多个明确度量原词，因此每个原词仍只映射一个字段。

禁止模型在没有 `METRIC_BUNDLE` 知识证据时自行展开“总体情况”“交易质量”等模糊词。

### 8.4 unmappedTerms 处理

如果某条规则成功覆盖了 `unmappedTerms` 中的原词，应从 `unmappedTerms` 移除对应项。

未被规则覆盖的 `unmappedTerms` 保留，继续进入后续 `unresolvedItems` 和用户澄清流程。

## 9. 标准化后的三类元数据召回

标准化后的语义示例：

```json
{
  "searchTerms": [
    {"text":"VCC","context":"VCC（虚拟商务卡）"},
    {"text":"虚拟商务卡","context":"VCC（虚拟商务卡）"},
    {"text":"总体业务情况","context":"总体业务情况"}
  ],
  "metricTerms": [
    "总交易笔数",
    "承兑笔数",
    "人民币承兑金额"
  ],
  "groupTerms": [],
  "filterTerms": [
    {
      "dimensionTerm": "年",
      "operator": "EQUALS",
      "values": ["2026"],
      "context": "今年至今"
    },
    {
      "dimensionTerm": "B2B产品标识",
      "operator": "BETWEEN",
      "values": ["10", "20"],
      "context": "VCC（虚拟商务卡）"
    },
    {
      "dimensionTerm": "发卡机构代码",
      "operator": "IN",
      "values": ["22090702", "47300702"],
      "context": "VCC（虚拟商务卡）"
    }
  ],
  "sortTerms": [],
  "unmappedTerms": []
}
```

现有三类召回分别得到：

- `总交易笔数`、`承兑笔数`、`人民币承兑金额`的度量候选。
- `年`、`B2B产品标识`和`发卡机构代码`的维度候选。
- 年份值、`bi_tag` 区间边界以及发卡机构代码集合的值域证据。

第二阶段预期 QueryState：

```json
{
  "metricIds": [
    "trans_cnt_m",
    "acpt_cnt_m",
    "acpt_trans_rmb_amt_m"
  ],
  "dimensionIds": [],
  "dimensionFilters": [
    {
      "dimensionId": "sett_dt_Year2",
      "operator": "EQUALS",
      "values": ["2026"]
    },
    {
      "dimensionId": "bi_tag",
      "operator": "BETWEEN",
      "values": ["10", "20"]
    },
    {
      "dimensionId": "iss_ins_cde",
      "operator": "IN",
      "values": ["22090702", "47300702"]
    }
  ],
  "sorts": [],
  "unresolvedItems": []
}
```

## 10. 审计与可观测性

增加以下审计事件，所有事件沿用当前 traceId：

```text
glossary.search.planned
glossary.request
glossary.response
glossary.candidates.merged
semantic.normalization.completed
semantic.normalization.rejected
```

至少记录：

- 原始用户输入。
- 第一阶段 `searchTerms`。
- 实际检索词列表。
- 每个检索词的召回知识 ID 和分数。
- 合并后的知识 ID。
- 应用、待确认和拒绝的规则。
- 标准化前后的 `semanticIntent`。
- 拒绝规则的明确原因。

不得在审计中记录 RAGFlow API key 等凭据。

## 11. 建议代码改造点

实施时先核对实际代码，以下名称是推荐结构，不要求机械照搬：

### 11.1 配置

- 扩展 `RagflowProperties.DocumentIds`，增加 `glossary`。
- 增加 glossary 独立阈值和 page size 配置。
- 在 `application.yml` 增加对应环境变量。

### 11.2 数据模型

- `RawSemanticIntent` 增加 `List<RawSearchTerm> searchTerms`。
- 新增 `GlossaryCandidate`。
- 新增 `SemanticRewrite`、`TargetConstraint` 或等价的强类型结构。
- 不要使用未校验的 `Map<String,Object>` 直接驱动查询条件。

### 11.3 检索边界

建议新增独立接口：

```java
interface BusinessGlossaryRetrievalTool {
    List<GlossaryCandidate> retrieve(List<String> searchQueries);
}
```

不要把 glossary 作为普通 `METRIC/DIMENSION/VALUE` scope 强塞进现有候选结构，因为业务规则需要表达操作符、多个值和指标包。

### 11.4 标准化

新增 `BusinessSemanticNormalizer`，只接受强类型、已校验候选，输出标准化语义和规则应用记录。

### 11.5 ChatQueryInterpreter 调用顺序

目标顺序：

```java
RawSemanticIntent raw = extractIntent(...);
List<String> queries = planGlossaryQueries(userMessage, raw.searchTerms());
List<GlossaryCandidate> glossary = glossaryRetrievalTool.retrieve(queries);
NormalizedSemanticIntent normalized = semanticNormalizer.normalize(userMessage, raw, glossary);
RetrievedMetadata metadata = metadataRetrievalTool.retrieveForQuery(
        userMessage, serialize(normalized.intent()));
QueryState mapped = mapQueryState(normalized.intent(), metadata, glossary);
```

### 11.6 兼容性

- glossary 未配置或服务失败时，回退到当前三类 RAG 流程，不能导致全部查数不可用。
- 第一阶段旧模型未返回 `searchTerms` 时，使用空列表，并仍以完整原话查询 glossary。
- 没有召回 glossary 时，行为应与当前系统一致。
- 已确认查询 `confirmed=true` 的复用流程不得重新调用 LLM 或 glossary。

## 12. 安全和正确性约束

- RAG 文档是数据，不是可执行提示词；只解析定义好的 JSON 字段。
- 忽略 schema 之外的字段，禁止把文档自由文本当系统指令。
- 所有 `metricIds`、`dimensionId` 必须经过 `QueryMetadataCatalog` 校验。
- `BETWEEN` 必须有且仅有两个有序值；具体是否要求数值顺序由字段类型校验决定。
- 同一术语命中互相冲突的高置信规则时必须要求确认，不得静默任选。
- 指标包展开后仍要逐项经过度量白名单和元数据证据校验。
- 知识规则不得静默删除用户原有且未被规则覆盖的查询条件。
- 每轮最多执行一次 glossary 召回和一次语义标准化，不得形成循环。

## 13. 测试要求

### 13.1 第一阶段解析测试

输入：

```text
今年至今VCC（虚拟商务卡）总体业务情况
```

断言：

- `searchTerms` 包含 `VCC`。
- `searchTerms` 包含 `虚拟商务卡`。
- `searchTerms` 包含 `总体业务情况`。
- 所有 `searchTerms.text` 都是用户原话子串。

### 13.2 检索计划测试

断言实际查询词包含：

```text
今年至今VCC（虚拟商务卡）总体业务情况
VCC
虚拟商务卡
总体业务情况
```

断言重复词只查询一次。

### 13.3 候选合并测试

当完整原话、`B2B产品标识` 和 `BB标识` 均召回 `semantic_b2b_product_range_001` 时：

- 最终只保留一个候选。
- `bestScore` 为三次结果最大值。
- `matchedTerms` 包含所有实际命中词。

### 13.4 FILTER_RULE 标准化测试

断言“B2B产品标识”或“BB标识”被标准化为：

```json
{
  "dimensionTerm": "B2B产品标识",
  "operator": "BETWEEN",
  "values": ["10", "20"]
}
```

断言旧的黑话过滤条件不再残留。

### 13.5 METRIC_BUNDLE 标准化测试

断言“总体业务情况”被展开为配置中的三个明确度量，并且不再作为原始模糊度量传给第二阶段。

同时断言该规则不依赖 VCC 上下文；单独出现“总体业务情况”也应按同一配置展开。

### 13.6 FILTER_BUNDLE 标准化测试

断言 `VCC/虚拟商务卡` 同时生成 `bi_tag BETWEEN [10,20]` 和 `iss_ins_cde IN [22090702,47300702]`，两项关系为 AND，确认摘要完整显示两个条件。

断言“扫微信交易”同时生成二维码交易介质过滤和三个商户代码过滤，并保留两个过滤组之间的 AND 关系。若目标 QueryState 不能表达该关系，应返回明确的不支持/待确认结果，不得遗漏其中任一条件。

### 13.7 端到端工作流测试

预期 QueryState：

```json
{
  "metricIds": ["trans_cnt_m","acpt_cnt_m","acpt_trans_rmb_amt_m"],
  "dimensionIds": [],
  "dimensionFilters": [
    {"dimensionId":"sett_dt_Year2","operator":"EQUALS","values":["2026"]},
    {"dimensionId":"bi_tag","operator":"BETWEEN","values":["10","20"]},
    {"dimensionId":"iss_ins_cde","operator":"IN","values":["22090702","47300702"]}
  ],
  "sorts": [],
  "unresolvedItems": []
}
```

在用户未确认前不得调用 SmartBI；确认页应同时展示 VCC 的 B2B 产品标识范围和发卡机构代码集合。指标包场景还应展示展开后的每个度量。

### 13.8 降级测试

- glossary 文档 ID 未配置：沿用当前行为。
- RAGFlow glossary 请求失败：沿用当前行为并记录 fallback。
- 第一阶段没有 `searchTerms`：仍使用完整原话召回。
- 候选 JSON 非法：忽略该候选，不能中断整次查询。
- 字段 ID 不在白名单：拒绝规则并进入澄清。
- 两条高置信规则冲突：不得自动应用。

## 14. 验收标准

实施完成必须满足：

1. 业务语义规则只增加一个文档，总知识文档数量为四类。
2. B2B 产品过滤规则和“总体业务情况”指标包可以维护在同一业务语义规则文档的不同 chunk 中。
3. 第一阶段 LLM 能输出原文 `searchTerms`，不额外增加 LLM 调用。
4. 完整原话和每个 `searchTerm` 分别、并行查询第 4 文档。
5. 多次召回结果按 `knowledgeId` 正确去重。
6. B2B产品标识/BB标识被确定性改写为 `bi_tag BETWEEN [10,20]`；VCC 被确定性改写为该范围与 `iss_ins_cde IN [22090702,47300702]` 两个 AND 条件。
7. “总体业务情况”严格按配置展开，不由模型自由选择指标。
8. 标准化后的语义继续通过现有三类元数据 RAG。
9. 最终 QueryState 通过现有字段白名单与格式校验。
10. glossary 故障不会阻断原有查数能力。
11. 审计日志可以从同一个 traceId 看清切词、召回、合并、改写和最终映射全过程。
12. 新增和现有相关测试全部通过。
13. 当前启用规则只使用已端到端支持的操作符。设计层规划应用内规范操作符 `CONTAINS`、`NOT_CONTAINS`、`STARTS_WITH`；SmartBI 适配层映射到 SDK/`getData` 的实际操作符，并以真实集成测试作为进入运行时允许集合的门槛。SDK 未明确支持集合型 `NOT_IN`，暂不支持。

## 15. 实施顺序

后续 Codex 应按以下顺序实施，每一步完成后运行相关测试：

1. 增加业务语义规则数据模型和配置，但暂不接入主流程。
2. 增加 glossary RAGFlow 召回、并发查询和 `knowledgeId` 去重测试。
3. 扩展第一阶段协议和提示词，新增 `searchTerms` 解析与原文子串校验。
4. 实现 `BusinessSemanticNormalizer`，覆盖 B2B、VCC 双条件、总体业务指标包和汇款交易单元测试。
5. 将 glossary 召回和标准化接入 `ChatQueryInterpreter`，位置必须在三类元数据召回之前。
6. 补充确认摘要和审计事件；VCC 必须逐项显示 `bi_tag` 范围与 `iss_ins_cde` 集合，总体业务必须逐项显示三个度量。
7. 完成端到端、降级、冲突和回归测试。
8. 使用真实 GLM 与真实/测试 RAGFlow 文档跑一次完整调用，保存 traceId 并核对两次 LLM、四类 RAG、最终 QueryState 和 SmartBI 是否按确认门禁执行。
9. 扩展文本匹配操作符时，先增加 `SmartBiOperatorAdapter`；用真实 `AugmentedDataSetForVModule.getData` 分别验证包含、不包含、前缀匹配的请求字面值，通过后再开放对应 QueryState 操作符。

## 16. 暂不包含的范围

以下内容不在第一版实施范围：

- 不把现有度量、维度和值域三个文档合并。
- 不增加独立的第三次 LLM 调用。
- 不允许模型在没有知识规则时自行创建指标包。
- 不实现自动学习或自动写回业务语义规则知识库。
- 不对当前时间解析策略做额外调整。
- 不绕过当前用户确认和 SmartBI 权限注入流程。
