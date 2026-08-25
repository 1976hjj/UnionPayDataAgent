package com.company.paymentanalysis.permission;

import com.company.paymentanalysis.permission.DataPermissionRepository.PermissionDimension;
import com.company.paymentanalysis.permission.DataPermissionRepository.UserDataScope;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Management-only operations for bi_agent_user_data_scope. */
@Service
public class PermissionManagementService {

    static final String SESSION_ATTRIBUTE = "bi-agent.permission-management.authorized";

    private final DataPermissionRepository repository;
    private final PermissionManagementProperties properties;

    public PermissionManagementService(
            DataPermissionRepository repository, PermissionManagementProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public void authenticate(String password, HttpSession session) {
        if (!properties.password().equals(password)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "管理口令不正确");
        }
        session.setAttribute(SESSION_ATTRIBUTE, Boolean.TRUE);
    }

    public void requireAuthentication(HttpSession session) {
        if (!Boolean.TRUE.equals(session.getAttribute(SESSION_ATTRIBUTE))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先验证管理口令");
        }
    }

    public List<ManagedDimension> dimensions() {
        return repository.enabledDimensions().stream()
                .map(this::managedDimension)
                .toList();
    }

    public List<UserDataScope> scopes(String keyword, Boolean enabled) {
        return repository.findUserDataScopes(keyword, enabled);
    }

    @Transactional
    public void createScopes(CreateScopesCommand command) {
        String loginUsername = normalizeLoginUsername(command.loginUsername());
        ManagedDimension dimension = requireManagedDimension(command.dimensionId());
        List<String> values = command.dimensionValues() == null ? List.of() : command.dimensionValues().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少选择一个授权值域");
        }
        for (String value : values) {
            if (!dimension.values().contains(value)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的授权值域：" + value);
            }
            repository.upsertUserDataScope(loginUsername, dimension.id(), value, command.enabled());
        }
    }

    @Transactional
    public void createCompleteScopes(CreateCompleteScopesCommand command) {
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新增授权内容不能为空");
        }
        String loginUsername = normalizeLoginUsername(command.loginUsername());
        List<DimensionScopeValues> submittedDimensions = command.dimensions() == null
                ? List.of()
                : command.dimensions();
        Map<String, DimensionScopeValues> submittedById;
        try {
            submittedById = submittedDimensions.stream().collect(Collectors.toMap(
                    item -> item.dimensionId() == null ? "" : item.dimensionId().trim(),
                    Function.identity()));
        } catch (IllegalStateException duplicateDimension) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "权限字段不能重复提交");
        }

        List<UserDataScope> existingScopes = repository.findUserDataScopes(loginUsername, null).stream()
                .filter(scope -> scope.loginUsername().equals(loginUsername))
                .toList();
        List<String> missingDimensions = dimensions().stream()
                .filter(ManagedDimension::required)
                .filter(dimension -> existingScopes.stream().noneMatch(scope -> scope.dimensionId().equals(dimension.id())))
                .filter(dimension -> {
                    DimensionScopeValues submitted = submittedById.get(dimension.id());
                    return submitted == null || submitted.dimensionValues() == null
                            || submitted.dimensionValues().stream().noneMatch(value -> value != null && !value.isBlank());
                })
                .map(ManagedDimension::name)
                .toList();
        if (!missingDimensions.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "每个必填权限字段至少选择一个值域：" + String.join("、", missingDimensions));
        }

        boolean hasNewValues = submittedDimensions.stream()
                .anyMatch(item -> item.dimensionValues() != null && item.dimensionValues().stream()
                        .anyMatch(value -> value != null && !value.isBlank()));
        if (!hasNewValues) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少选择一条新增授权");
        }
        for (DimensionScopeValues dimension : submittedDimensions) {
            if (dimension.dimensionValues() != null && !dimension.dimensionValues().isEmpty()) {
                createScopes(new CreateScopesCommand(
                        loginUsername, dimension.dimensionId(), dimension.dimensionValues(), command.enabled()));
            }
        }
    }

    @Transactional
    public void updateScopeStatus(UpdateScopeStatusCommand command) {
        String loginUsername = normalizeLoginUsername(command.loginUsername());
        ManagedDimension dimension = requireManagedDimension(command.dimensionId());
        String value = normalizeValue(command.dimensionValue(), dimension);
        if (!repository.updateUserDataScopeStatus(loginUsername, dimension.id(), value, command.enabled())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "授权记录不存在");
        }
    }

    @Transactional
    public void updateScopeStatuses(BatchUpdateScopeStatusCommand command) {
        List<ScopeKey> scopes = requireScopeKeys(command == null ? null : command.scopes());
        for (ScopeKey scope : scopes) {
            updateScopeStatus(new UpdateScopeStatusCommand(
                    scope.loginUsername(), scope.dimensionId(), scope.dimensionValue(), command.enabled()));
        }
    }

    @Transactional
    public void deleteScope(String sourceLoginUsername, String dimensionId, String sourceDimensionValue) {
        String loginUsername = normalizeLoginUsername(sourceLoginUsername);
        ManagedDimension dimension = requireManagedDimension(dimensionId);
        String value = normalizeValue(sourceDimensionValue, dimension);
        if (!repository.deleteUserDataScope(loginUsername, dimension.id(), value)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "授权记录不存在");
        }
    }

    @Transactional
    public void deleteScopes(BatchDeleteScopesCommand command) {
        for (ScopeKey scope : requireScopeKeys(command == null ? null : command.scopes())) {
            deleteScope(scope.loginUsername(), scope.dimensionId(), scope.dimensionValue());
        }
    }

    private List<ScopeKey> requireScopeKeys(List<ScopeKey> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少选择一条授权记录");
        }
        if (scopes.size() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次最多操作 500 条授权记录");
        }
        return scopes;
    }

    private ManagedDimension managedDimension(PermissionDimension dimension) {
        if (!QueryMetadataCatalog.isDimension(dimension.dimensionId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "权限维度未登记到数据字典：" + dimension.dimensionId());
        }
        List<String> values = PermissionValueDomainCatalog.valuesFor(dimension.dimensionId());
        if (values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "权限维度缺少值域目录：" + dimension.dimensionId());
        }
        return new ManagedDimension(
                dimension.dimensionId(), QueryMetadataCatalog.displayName(dimension.dimensionId()), dimension.required(), values);
    }

    private ManagedDimension requireManagedDimension(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "权限字段不能为空");
        }
        return dimensions().stream()
                .filter(dimension -> dimension.id().equals(dimensionId.trim()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "权限字段未启用或不支持"));
    }

    private String normalizeLoginUsername(String source) {
        if (source == null || source.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OA 用户名不能为空");
        }
        String normalized = source.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9._:-]{1,120}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OA 用户名格式无效");
        }
        return normalized;
    }

    private String normalizeValue(String source, ManagedDimension dimension) {
        if (source == null || source.isBlank() || !dimension.values().contains(source.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "授权值域无效");
        }
        return source.trim();
    }

    public record ManagedDimension(String id, String name, boolean required, List<String> values) {
    }

    public record CreateScopesCommand(
            String loginUsername, String dimensionId, List<String> dimensionValues, boolean enabled) {
    }

    public record DimensionScopeValues(String dimensionId, List<String> dimensionValues) {
    }

    public record CreateCompleteScopesCommand(
            String loginUsername, List<DimensionScopeValues> dimensions, boolean enabled) {
    }

    public record UpdateScopeStatusCommand(
            String loginUsername, String dimensionId, String dimensionValue, boolean enabled) {
    }

    public record ScopeKey(String loginUsername, String dimensionId, String dimensionValue) {
    }

    public record BatchUpdateScopeStatusCommand(List<ScopeKey> scopes, boolean enabled) {
    }

    public record BatchDeleteScopesCommand(List<ScopeKey> scopes) {
    }

}
