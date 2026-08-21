package com.company.paymentanalysis.permission;

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
                FROM permission_dimension
                WHERE enabled = 1
                ORDER BY sort_order, dimension_id
                """, (resultSet, rowNumber) -> new PermissionDimension(
                        resultSet.getString("dimension_id"),
                        resultSet.getInt("required") == 1));
    }

    public List<String> enabledValues(String loginUsername, String dimensionId) {
        return jdbcTemplate.queryForList("""
                SELECT dimension_value
                FROM user_data_scope
                WHERE login_username = ?
                  AND dimension_id = ?
                  AND enabled = 1
                ORDER BY dimension_value
                """, String.class, loginUsername, dimensionId);
    }

    public record PermissionDimension(String dimensionId, boolean required) {
    }
}
