package com.company.paymentanalysis.permission;

import java.util.List;
import java.util.Map;

/** Authoritative selectable value domains for the data-scope management screen. */
public final class PermissionValueDomainCatalog {

    private static final List<String> REGIONAL_SCOPE_VALUES = List.of(
            "拉美", "韩国", "日本", "中东", "南亚", "欧洲", "香港", "其它", "俄罗斯",
            "北美", "中亚", "台湾", "东南亚", "蒙古", "南太", "非洲", "中国大陆");

    private static final Map<String, List<String>> VALUES_BY_DIMENSION = Map.of(
            "acq_reg_ch", REGIONAL_SCOPE_VALUES,
            "iss_dq_ch", REGIONAL_SCOPE_VALUES);

    private PermissionValueDomainCatalog() {
    }

    public static List<String> valuesFor(String dimensionId) {
        return VALUES_BY_DIMENSION.getOrDefault(dimensionId, List.of());
    }
}
