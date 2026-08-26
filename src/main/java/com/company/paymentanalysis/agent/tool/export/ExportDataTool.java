package com.company.paymentanalysis.agent.tool.export;

import com.company.paymentanalysis.agent.tool.AgentTool;
import com.company.paymentanalysis.agent.tool.ToolDescriptor;
import com.company.paymentanalysis.agent.tool.ToolExecutionRequest;
import com.company.paymentanalysis.agent.tool.ToolExecutionResult;
import com.company.paymentanalysis.artifact.file.ArtifactFileStorage;
import com.company.paymentanalysis.artifact.file.ArtifactFileStorage.StoredFile;
import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import com.company.paymentanalysis.artifact.model.FileArtifactPayload;
import com.company.paymentanalysis.artifact.model.FileArtifactPayload.FileFormat;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload;
import com.company.paymentanalysis.artifact.service.ArtifactAccessService;
import com.company.paymentanalysis.artifact.service.ArtifactService;
import com.company.paymentanalysis.artifact.service.ArtifactService.CreateFile;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Deterministically exports one query-result artifact as CSV or XLSX. */
@Service
public class ExportDataTool implements AgentTool {

    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "export-data", "原始数据导出", "将查询结果导出为 CSV 或 XLSX 文件",
            Set.of(ArtifactType.QUERY_RESULT), Set.of(ArtifactType.FILE),
            ToolDescriptor.SideEffect.CREATES_ARTIFACT);

    private final ArtifactAccessService artifactAccessService;
    private final ArtifactService artifactService;
    private final QueryResultExporter exporter;
    private final ArtifactFileStorage fileStorage;

    public ExportDataTool(
            ArtifactAccessService artifactAccessService,
            ArtifactService artifactService,
            QueryResultExporter exporter,
            ArtifactFileStorage fileStorage) {
        this.artifactAccessService = artifactAccessService;
        this.artifactService = artifactService;
        this.exporter = exporter;
        this.fileStorage = fileStorage;
    }

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (request == null || request.inputArtifactIds().size() != 1) {
            throw new IllegalArgumentException("数据导出必须且只能指定一个查询 Artifact");
        }
        String sourceArtifactId = request.inputArtifactIds().get(0);
        FileFormat format = format(request.arguments().get("format"));
        QueryResultArtifactPayload query = artifactAccessService.readPayload(
                request.userId(), sourceArtifactId, ArtifactType.QUERY_RESULT,
                QueryResultArtifactPayload.class);
        QueryResultExporter.ExportedContent content = exporter.export(query, format);
        String fileName = fileName(request.arguments().get("fileName"), content.extension());
        StoredFile stored = fileStorage.store(content.extension(), content.content());
        try {
            FileArtifactPayload payload = new FileArtifactPayload(
                    sourceArtifactId, fileName, format, content.mediaType(),
                    stored.sizeBytes(), stored.checksum());
            Artifact artifact = artifactService.createFile(new CreateFile(
                    request.userId(), request.conversationId(), null, fileName,
                    sourceArtifactId, stored.storageKey(), query.rows().size(), payload));
            return new ToolExecutionResult(
                    "completed", List.of(artifact.artifactId()),
                    Map.of("format", format.name(), "fileName", fileName,
                            "sizeBytes", stored.sizeBytes()));
        } catch (RuntimeException exception) {
            fileStorage.delete(stored.storageKey());
            throw exception;
        }
    }

    private FileFormat format(Object value) {
        String normalized = value == null ? "XLSX" : value.toString().trim().toUpperCase(Locale.ROOT);
        try {
            return FileFormat.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("导出格式只支持 CSV 或 XLSX");
        }
    }

    private String fileName(Object value, String extension) {
        String base = value == null ? "查询结果" : value.toString().trim();
        if (base.toLowerCase(Locale.ROOT).endsWith("." + extension)) {
            base = base.substring(0, base.length() - extension.length() - 1);
        }
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.isBlank()) base = "查询结果";
        if (base.length() > 100) base = base.substring(0, 100).trim();
        return base + "." + extension;
    }
}
