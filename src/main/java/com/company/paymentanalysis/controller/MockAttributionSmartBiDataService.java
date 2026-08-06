package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiModels.RelationNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/**
 * Deterministic in-memory SmartBI dataset for attribution development.
 *
 * <p>Every production dimension is present on the same synthetic facts. This is
 * important for attribution tests: filtering a first-level driver and grouping
 * by a second dimension queries the same population instead of unrelated mock
 * arrays. The 24 measures, including month-on-month and year-on-year fields,
 * are materialized on every fact as if SmartBI had calculated them. July 2026
 * intentionally contains a decline led by 收单机构A and, within that driver,
 * 英国发卡交易.
 */
@Service
public class MockAttributionSmartBiDataService {

    private static final Set<String> TIME_DIMENSIONS =
            Set.of("sett_dt_Year2", "sett_dt_Month2", "sett_dt_Day2");
    private static final YearMonth FIRST_PERIOD = YearMonth.of(2025, 1);
    private static final YearMonth LAST_PERIOD = YearMonth.of(2026, 12);
    private static final int SEGMENT_COUNT = 16;
    private static final BigDecimal[] ACQUIRING_SHARES = decimals("0.42", "0.28", "0.18", "0.12");
    private static final BigDecimal[][] ISSUER_MIX = {
        decimals("0.55", "0.20", "0.15", "0.10"),
        decimals("0.15", "0.45", "0.25", "0.15"),
        decimals("0.20", "0.20", "0.45", "0.15"),
        decimals("0.20", "0.25", "0.20", "0.35")
    };

    private static final Set<String> ACQUIRING_DIMENSIONS = Set.of(
            "acq_reg_ch", "acq_mkt_ch", "acq_reg_cde", "reg_nm_lvl_1", "reg_nm_lvl_2",
            "acq_ins_cde", "acq_ins_ch", "acq_sett_curr_cde", "acq_trans_resp_cde", "DEFINITION2",
            "bid2", "org_name2", "rep_reg_ch3", "abd_mkt_ch3", "vip_type2");
    private static final Set<String> ISSUING_DIMENSIONS = Set.of(
            "iss_dq_ch", "iss_sc_ch", "iss_reg_cde", "iss_ins_cde", "ins_ins_ch",
            "iss_sett_curr_cde", "iss_trans_resp_cde", "DEFINITION3", "bid", "org_name",
            "rep_reg_ch2", "abd_mkt_ch2", "vip_type");
    private static final Set<String> MEDIA_DIMENSIONS = Set.of(
            "eci", "mm_sh_sign", "mpay_def", "srv_entry_mod", "ic_cond_cde", "JYJZ_NAME",
            "term_entry_cap", "wallet_id", "name", "token_ind_ch", "card_media_def");

    private static final Map<String, BigDecimal> BASE_METRICS = Map.ofEntries(
            Map.entry("trans_cnt_m", decimal("1000000")),
            Map.entry("trans_amt_m", decimal("80000000")),
            Map.entry("trans_rmb_amt_m", decimal("560000000")),
            Map.entry("acpt_cnt_m", decimal("920000")),
            Map.entry("acpt_trans_amt_m", decimal("73600000")),
            Map.entry("acpt_trans_rmb_amt_m", decimal("515200000")),
            Map.entry("sh_jy_num_m", decimal("80000")),
            Map.entry("sh_cg_num_m", decimal("72000")));

    private static final Map<String, List<String>> REPRESENTATIVE_MEMBERS = Map.ofEntries(
            Map.entry("acq_reg_ch", members("上海分公司", "广东分公司", "北京分公司", "四川分公司")),
            Map.entry("acq_mkt_ch", members("欧洲市场", "亚太市场", "境内市场", "北美市场")),
            Map.entry("acq_reg_cde", members("EU", "AP", "CN", "NA")),
            Map.entry("iss_dq_ch", members("上海发卡分公司", "广东发卡分公司", "北京发卡分公司", "境外发卡分公司")),
            Map.entry("iss_sc_ch", members("英国", "中国大陆", "法国", "美国")),
            Map.entry("iss_reg_cde", members("GB", "CN", "FR", "US")),
            Map.entry("reg_nm_lvl_1", members("上海", "广东", "北京", "四川")),
            Map.entry("reg_nm_lvl_2", members("浦东新区", "深圳市", "朝阳区", "成都市")),
            Map.entry("acq_ins_cde", members("ACQ001", "ACQ002", "ACQ003", "ACQ004")),
            Map.entry("iss_ins_cde", members("ISS001", "ISS002", "ISS003", "ISS004")),
            Map.entry("acq_ins_ch", members("收单机构A", "收单机构B", "收单机构C", "收单机构D")),
            Map.entry("ins_ins_ch", members("发卡机构甲", "发卡机构乙", "发卡机构丙", "发卡机构丁")),
            Map.entry("fwd_ins_cde", members("FWD001", "FWD002", "FWD003", "FWD004")),
            Map.entry("recv_ins_cde", members("RCV001", "RCV002", "RCV003", "RCV004")),
            Map.entry("recv_ins_nm", members("接受机构一", "接受机构二", "接受机构三", "接受机构四")),
            Map.entry("eci", members("05", "06", "07", "空")),
            Map.entry("mm_sh_sign", members("免密", "验密", "小额免密", "未知")),
            Map.entry("trans_nms", members("消费", "预授权", "退货", "取现")),
            Map.entry("acq_pos_cond_cde", members("00", "08", "59", "91")),
            Map.entry("instalment", members("不分期", "3期", "6期", "12期")),
            Map.entry("mpay_def", members("云闪付", "Apple Pay", "Huawei Pay", "非移动支付")),
            Map.entry("resp_cde", members("00", "05", "51", "61")),
            Map.entry("DEFINITION", members("成功", "不予承兑", "余额不足", "超限额")),
            Map.entry("proc_ind", members("正常", "联机", "脱机", "人工")),
            Map.entry("rev_ind", members("非冲正", "冲正", "冲正撤销", "未知")),
            Map.entry("trans_cde", members("S00", "S20", "S30", "S50")),
            Map.entry("trans_nm", members("消费", "消费撤销", "退货", "取现")),
            Map.entry("trans_mod_def", members("联机", "脱机", "无卡", "代授权")),
            Map.entry("channel", members("01", "02", "03", "04")),
            Map.entry("channel_def", members("POS", "线上", "移动端", "ATM")),
            Map.entry("curr_cde", members("156", "840", "978", "826")),
            Map.entry("curr_nm_ch", members("人民币", "美元", "欧元", "英镑")),
            Map.entry("srv_entry_mod", members("芯片", "非接", "二维码", "磁条")),
            Map.entry("ic_cond_cde", members("0", "1", "2", "9")),
            Map.entry("JYJZ_NAME", members("芯片卡", "非接支付", "二维码", "磁条卡")),
            Map.entry("term_entry_cap", members("接触式IC", "非接IC", "二维码", "磁条")),
            Map.entry("wallet_id", members("W001", "W002", "W003", "W004")),
            Map.entry("name", members("云闪付", "Apple Pay", "Huawei Pay", "其他钱包")),
            Map.entry("acq_sett_curr_cde", members("156", "978", "840", "826")),
            Map.entry("iss_sett_curr_cde", members("826", "156", "978", "840")),
            Map.entry("trans_scen_ind", members("境内线下", "境内线上", "跨境线下", "跨境线上")),
            Map.entry("bi_tag", members("非B2B", "B2B采购", "B2B缴费", "其他B2B")),
            Map.entry("token_ind_ch", members("非Token", "Token", "网络Token", "设备Token")),
            Map.entry("iss_trans_resp_cde", members("00", "05", "51", "61")),
            Map.entry("DEFINITION3", members("发卡成功", "发卡拒绝", "余额不足", "发卡超限")),
            Map.entry("acq_trans_resp_cde", members("00", "12", "30", "96")),
            Map.entry("DEFINITION2", members("收单成功", "无效交易", "格式错误", "系统异常")),
            Map.entry("kpi_ind", members("有效", "无效", "待核验", "已剔除")),
            Map.entry("brand", members("银联", "Visa", "Mastercard", "其他品牌")),
            Map.entry("card_bin", members("622202", "621700", "625999", "356833")),
            Map.entry("card_attr_def", members("借记卡", "贷记卡", "准贷记卡", "预付卡")),
            Map.entry("card_media_def", members("实体卡", "虚拟卡", "Token卡", "无卡")),
            Map.entry("card_rank_cde_def", members("普卡", "金卡", "白金卡", "钻石卡")),
            Map.entry("tid", members("T10001", "T10002", "T10003", "T10004")),
            Map.entry("mer_id", members("M10001", "M10002", "M10003", "M10004")),
            Map.entry("mer_addr_nm", members("伦敦百货", "上海商超", "巴黎酒店", "纽约航空")),
            Map.entry("mcc_cde", members("5411", "5812", "4511", "7011")),
            Map.entry("china_mcc_cde_lvl_3", members("综合零售", "餐饮", "航空", "住宿")),
            Map.entry("bid", members("IBID01", "IBID02", "IBID03", "IBID04")),
            Map.entry("org_name", members("发卡会员甲", "发卡会员乙", "发卡会员丙", "发卡会员丁")),
            Map.entry("rep_reg_ch2", members("发卡会员上海", "发卡会员广东", "发卡会员北京", "发卡会员境外")),
            Map.entry("abd_mkt_ch2", members("欧洲", "境内", "亚太", "北美")),
            Map.entry("vip_type", members("银行", "支付机构", "数字银行", "其他")),
            Map.entry("bid2", members("ABID01", "ABID02", "ABID03", "ABID04")),
            Map.entry("org_name2", members("收单会员甲", "收单会员乙", "收单会员丙", "收单会员丁")),
            Map.entry("rep_reg_ch3", members("收单会员上海", "收单会员广东", "收单会员北京", "收单会员境外")),
            Map.entry("abd_mkt_ch3", members("欧洲", "亚太", "境内", "北美")),
            Map.entry("vip_type2", members("银行收单", "专业收单", "支付机构", "其他")));

    public boolean supports(QueryRequest request) {
        return request != null
                && !request.columns().isEmpty()
                && request.columns().stream().allMatch(QueryMetadataCatalog::isMetric)
                && request.rows().stream().allMatch(QueryMetadataCatalog::isDimension)
                && request.filters().stream().allMatch(filter -> QueryMetadataCatalog.isDimension(filter.name()));
    }

    public QueryResponse query(QueryRequest request) {
        List<Fact> facts = facts().stream().filter(fact -> matches(request, fact)).toList();
        Map<List<String>, List<Fact>> groups = new LinkedHashMap<>();
        if (request.rows().isEmpty()) {
            groups.put(List.of(), facts);
        } else {
            for (Fact fact : facts) {
                List<String> key = request.rows().stream().map(fact::dimension).toList();
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fact);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<List<String>, List<Fact>> entry : groups.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            IntStream.range(0, request.rows().size())
                    .forEach(index -> row.put(request.rows().get(index), entry.getKey().get(index)));
            request.columns().forEach(metric -> row.put(metric, aggregate(metric, entry.getValue())));
            rows.add(row);
        }
        rows = sorted(request, rows);
        return new QueryResponse(
                "mock-attribution-smartbi-" + UUID.randomUUID(),
                List.copyOf(rows),
                Map.of(
                        "source", "Deterministic attribution mock dataset",
                        "factCount", facts.size(),
                        "rowCount", rows.size(),
                        "derivedMetrics", "materialized SmartBI fields",
                        "scenario", "2026-07 decline led by 收单机构A / 英国"));
    }

    Map<String, List<String>> memberCatalog() {
        Map<String, List<String>> catalog = new LinkedHashMap<>();
        QueryMetadataCatalog.dimensionIds().stream()
                .filter(id -> !TIME_DIMENSIONS.contains(id))
                .forEach(id -> catalog.put(id, membersFor(id)));
        return Map.copyOf(catalog);
    }

    private List<Fact> facts() {
        List<Fact> result = new ArrayList<>();
        for (YearMonth period = FIRST_PERIOD; !period.isAfter(LAST_PERIOD); period = period.plusMonths(1)) {
            for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
                result.add(createFact(period, segment));
            }
        }
        return result;
    }

    private BigDecimal aggregate(String metric, List<Fact> facts) {
        if (metric.endsWith("_hb") || metric.endsWith("_tb")) {
            BigDecimal weight = facts.stream()
                    .map(fact -> fact.derivedWeights().get(metric))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (weight.signum() == 0) {
                return BigDecimal.ZERO;
            }
            return facts.stream()
                    .map(fact -> fact.metrics().get(metric).multiply(fact.derivedWeights().get(metric)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(weight, 2, RoundingMode.HALF_UP);
        }
        return facts.stream()
                .map(fact -> fact.metrics().get(metric))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private Fact createFact(YearMonth period, int segment) {
        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        BASE_METRICS.keySet().forEach(metric -> metrics.put(metric, baseMetricValue(metric, period, segment)));
        for (String baseMetric : BASE_METRICS.keySet()) {
            materializeDerivedMetric(metrics, weights, baseMetric, "hb", period, segment, 1);
            materializeDerivedMetric(metrics, weights, baseMetric, "tb", period, segment, 12);
        }
        return new Fact(period, segment, Map.copyOf(metrics), Map.copyOf(weights));
    }

    private void materializeDerivedMetric(
            Map<String, BigDecimal> metrics,
            Map<String, BigDecimal> weights,
            String baseMetric,
            String suffix,
            YearMonth period,
            int segment,
            int comparisonMonths) {
        String derivedMetric = baseMetric.substring(0, baseMetric.length() - 1) + suffix;
        BigDecimal current = metrics.get(baseMetric);
        BigDecimal comparison = baseMetricValue(baseMetric, period.minusMonths(comparisonMonths), segment);
        BigDecimal rate = comparison.signum() == 0
                ? BigDecimal.ZERO
                : current.subtract(comparison)
                        .multiply(decimal("100"))
                        .divide(comparison, 6, RoundingMode.HALF_UP);
        metrics.put(derivedMetric, rate);
        weights.put(derivedMetric, comparison);
    }

    private BigDecimal baseMetricValue(String metric, YearMonth period, int segment) {
        BigDecimal base = BASE_METRICS.get(metric);
        if (base == null) {
            throw new IllegalArgumentException("Mock 不支持度量：" + metric);
        }
        int acquiringIndex = segment / 4;
        int issuingIndex = segment % 4;
        int mediaIndex = Math.floorMod(acquiringIndex + issuingIndex, 4);
        BigDecimal segmentShare = ACQUIRING_SHARES[acquiringIndex].multiply(ISSUER_MIX[acquiringIndex][issuingIndex]);
        BigDecimal yearFactor = period.getYear() <= 2025 ? decimal("0.92") : decimal("1.08");
        BigDecimal season = decimal("0.90").add(decimal("0.01").multiply(decimal(period.getMonthValue())));
        BigDecimal shock = scenarioFactor(metric, period, acquiringIndex, issuingIndex, mediaIndex);
        return base.multiply(segmentShare).multiply(yearFactor).multiply(season).multiply(shock);
    }

    private BigDecimal scenarioFactor(
            String metric, YearMonth period, int acquiringIndex, int issuingIndex, int mediaIndex) {
        if (YearMonth.of(2026, 7).equals(period) && !isMerchantMetric(metric)) {
            if (acquiringIndex == 0) {
                return issuingIndex == 0 ? decimal("0.45") : decimal("0.72");
            }
            return switch (acquiringIndex) {
                case 1 -> decimal("1.08");
                case 2 -> decimal("0.86");
                default -> decimal("1.03");
            };
        }
        if (YearMonth.of(2026, 3).equals(period) && isMerchantMetric(metric)) {
            return acquiringIndex == 2 ? decimal("0.55") : acquiringIndex == 1 ? decimal("1.04") : BigDecimal.ONE;
        }
        if (YearMonth.of(2026, 11).equals(period) && metric.contains("trans_") && mediaIndex == 2) {
            return decimal("1.35");
        }
        return BigDecimal.ONE;
    }

    private boolean isMerchantMetric(String metric) {
        return metric.startsWith("sh_jy_num_") || metric.startsWith("sh_cg_num_");
    }

    private boolean matches(QueryRequest request, Fact fact) {
        RelationNode relation = request.relationNode();
        if (relation != null && relation.childNodes() != null && !relation.childNodes().isEmpty()) {
            return matches(relation, fact);
        }
        return request.filters().stream().allMatch(filter -> matches(filter, fact));
    }

    private boolean matches(RelationNode node, Fact fact) {
        if (node.leaf()) {
            return node.filter() != null && matches(node.filter(), fact);
        }
        List<RelationNode> children = node.childNodes() == null ? List.of() : node.childNodes();
        return "OR".equalsIgnoreCase(node.relation())
                ? children.stream().anyMatch(child -> matches(child, fact))
                : children.stream().allMatch(child -> matches(child, fact));
    }

    private boolean matches(Filter filter, Fact fact) {
        String actual = fact.dimension(filter.name());
        List<String> values = filter.values() == null ? List.of() : filter.values();
        return switch (filter.operation().toUpperCase()) {
            case "EQUALS", "IN" -> values.contains(actual);
            case "NOT_EQUALS", "NOT_IN" -> !values.contains(actual);
            case "GREATER", "GREATER_THAN" -> !values.isEmpty() && actual.compareTo(values.get(0)) > 0;
            case "GREATER_EQUALS", "GREATER_THAN_OR_EQUAL" ->
                    !values.isEmpty() && actual.compareTo(values.get(0)) >= 0;
            case "LESS", "LESS_THAN" -> !values.isEmpty() && actual.compareTo(values.get(0)) < 0;
            case "LESS_EQUALS", "LESS_THAN_OR_EQUAL" ->
                    !values.isEmpty() && actual.compareTo(values.get(0)) <= 0;
            case "BETWEEN" -> values.size() >= 2
                    && actual.compareTo(values.get(0)) >= 0
                    && actual.compareTo(values.get(1)) <= 0;
            case "CONTAINS" -> values.stream().anyMatch(actual::contains);
            default -> false;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map<String, Object>> sorted(QueryRequest request, List<Map<String, Object>> rows) {
        if (request.sorts().isEmpty()) {
            return rows;
        }
        Comparator<Map<String, Object>> comparator = null;
        for (var sort : request.sorts()) {
            Comparator<Map<String, Object>> next = Comparator.comparing(
                    row -> comparable(row.get(sort.field())), Comparator.nullsLast(Comparator.naturalOrder()));
            if ("DESC".equalsIgnoreCase(sort.direction())) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return rows.stream().sorted(comparator).toList();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Comparable comparable(Object value) {
        return value instanceof Comparable comparable ? comparable : value == null ? null : value.toString();
    }

    private List<String> membersFor(String dimension) {
        return REPRESENTATIVE_MEMBERS.getOrDefault(
                dimension,
                IntStream.rangeClosed(1, 4)
                        .mapToObj(index -> QueryMetadataCatalog.displayName(dimension) + "样本" + index)
                        .toList());
    }

    private record Fact(
            YearMonth period,
            int segment,
            Map<String, BigDecimal> metrics,
            Map<String, BigDecimal> derivedWeights) {

        String dimension(String field) {
            int acquiringIndex = segment / 4;
            int issuingIndex = segment % 4;
            int mediaIndex = Math.floorMod(acquiringIndex + issuingIndex, 4);
            return switch (field) {
                case "sett_dt_Year2" -> String.valueOf(period.getYear());
                case "sett_dt_Month2" -> period.toString();
                case "sett_dt_Day2" -> period.atDay(segment + 1).toString();
                default -> REPRESENTATIVE_MEMBERS.getOrDefault(
                                field,
                                IntStream.rangeClosed(1, 4)
                                        .mapToObj(index -> QueryMetadataCatalog.displayName(field) + "样本" + index)
                                        .toList())
                        .get(memberIndex(field, acquiringIndex, issuingIndex, mediaIndex));
            };
        }

        private int memberIndex(String field, int acquiringIndex, int issuingIndex, int mediaIndex) {
            if (ACQUIRING_DIMENSIONS.contains(field)) {
                return acquiringIndex;
            }
            if (ISSUING_DIMENSIONS.contains(field)) {
                return issuingIndex;
            }
            if (MEDIA_DIMENSIONS.contains(field)) {
                return mediaIndex;
            }
            return Math.floorMod(field.hashCode() + acquiringIndex * 2 + issuingIndex, 4);
        }
    }

    private static List<String> members(String... values) {
        return List.of(values);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal decimal(int value) {
        return BigDecimal.valueOf(value);
    }

    private static BigDecimal[] decimals(String... values) {
        return java.util.Arrays.stream(values).map(MockAttributionSmartBiDataService::decimal).toArray(BigDecimal[]::new);
    }
}
