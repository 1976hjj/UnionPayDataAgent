package com.company.paymentanalysis.artifact.service;

import com.company.paymentanalysis.artifact.file.ArtifactFileStorage;
import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.model.Artifact.StorageType;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import com.company.paymentanalysis.artifact.model.FileArtifactPayload;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

@Service
public class ArtifactDownloadService {

    private final ArtifactAccessService accessService;
    private final ArtifactFileStorage fileStorage;

    public ArtifactDownloadService(
            ArtifactAccessService accessService,
            ArtifactFileStorage fileStorage) {
        this.accessService = accessService;
        this.fileStorage = fileStorage;
    }

    public Download download(String ownerUserId, String artifactId) {
        Artifact artifact = accessService.requireOwnedArtifact(ownerUserId, artifactId);
        if (artifact.artifactType() != ArtifactType.FILE || artifact.storageType() != StorageType.FILE) {
            throw new IllegalArgumentException("该 Artifact 不是可下载文件");
        }
        FileArtifactPayload payload = accessService.readPayload(
                ownerUserId, artifactId, ArtifactType.FILE, FileArtifactPayload.class);
        Path path = fileStorage.resolve(artifact.payloadUri());
        if (!Files.isRegularFile(path)) {
            throw new ArtifactAccessService.ArtifactNotFoundException("导出文件不存在");
        }
        if (!payload.fileChecksum().equals(checksum(path))) {
            throw new ArtifactAccessService.ArtifactIntegrityException("导出文件完整性校验失败");
        }
        return new Download(new FileSystemResource(path), payload.fileName(), payload.mediaType(), payload.sizeBytes());
    }

    private String checksum(Path path) {
        try (InputStream input = Files.newInputStream(path);
             DigestInputStream digestInput = new DigestInputStream(input, MessageDigest.getInstance("SHA-256"))) {
            digestInput.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(digestInput.getMessageDigest().digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ArtifactAccessService.ArtifactIntegrityException("导出文件校验失败", exception);
        }
    }

    public record Download(
            FileSystemResource resource,
            String fileName,
            String mediaType,
            long sizeBytes) {
    }
}
