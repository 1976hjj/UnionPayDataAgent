package com.company.paymentanalysis.artifact.service;

import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.model.AttributionResultArtifactPayload;
import com.company.paymentanalysis.artifact.model.ChartArtifactPayload;
import com.company.paymentanalysis.artifact.model.FileArtifactPayload;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload;

public interface ArtifactService {

    Artifact createQueryResult(CreateQueryResult command);

    Artifact createAttributionResult(CreateAttributionResult command);

    Artifact createChart(CreateChart command);

    Artifact createFile(CreateFile command);

    record CreateQueryResult(
            String ownerUserId,
            String conversationId,
            String sourceMessageId,
            String title,
            QueryResultArtifactPayload payload) {
    }

    record CreateAttributionResult(
            String ownerUserId,
            String conversationId,
            String sourceMessageId,
            String title,
            AttributionResultArtifactPayload payload) {
    }

    record CreateChart(
            String ownerUserId,
            String conversationId,
            String sourceMessageId,
            String title,
            String sourceArtifactId,
            ChartArtifactPayload payload) {
    }

    record CreateFile(
            String ownerUserId,
            String conversationId,
            String sourceMessageId,
            String title,
            String sourceArtifactId,
            String storageKey,
            long rowCount,
            FileArtifactPayload payload) {
    }

    static ArtifactService noOp() {
        return new ArtifactService() {
            @Override
            public Artifact createQueryResult(CreateQueryResult command) {
                return null;
            }

            @Override
            public Artifact createAttributionResult(CreateAttributionResult command) {
                return null;
            }

            @Override
            public Artifact createChart(CreateChart command) {
                return null;
            }

            @Override
            public Artifact createFile(CreateFile command) {
                return null;
            }
        };
    }
}
