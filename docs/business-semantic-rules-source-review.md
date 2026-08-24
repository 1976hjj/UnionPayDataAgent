# 业务语义规则源数据审阅报告

## 1. 本次输入

- `C:\Users\hjjgg\Desktop\BIChat.xlsx`
- `C:\Users\hjjgg\Desktop\json-dataset.txt`
- 当前项目使用的度量、维度、值域源表
- 当前 `QueryMetadataCatalog` 字段白名单

本报告用于解释 `data/ragflow/business-semantic-rules.draft.jsonl` 的生成依据、排除项和上线前待确认内容。

## 2. 源文件质量

### BIChat.xlsx

- 工作表：1 个，名称为 `Sheet1`。
- 数据规模：197 行、2 列，含 1 行表头和约 196 条对话。
- Excel 内部 `sharedStrings.xml` 已经写入大量 Unicode 替换字符 `�`，中文不是终端显示乱码，而是源文件本身发生过错误解码。
- 英文缩写、数字和部分上下文仍可读取，例如 `VCC`、`Starlink`、`HAIDILAO`、IIN 和日期。
- 因中文字符存在不可逆丢失，本次不依据乱码猜测中文别名，只使用能够与完好 JSON 结果交叉印证的内容。

### json-dataset.txt

- 识别出 135 个顶层 JSON 片段。
- 其中 130 个在去除尾随逗号后可解析。
- 5 个存在注释、非法引号或结构错误，未作为启用规则的唯一证据。
- 可解析对象使用了 38 个已知维度字段和 16 个已知度量字段。
- 数据中也存在明显错误，例如无效日期、字段拼写错误、维度放入 columns、度量放入 filters，以及 `TID`、`iss_ins_ch`、`iss_mkt_ch` 等不在当前白名单的字段。

因此本次采用“多条证据一致、字段合法、操作符可执行”才进入草案的保守策略。

## 3. 白名单核对

当前源表实际情况：

- 维度表：71 个维度，与用户说明一致。
- 度量表：24 个度量，用户已确认以这 24 个为最终白名单。
- 当前 Java `QueryMetadataCatalog`：同样是 71 个维度、24 个度量。
- 值域表：10066 条数据行。

用户已于 2026-08-23 确认：此前所说的 21 个度量应更正为 24 个。草案按当前度量表和 `QueryMetadataCatalog` 中一致的 24 个度量进行白名单校验。

## 4. 已进入第 4 文档的规则

草案共 13 条：

| knowledgeId | 业务词 | 类型 | 主要结果 | 状态 |
|---|---|---|---|---|
| semantic_qr_transaction_001 | 二维码交易 | FILTER_RULE | `JYJZ_NAME IN [二维码主扫, 二维码被扫]` | 高置信 |
| semantic_wechat_scan_transaction_001 | 扫微信交易 | FILTER_BUNDLE | 二维码介质 + 3 个商户代码 | 高置信 |
| semantic_moto_transaction_001 | MOTO交易 | FILTER_RULE | `acq_pos_cond_cde = 08` | 高置信 |
| semantic_collection_transaction_001 | 代收交易 | FILTER_RULE | `acq_pos_cond_cde = 28` | 高置信 |
| semantic_airline_mcc_category_001 | 航司类 | FILTER_RULE | `china_mcc_cde_lvl_3 = 航空售票类` | 高置信 |
| semantic_luxury_mcc_category_001 | 奢侈品类 | FILTER_RULE | `china_mcc_cde_lvl_3 = 珠宝、工艺类` | 执行前确认 |
| semantic_b2b_product_range_001 | B2B产品标识/BB标识 | FILTER_RULE | `bi_tag BETWEEN [10,20]` | 高置信 |
| semantic_vcc_filter_bundle_001 | VCC/虚拟商务卡 | FILTER_BUNDLE | `bi_tag BETWEEN [10,20]` 且 `iss_ins_cde IN [22090702,47300702]` | 高置信，执行前展示两项条件 |
| semantic_starlink_issuer_001 | Starlink | FILTER_RULE | `iss_ins_cde = 22090702` | 执行前确认 |
| semantic_business_overview_metrics_001 | 总体业务情况 | METRIC_BUNDLE | 总交易笔数、承兑笔数、人民币承兑金额 | 高置信，执行前展示三个度量 |
| semantic_remittance_transaction_001 | 汇款交易 | FILTER_RULE | `trans_nm = 汇款交易` | 高置信 |
| semantic_fifteen_markets_to_mainland_001 | 15市场至中国大陆的外卡内用POS交易 | FILTER_BUNDLE | 模式 + POS + 发卡15市场 | 执行前确认 |
| semantic_mainland_to_fifteen_markets_001 | 中国大陆至15市场的内卡外用POS交易 | FILTER_BUNDLE | 模式 + POS + 收单15市场 | 执行前确认 |

所有 `targetConstraint` 中的字段 ID 都属于当前 71 个维度或当前度量白名单。

## 5. 没有进入启用草案的内容

VCC、通用“总体业务情况”和“汇款交易”的冲突项已经取得业务确认并进入启用草案：

- VCC 同时应用 `bi_tag BETWEEN [10,20]` 与 `iss_ins_cde IN [22090702,47300702]`，关系为 AND，确认页必须显示两项。
- “总体业务情况”作为通用规则，不要求 VCC 上下文，展开为三个固定度量。
- “汇款交易”统一使用 `trans_nm EQUALS 汇款交易`。

### 非接交易

样例同时使用 `JYJZ_NAME = 手机闪付` 和 `srv_entry_mod STARTWITH 07`。SmartBI SDK 存在 `STARTWITH` 能力，但当前 Chat QueryState 尚未接入，并且两条件之间的业务关系需要确认，因此本规则暂不启用。

### 海底捞

样例使用：

```text
mer_addr_nm LIKE HAIDILAO OR mer_addr_nm LIKE HAI DI LAO
```

不能无损改成 `EQUALS` 或 `IN`。SmartBI SDK 提供的是 `CONTAIN`，历史 JSON 使用的是 `LIKE`；当前 `AugmentedDataSetForVModule.getData` 调用尚无真实集成测试证明应发送哪个字面值，因此暂不启用。实现适配并通过真实接口测试后可补入。

### ME南南/ME南北交易量

样例是两个复杂分支的 OR：

- ME南南：代付/代付通知 + 响应码 + 处理标记 + 排除机构。
- ME南北：`trans_cde = RMT`。

当前查数 QueryState 没有通用嵌套关系树，暂不把它转换成会改变逻辑的平铺过滤。

### 高交换费15市场之间跨境POS

对象的 `filters` 写的是 `外卡跨境用`，但 `relationNode` 写成了 `内卡外用`，同一个 JSON 内部冲突，暂不启用。

### 承兑率、失败率、笔均金额、唯一计数和占比

这些是派生计算，不是当前白名单中的单一度量字段。对话 JSON 有时只返回计算所需基础度量，有时遗漏计算表达式。应先设计 `DERIVED_METRIC` 协议，不能作为普通 `METRIC_BUNDLE` 直接执行。

### 大学直缴、南太移动支付、南太线上商户情况

这些更接近完整查询模板，包含时间、市场、交易类型、分组维度和指标包。部分还使用 `LIKE`。第一版第 4 文档先处理稳定黑话与组合过滤，不把整张报表模板混入同一执行协议。

## 6. 应回填现有三个文档而不是第 4 文档的内容

以下属于一对一普通别名，原则上应维护在原有文档：

- 发卡 IIN → 维度 `iss_ins_cde`。
- 收单 IIN/受理机构 IIN → 维度 `acq_ins_cde`。
- 应答码 → 维度 `resp_cde`。
- POS类交易 → `trans_nms` 的值 `POS`。
- 发卡行名称 → 维度 `ins_ins_ch`。
- 承兑交易笔数 → 度量 `acpt_cnt_m`。
- 承兑人民币金额 → 度量 `acpt_trans_rmb_amt_m`。

一对一字段或值别名不进入第 4 文档，避免与现有度量、维度、值域召回重复竞争。

## 7. 对实现方案的补充要求

源数据表明业务规则不只有单一过滤条件，因此后续实现时第 4 文档至少需要支持：

```text
FILTER_RULE
FILTER_BUNDLE
METRIC_BUNDLE
```

`FILTER_BUNDLE` 必须保留规则内部的 `AND/OR` 关系。不能简单把所有过滤条件平铺成默认 AND。

对于第 4 文档中的值：

- 字段必须通过 71 维度白名单校验。
- 度量必须通过已确认的 24 度量白名单校验。
- 值优先采用 `json-dataset.txt` 中 Query JSON 的结果。
- 值域表中不存在但对话 JSON 明确给出的编号类值，应标记来源为对话证据，不应被模型自行改写。
- 文本值只有一个样例且值域表不存在时，规则应要求确认。

## 8. 已确认项与剩余边界

前五项业务口径已于 2026-08-23 确认并回填草案。第六项按 SmartBI 能力核对结果处理：

- 当前启用规则只使用 `EQUALS`、`IN`、`BETWEEN` 等 `ChatQueryInterpreter` 已允许的操作符。
- `BETWEEN` 在 `SmartBiQueryBuilder` 中会转换为 `GREATER_EQUALS` 与 `LESS_EQUALS` 后发送给 SmartBI。
- 项目本地 SmartBI SDK 的 `smartbi.olap.util.OperatorType` 明确定义了 `CONTAIN`、`NOTCONTAIN`、`STARTWITH`、`BETWEEN` 等能力，因此设计层纳入“包含/不包含/前缀匹配”的支持计划。
- 应用生产调用走 `AugmentedDataSetForVModule.getData`，当前只是把 operation 字符串原样发送；历史 JSON 使用 `LIKE/STARTWITH`，SDK 使用 `CONTAIN/STARTWITH`，仓库没有真实接口测试证明“包含”最终应发送 `LIKE`、`CONTAIN` 还是其他字面值。
- 实施时使用应用内规范名 `CONTAINS`、`NOT_CONTAINS`、`STARTS_WITH`，在 SmartBI 适配层统一转换；每个映射必须先通过真实 `getData` 集成测试才进入运行时允许集合。
- SDK 没有明确的集合型 `NOT_IN`，因此暂不支持 `NOT_IN`，不能仅凭历史样例启用。
- 当前 13 条启用规则不依赖这些扩展操作符，不受此项阻塞。
