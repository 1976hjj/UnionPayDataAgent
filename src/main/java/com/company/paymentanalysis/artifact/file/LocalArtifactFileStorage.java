package com.company.paymentanalysis.artifact.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Local-disk implementation. Only opaque relative keys ever leave this boundary. */
@Service
public class LocalArtifactFileStorage implements ArtifactFileStorage {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("csv", "xlsx");
    private final Path root;

    public LocalArtifactFileStorage(@Value("${artifact.file-storage.root:data/artifacts/files}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(String extension, byte[] content) {
        String normalizedExtension = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(normalizedExtension)) {
            throw new IllegalArgumentException("不支持的导出文件扩展名");
        }
        if (content == null) {
            throw new IllegalArgumentException("导出文件内容不能为空");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String storageKey = id.substring(0, 2) + "/" + id + "." + normalizedExtension;
        Path target = resolve(storageKey);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredFile(storageKey, content.length, sha256(content));
        } catch (IOException exception) {
            safeDelete(temporary);
            throw new IllegalStateException("导出文件写入失败", exception);
        }
    }

    @Override
    public Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || storageKey.contains("\\")) {
            throw new IllegalArgumentException("文件存储标识无效");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("文件存储标识越界");
        }
        return resolved;
    }

    @Override
    public void delete(String storageKey) {
        safeDelete(resolve(storageKey));
    }

    private void safeDelete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Cleanup is best effort; a later retention job can remove orphaned files.
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }
}
