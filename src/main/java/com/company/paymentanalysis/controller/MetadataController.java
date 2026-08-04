package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.query.QueryMetadataCatalog;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

    @GetMapping
    public MetadataResponse metadata() {
        return new MetadataResponse(
                "production-v1",
                QueryMetadataCatalog.metricIds().stream()
                        .map(id -> new Metric(id, QueryMetadataCatalog.displayName(id), "", "number"))
                        .toList(),
                QueryMetadataCatalog.dimensionIds().stream()
                        .map(id -> new Dimension(id, QueryMetadataCatalog.displayName(id)))
                        .toList());
    }

    public record MetadataResponse(
            String version,
            List<Metric> metrics,
            List<Dimension> dimensions) {
    }

    public record Metric(String id, String name, String unit, String format) {
    }

    public record Dimension(String id, String name) {
    }
}
