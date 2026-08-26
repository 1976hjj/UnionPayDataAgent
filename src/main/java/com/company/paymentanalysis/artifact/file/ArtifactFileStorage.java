package com.company.paymentanalysis.artifact.file;

import java.nio.file.Path;

public interface ArtifactFileStorage {

    StoredFile store(String extension, byte[] content);

    Path resolve(String storageKey);

    void delete(String storageKey);

    record StoredFile(String storageKey, long sizeBytes, String checksum) {
    }
}
