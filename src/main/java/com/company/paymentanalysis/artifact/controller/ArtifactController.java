package com.company.paymentanalysis.artifact.controller;

import com.company.paymentanalysis.artifact.service.ArtifactAccessService;
import com.company.paymentanalysis.artifact.service.ArtifactAccessService.ArtifactIntegrityException;
import com.company.paymentanalysis.artifact.service.ArtifactAccessService.ArtifactNotFoundException;
import com.company.paymentanalysis.artifact.service.ArtifactAccessService.ArtifactView;
import com.company.paymentanalysis.artifact.service.ArtifactDownloadService;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private final ArtifactAccessService accessService;
    private final ArtifactDownloadService downloadService;

    public ArtifactController(
            ArtifactAccessService accessService,
            ArtifactDownloadService downloadService) {
        this.accessService = accessService;
        this.downloadService = downloadService;
    }

    @GetMapping("/{artifactId}/download")
    public ResponseEntity<?> download(
            @PathVariable String artifactId,
            @RequestParam(defaultValue = "demo-user") String userId) {
        try {
            var download = downloadService.download(userId, artifactId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(download.fileName(), StandardCharsets.UTF_8).build());
            headers.setContentLength(download.sizeBytes());
            return ResponseEntity.ok()
                    .headers(headers)
                    .contentType(MediaType.parseMediaType(download.mediaType()))
                    .body(download.resource());
        } catch (ArtifactNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (ArtifactIntegrityException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/{artifactId}")
    public ArtifactView artifact(
            @PathVariable String artifactId,
            @RequestParam(defaultValue = "demo-user") String userId) {
        try {
            return accessService.get(userId, artifactId);
        } catch (ArtifactNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (ArtifactIntegrityException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/conversations/{conversationId}")
    public List<ArtifactView> conversationArtifacts(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "demo-user") String userId) {
        try {
            return accessService.findByConversation(userId, conversationId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }
}
