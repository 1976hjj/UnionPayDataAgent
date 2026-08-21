package com.company.paymentanalysis.permission;

import com.company.paymentanalysis.permission.DataPermissionRepository.PermissionDimension;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class DataPermissionService {

    private final DataPermissionRepository repository;

    public DataPermissionService(DataPermissionRepository repository) {
        this.repository = repository;
    }

    public PermissionScope resolveRequiredScope(String sourceLoginUsername) {
        String loginUsername = normalize(sourceLoginUsername);
        List<PermissionDimension> dimensions = repository.enabledDimensions();
        if (dimensions.isEmpty()) {
            throw new IllegalStateException("未配置任何启用的数据权限维度，已停止 SmartBI 查询");
        }

        LinkedHashMap<String, List<String>> valuesByDimension = new LinkedHashMap<>();
        for (PermissionDimension dimension : dimensions) {
            if (!QueryMetadataCatalog.isDimension(dimension.dimensionId())) {
                throw new IllegalStateException("数据权限包含未登记的维度：" + dimension.dimensionId());
            }
            List<String> values = repository.enabledValues(loginUsername, dimension.dimensionId()).stream()
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .toList();
            if (values.isEmpty()) {
                if (dimension.required()) {
                    throw new DataPermissionDeniedException(
                            "账号 " + loginUsername + " 未配置必需的数据权限：" + dimension.dimensionId());
                }
                continue;
            }
            valuesByDimension.put(dimension.dimensionId(), values);
        }
        return new PermissionScope(loginUsername, valuesByDimension);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new DataPermissionDeniedException("缺少 OA loginusername，已停止 SmartBI 查询");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9._:-]{1,120}")) {
            throw new DataPermissionDeniedException("OA loginusername 格式无效，已停止 SmartBI 查询");
        }
        return normalized;
    }
}
