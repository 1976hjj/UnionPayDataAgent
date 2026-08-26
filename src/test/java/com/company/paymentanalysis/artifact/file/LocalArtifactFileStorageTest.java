package com.company.paymentanalysis.artifact.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalArtifactFileStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesOpaqueFilesInsideTheConfiguredRootAndRejectsTraversal() throws Exception {
        LocalArtifactFileStorage storage = new LocalArtifactFileStorage(temporaryDirectory.toString());

        var stored = storage.store("csv", new byte[] {1, 2, 3});

        assertThat(stored.storageKey()).endsWith(".csv");
        assertThat(stored.checksum()).hasSize(64);
        assertThat(Files.readAllBytes(storage.resolve(stored.storageKey())))
                .containsExactly(1, 2, 3);
        assertThatThrownBy(() -> storage.resolve("../outside.csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越界");
    }
}
