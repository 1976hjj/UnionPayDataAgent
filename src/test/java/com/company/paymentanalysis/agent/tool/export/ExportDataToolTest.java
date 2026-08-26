package com.company.paymentanalysis.agent.tool.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.agent.tool.ToolExecutionRequest;
import com.company.paymentanalysis.artifact.file.ArtifactFileStorage;
import com.company.paymentanalysis.artifact.file.ArtifactFileStorage.StoredFile;
import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.model.Artifact.Status;
import com.company.paymentanalysis.artifact.model.Artifact.StorageType;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Column;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.DataType;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.QueryContract;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Role;
import com.company.paymentanalysis.artifact.service.ArtifactAccessService;
import com.company.paymentanalysis.artifact.service.ArtifactService;
import com.company.paymentanalysis.artifact.service.ArtifactService.CreateFile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExportDataToolTest {

    @Test
    void exportsOneOwnedQueryArtifactAndCreatesAFileArtifact() {
        ArtifactAccessService access = mock(ArtifactAccessService.class);
        ArtifactService artifacts = mock(ArtifactService.class);
        QueryResultExporter exporter = mock(QueryResultExporter.class);
        ArtifactFileStorage files = mock(ArtifactFileStorage.class);
        QueryResultArtifactPayload query = queryResult();
        when(access.readPayload("user", "art_query", ArtifactType.QUERY_RESULT, QueryResultArtifactPayload.class))
                .thenReturn(query);
        when(exporter.export(query, com.company.paymentanalysis.artifact.model.FileArtifactPayload.FileFormat.CSV))
                .thenReturn(new QueryResultExporter.ExportedContent(new byte[] {1, 2}, "text/csv", "csv"));
        when(files.store(org.mockito.ArgumentMatchers.eq("csv"), any(byte[].class)))
                .thenReturn(new StoredFile("ab/file.csv", 2, "file-checksum"));
        when(artifacts.createFile(any())).thenReturn(fileArtifact("art_file"));
        ExportDataTool tool = new ExportDataTool(access, artifacts, exporter, files);

        var result = tool.execute(new ToolExecutionRequest(
                "user", "conversation", List.of("art_query"),
                Map.of("format", "csv", "fileName", "月度/交易")));

        ArgumentCaptor<CreateFile> command = ArgumentCaptor.forClass(CreateFile.class);
        verify(artifacts).createFile(command.capture());
        assertThat(command.getValue().payload().fileName()).isEqualTo("月度_交易.csv");
        assertThat(command.getValue().payload().fileChecksum()).isEqualTo("file-checksum");
        assertThat(command.getValue().sourceArtifactId()).isEqualTo("art_query");
        assertThat(result.outputArtifactIds()).containsExactly("art_file");
        assertThat(tool.descriptor().toolId()).isEqualTo("export-data");
    }

    private QueryResultArtifactPayload queryResult() {
        return new QueryResultArtifactPayload(
                "", List.of(new Column("amount", "金额", Role.METRIC, DataType.NUMBER, "元")),
                List.of(Map.of("amount", 1)), 1, false,
                new QueryContract("dataset", List.of("amount"), List.of(), List.of(), List.of()));
    }

    private Artifact fileArtifact(String id) {
        return new Artifact(
                id, ArtifactType.FILE, 1, Status.COMPLETED, "file", "user", "conversation",
                null, "export-data-tool", StorageType.FILE, "{}", "ab/file.csv",
                "payload-checksum", 1L, Instant.now(), null);
    }
}
