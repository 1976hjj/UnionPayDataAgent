package com.company.paymentanalysis.artifact.model;

/** Public metadata for a downloadable file; the server path is kept out of the payload. */
public record FileArtifactPayload(
        String sourceArtifactId,
        String fileName,
        FileFormat format,
        String mediaType,
        long sizeBytes,
        String fileChecksum) {

    public enum FileFormat { CSV, XLSX }
}
