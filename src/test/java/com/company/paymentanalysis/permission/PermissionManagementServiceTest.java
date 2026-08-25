package com.company.paymentanalysis.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.permission.DataPermissionRepository.PermissionDimension;
import com.company.paymentanalysis.permission.PermissionManagementService.CreateScopesCommand;
import com.company.paymentanalysis.permission.PermissionManagementService.BatchDeleteScopesCommand;
import com.company.paymentanalysis.permission.PermissionManagementService.BatchUpdateScopeStatusCommand;
import com.company.paymentanalysis.permission.PermissionManagementService.CreateCompleteScopesCommand;
import com.company.paymentanalysis.permission.PermissionManagementService.DimensionScopeValues;
import com.company.paymentanalysis.permission.PermissionManagementService.ScopeKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

class PermissionManagementServiceTest {

    @Test
    void authenticatesTheManagementSessionAndUpsertsOnlyCataloguedValues() {
        DataPermissionRepository repository = mock(DataPermissionRepository.class);
        when(repository.enabledDimensions()).thenReturn(List.of(
                new PermissionDimension("acq_reg_ch", true),
                new PermissionDimension("iss_dq_ch", true)));
        PermissionManagementService service = new PermissionManagementService(
                repository, new PermissionManagementProperties("helloworld"));
        MockHttpSession session = new MockHttpSession();

        service.authenticate("helloworld", session);
        service.requireAuthentication(session);
        service.createScopes(new CreateScopesCommand(
                " ZhangSan ", "acq_reg_ch", List.of("中国大陆", "日本"), true));

        assertThat(service.dimensions()).allSatisfy(dimension ->
                assertThat(dimension.values()).contains("中国大陆", "日本", "非洲"));
        verify(repository).upsertUserDataScope("zhangsan", "acq_reg_ch", "中国大陆", true);
        verify(repository).upsertUserDataScope("zhangsan", "acq_reg_ch", "日本", true);
    }

    @Test
    void rejectsAnUnauthenticatedManagementSessionAndUnknownValueDomain() {
        DataPermissionRepository repository = mock(DataPermissionRepository.class);
        when(repository.enabledDimensions()).thenReturn(List.of(new PermissionDimension("acq_reg_ch", true)));
        PermissionManagementService service = new PermissionManagementService(
                repository, new PermissionManagementProperties("helloworld"));

        assertThatThrownBy(() -> service.requireAuthentication(new MockHttpSession()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.createScopes(new CreateScopesCommand(
                "zhangsan", "acq_reg_ch", List.of("不存在"), true)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updatesAndDeletesSelectedScopesInBatches() {
        DataPermissionRepository repository = mock(DataPermissionRepository.class);
        when(repository.enabledDimensions()).thenReturn(List.of(new PermissionDimension("acq_reg_ch", true)));
        when(repository.updateUserDataScopeStatus("zhangsan", "acq_reg_ch", "日本", true)).thenReturn(true);
        when(repository.deleteUserDataScope("zhangsan", "acq_reg_ch", "日本")).thenReturn(true);
        PermissionManagementService service = new PermissionManagementService(
                repository, new PermissionManagementProperties("helloworld"));
        ScopeKey scope = new ScopeKey("zhangsan", "acq_reg_ch", "日本");

        service.updateScopeStatuses(new BatchUpdateScopeStatusCommand(List.of(scope), true));
        service.deleteScopes(new BatchDeleteScopesCommand(List.of(scope)));

        verify(repository).updateUserDataScopeStatus("zhangsan", "acq_reg_ch", "日本", true);
        verify(repository).deleteUserDataScope("zhangsan", "acq_reg_ch", "日本");
    }

    @Test
    void requiresAtLeastOneScopeForEveryRequiredDimensionWhenCreating() {
        DataPermissionRepository repository = mock(DataPermissionRepository.class);
        when(repository.enabledDimensions()).thenReturn(List.of(
                new PermissionDimension("acq_reg_ch", true),
                new PermissionDimension("iss_dq_ch", true)));
        when(repository.findUserDataScopes("zhangsan", null)).thenReturn(List.of());
        PermissionManagementService service = new PermissionManagementService(
                repository, new PermissionManagementProperties("helloworld"));

        assertThatThrownBy(() -> service.createCompleteScopes(new CreateCompleteScopesCommand(
                "zhangsan",
                List.of(new DimensionScopeValues("acq_reg_ch", List.of("中国大陆"))),
                true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("发卡分公司");

        service.createCompleteScopes(new CreateCompleteScopesCommand(
                "zhangsan",
                List.of(
                        new DimensionScopeValues("acq_reg_ch", List.of("中国大陆")),
                        new DimensionScopeValues("iss_dq_ch", List.of("日本"))),
                true));

        verify(repository).upsertUserDataScope("zhangsan", "acq_reg_ch", "中国大陆", true);
        verify(repository).upsertUserDataScope("zhangsan", "iss_dq_ch", "日本", true);
    }
}
