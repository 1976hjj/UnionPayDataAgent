package com.company.paymentanalysis.permission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "chat.memory.redis-enabled=false")
class PermissionManagementRepositoryIntegrationTest {

    @Autowired
    private DataPermissionRepository repository;

    @Test
    void persistsUpdatesAndDeletesTheNewScopeTable() {
        repository.upsertUserDataScope("management-test", "acq_reg_ch", "日本", true);
        assertThat(repository.findUserDataScopes("management-test", true))
                .anySatisfy(scope -> assertThat(scope.dimensionValue()).isEqualTo("日本"));

        assertThat(repository.updateUserDataScopeStatus("management-test", "acq_reg_ch", "日本", false)).isTrue();
        assertThat(repository.findUserDataScopes("management-test", false))
                .anySatisfy(scope -> assertThat(scope.enabled()).isFalse());

        assertThat(repository.deleteUserDataScope("management-test", "acq_reg_ch", "日本")).isTrue();
    }
}
