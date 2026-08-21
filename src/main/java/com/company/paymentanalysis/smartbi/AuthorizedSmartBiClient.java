package com.company.paymentanalysis.smartbi;

import com.company.paymentanalysis.permission.DataPermissionService;
import com.company.paymentanalysis.permission.PermissionQueryEnricher;
import com.company.paymentanalysis.permission.PermissionScope;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import org.springframework.stereotype.Component;

/** The application-level SmartBI boundary: every request must be prepared here first. */
@Component
public class AuthorizedSmartBiClient {

    private final SmartBiClient transport;
    private final DataPermissionService permissionService;
    private final PermissionQueryEnricher queryEnricher;

    public AuthorizedSmartBiClient(
            SmartBiClient transport,
            DataPermissionService permissionService,
            PermissionQueryEnricher queryEnricher) {
        this.transport = transport;
        this.permissionService = permissionService;
        this.queryEnricher = queryEnricher;
    }

    public PreparedQuery prepare(String loginUsername, QueryRequest source) {
        PermissionScope scope = permissionService.resolveRequiredScope(loginUsername);
        return prepare(scope, source);
    }

    public PreparedQuery prepare(PermissionScope scope, QueryRequest source) {
        if (scope == null) {
            throw new IllegalArgumentException("SmartBI 权限范围不能为空");
        }
        return new PreparedQuery(scope.loginUsername(), queryEnricher.apply(source, scope));
    }

    public QueryResponse query(PreparedQuery preparedQuery) {
        if (preparedQuery == null || preparedQuery.request() == null) {
            throw new IllegalArgumentException("SmartBI 授权查询不能为空");
        }
        return transport.query(preparedQuery.request());
    }

    public record PreparedQuery(String loginUsername, QueryRequest request) {
    }
}
