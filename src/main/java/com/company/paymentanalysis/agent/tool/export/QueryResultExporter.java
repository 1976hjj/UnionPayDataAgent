package com.company.paymentanalysis.agent.tool.export;

import com.company.paymentanalysis.artifact.model.FileArtifactPayload.FileFormat;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload;

public interface QueryResultExporter {

    ExportedContent export(QueryResultArtifactPayload query, FileFormat format);

    record ExportedContent(byte[] content, String mediaType, String extension) {
        public ExportedContent {
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
