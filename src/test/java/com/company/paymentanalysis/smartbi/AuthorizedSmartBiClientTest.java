package com.company.paymentanalysis.smartbi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.permission.DataPermissionDeniedException;
import com.company.paymentanalysis.permission.DataPermissionService;
import com.company.paymentanalysis.permission.PermissionQueryEnricher;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthorizedSmartBiClientTest {

    @Test
    void neverCallsTheTransportWhenRequiredPermissionsAreMissing() {
        SmartBiClient transport = mock(SmartBiClient.class);
        DataPermissionService permissionService = mock(DataPermissionService.class);
        when(permissionService.resolveRequiredScope("missing-user"))
                .thenThrow(new DataPermissionDeniedException("missing permission"));
        AuthorizedSmartBiClient client = new AuthorizedSmartBiClient(
                transport, permissionService, mock(PermissionQueryEnricher.class));
        QueryRequest request = new QueryRequest("dataset", List.of(), List.of("trans_cnt_m"), List.of(), null);

        assertThatThrownBy(() -> client.prepare("missing-user", request))
                .isInstanceOf(DataPermissionDeniedException.class);
        verifyNoInteractions(transport);
    }
}
