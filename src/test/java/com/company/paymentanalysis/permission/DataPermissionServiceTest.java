package com.company.paymentanalysis.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.permission.DataPermissionRepository.PermissionDimension;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataPermissionServiceTest {

    @Test
    void resolvesEveryRequiredDimensionAndNormalizesTheLogin() {
        DataPermissionRepository repository = mock(DataPermissionRepository.class);
        when(repository.enabledDimensions()).thenReturn(List.of(
                new PermissionDimension("acq_reg_ch", true),
                new PermissionDimension("iss_dq_ch", true)));
        when(repository.enabledValues("zhangsan", "acq_reg_ch")).thenReturn(List.of("中国大陆"));
        when(repository.enabledValues("zhangsan", "iss_dq_ch")).thenReturn(List.of("中国大陆"));

        PermissionScope scope = new DataPermissionService(repository).resolveRequiredScope(" ZhangSan ");

        assertThat(scope.loginUsername()).isEqualTo("zhangsan");
        assertThat(scope.valuesByDimension()).containsExactly(
                org.assertj.core.api.Assertions.entry("acq_reg_ch", List.of("中国大陆")),
                org.assertj.core.api.Assertions.entry("iss_dq_ch", List.of("中国大陆")));
    }

    @Test
    void rejectsBeforeSmartBiWhenAnyRequiredDimensionIsMissing() {
        DataPermissionRepository repository = mock(DataPermissionRepository.class);
        when(repository.enabledDimensions()).thenReturn(List.of(
                new PermissionDimension("acq_reg_ch", true),
                new PermissionDimension("iss_dq_ch", true)));
        when(repository.enabledValues("zhangsan", "acq_reg_ch")).thenReturn(List.of("中国大陆"));
        when(repository.enabledValues("zhangsan", "iss_dq_ch")).thenReturn(List.of());

        assertThatThrownBy(() -> new DataPermissionService(repository).resolveRequiredScope("zhangsan"))
                .isInstanceOf(DataPermissionDeniedException.class)
                .hasMessageContaining("iss_dq_ch");
    }
}
