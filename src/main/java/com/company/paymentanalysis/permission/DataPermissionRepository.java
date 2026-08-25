package com.company.paymentanalysis.permission;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataPermissionRepository {

    private final JdbcTemplate jdbcTemplate;

    public DataPermissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PermissionDimension> enabledDimensions() {
        return jdbcTemplate.query("""
                SELECT dimension_id, required
                FROM bi_agent_permission_dimension
                WHERE enabled = 1
                ORDER BY sort_order, dimension_id
                """, (resultSet, rowNumber) -> new PermissionDimension(
                        resultSet.getString("dimension_id"),
                        resultSet.getInt("required") == 1));
    }

    public List<String> enabledValues(String loginUsername, String dimensionId) {
        return jdbcTemplate.queryForList("""
                SELECT dimension_value
                FROM bi_agent_user_data_scope
                WHERE login_username = ?
                  AND dimension_id = ?
                  AND enabled = 1
                ORDER BY dimension_value
                """, String.class, loginUsername, dimensionId);
    }

    public List<UserDataScope> findUserDataScopes(String keyword, Boolean enabled) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
        String pattern = "%" + normalizedKeyword + "%";
        return jdbcTemplate.query("""
                SELECT login_username, dimension_id, dimension_value, enabled, updated_at
                FROM bi_agent_user_data_scope
                WHERE (? = ''
                       OR lower(login_username) LIKE ?
                       OR lower(dimension_id) LIKE ?
                       OR lower(dimension_value) LIKE ?)
                  AND (? IS NULL OR enabled = ?)
                ORDER BY login_username, dimension_id, dimension_value
                """, (resultSet, rowNumber) -> new UserDataScope(
                        resultSet.getString("login_username"),
                        resultSet.getString("dimension_id"),
                        resultSet.getString("dimension_value"),
                        resultSet.getInt("enabled") == 1,
                        timestamp(resultSet.getTimestamp("updated_at"))),
                normalizedKeyword, pattern, pattern, pattern, enabled, enabled == null ? null : enabled ? 1 : 0);
    }

    public void upsertUserDataScope(String loginUsername, String dimensionId, String dimensionValue, boolean enabled) {
        jdbcTemplate.update("""
                INSERT INTO bi_agent_user_data_scope
                    (login_username, dimension_id, dimension_value, enabled, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(login_username, dimension_id, dimension_value)
                DO UPDATE SET enabled = excluded.enabled, updated_at = CURRENT_TIMESTAMP
                """, loginUsername, dimensionId, dimensionValue, enabled ? 1 : 0);
    }

    public boolean updateUserDataScopeStatus(
            String loginUsername, String dimensionId, String dimensionValue, boolean enabled) {
        return jdbcTemplate.update("""
                UPDATE bi_agent_user_data_scope
                SET enabled = ?, updated_at = CURRENT_TIMESTAMP
                WHERE login_username = ? AND dimension_id = ? AND dimension_value = ?
                """, enabled ? 1 : 0, loginUsername, dimensionId, dimensionValue) > 0;
    }

    public boolean deleteUserDataScope(String loginUsername, String dimensionId, String dimensionValue) {
        return jdbcTemplate.update("""
                DELETE FROM bi_agent_user_data_scope
                WHERE login_username = ? AND dimension_id = ? AND dimension_value = ?
                """, loginUsername, dimensionId, dimensionValue) > 0;
    }

    private Instant timestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record PermissionDimension(String dimensionId, boolean required) {
    }

    public record UserDataScope(
            String loginUsername, String dimensionId, String dimensionValue, boolean enabled, Instant updatedAt) {
    }
}
