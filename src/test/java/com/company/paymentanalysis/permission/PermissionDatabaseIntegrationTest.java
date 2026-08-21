package com.company.paymentanalysis.permission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "chat.memory.redis-enabled=false")
class PermissionDatabaseIntegrationTest {

    @Autowired
    private DataPermissionService permissionService;

    @Test
    void loadsTheSeededMainlandScopeFromSQLite() {
        PermissionScope scope = permissionService.resolveRequiredScope("demo-user");

        assertThat(scope.valuesByDimension()).containsOnlyKeys("acq_reg_ch", "iss_dq_ch");
        assertThat(scope.valuesByDimension().values()).allSatisfy(values -> {
            assertThat(values).hasSize(1);
            assertThat(values.get(0)).isNotBlank();
        });
    }
}
