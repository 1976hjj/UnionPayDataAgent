package com.company.paymentanalysis.ragflow;

import static com.company.paymentanalysis.ragflow.MetadataRetrievalTool.Scope.DIMENSION;
import static com.company.paymentanalysis.ragflow.MetadataRetrievalTool.Scope.METRIC;
import static com.company.paymentanalysis.ragflow.MetadataRetrievalTool.Scope.VALUE;

import com.company.paymentanalysis.attribution.AttributionCatalog;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * One retrieval boundary for both chat experiences. In mock mode it reads the
 * actual Excel source tables; remote mode keeps the exact same candidate shape.
 */
@Component
public class RagflowMetadataTool implements MetadataRetrievalTool {

    private static final int METRIC_LIMIT = 3;
    private static final int DIMENSION_LIMIT = 5;
    private static final int VALUE_LIMIT = 5;
    private static final Pattern FIELD_ID = Pattern.compile(
            "(?:指标代码|字段代码|field[_\\s-]?id)\\s*[:：]\\s*([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD_NAME = Pattern.compile(
            "(?:指标名称|字段中文名|中文名称|field[_\\s-]?name)\\s*[:：]\\s*([^;；|\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD_VALUE = Pattern.compile(
            "(?:字段值|value)\\s*[:：]\\s*([^;；|\\n]+)", Pattern.CASE_INSENSITIVE);

    private final RagflowProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private volatile List<Entry> mockIndex;

    public RagflowMetadataTool(
            RagflowProperties properties, RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public RetrievedMetadata retrieveForQuery(String userMessage, String semanticIntent) {
        try {
            JsonNode intent = objectMapper.readTree(semanticIntent);
            List<String> filters = filterTerms(intent.path("filterTerms"));
            List<String> dimensions = textItems(intent.path("groupTerms"));
            for (JsonNode item : intent.path("filterTerms")) {
                add(dimensions, item.path("dimensionTerm").asText());
            }
            return retrieve(new RetrievalPlan(
                    textItems(intent.path("metricTerms")),
                    dimensions,
                    filters), userMessage);
        } catch (RuntimeException | java.io.IOException ignored) {
            return RetrievedMetadata.empty();
        }
    }

    @Override
    public RetrievedMetadata retrieveForAttribution(String userMessage, String semanticIntent) {
        try {
            JsonNode intent = objectMapper.readTree(semanticIntent);
            List<String> metrics = new ArrayList<>();
            add(metrics, intent.path("metricTerm").asText());
            List<String> dimensions = new ArrayList<>();
            for (JsonNode item : intent.path("analysisTerms")) {
                add(dimensions, item.path("term").asText());
            }
            return retrieve(new RetrievalPlan(metrics, dimensions, filterTerms(intent.path("filterTerms"))), userMessage);
        } catch (RuntimeException | java.io.IOException ignored) {
            return RetrievedMetadata.empty();
        }
    }

    private RetrievedMetadata retrieve(RetrievalPlan plan, String userMessage) {
        boolean remote = properties.enabled() && !properties.mockEnabled();
        try {
            List<MetadataCandidate> metrics = retrieveAll(METRIC, plan.metrics(), remote, userMessage, METRIC_LIMIT);
            List<MetadataCandidate> dimensions = retrieveAll(DIMENSION, plan.dimensions(), remote, userMessage, DIMENSION_LIMIT);
            List<MetadataCandidate> values = retrieveAll(VALUE, plan.filters(), remote, userMessage, VALUE_LIMIT);
            return new RetrievedMetadata(metrics, dimensions, values, !remote);
        } catch (RuntimeException exception) {
            // A knowledge-base outage must not stop either existing conversation.
            return retrieveWithMock(plan, userMessage, true);
        }
    }

    private RetrievedMetadata retrieveWithMock(RetrievalPlan plan, String userMessage, boolean fallback) {
        return new RetrievedMetadata(
                retrieveAll(METRIC, plan.metrics(), false, userMessage, METRIC_LIMIT),
                retrieveAll(DIMENSION, plan.dimensions(), false, userMessage, DIMENSION_LIMIT),
                retrieveAll(VALUE, plan.filters(), false, userMessage, VALUE_LIMIT), fallback);
    }

    private List<MetadataCandidate> retrieveAll(
            Scope scope, List<String> terms, boolean remote, String userMessage, int limit) {
        Map<String, MetadataCandidate> unique = new LinkedHashMap<>();
        for (String term : terms) {
            List<MetadataCandidate> candidates = remote ? retrieveRemote(scope, term) : retrieveMock(scope, term);
            for (MetadataCandidate candidate : candidates) {
                String key = candidate.scope() + "|" + candidate.fieldId() + "|" + candidate.value();
                unique.merge(key, candidate, (left, right) -> left.score() >= right.score() ? left : right);
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparingDouble(MetadataCandidate::score).reversed()
                        .thenComparing(MetadataCandidate::fieldId)
                        .thenComparing(candidate -> candidate.value() == null ? "" : candidate.value()))
                .limit(limit)
                .toList();
    }

    private List<MetadataCandidate> retrieveMock(Scope scope, String term) {
        return index().stream()
                .filter(entry -> entry.scope() == scope)
                .map(entry -> entry.toCandidate(entry.score(term)))
                .filter(candidate -> candidate.score() > 0)
                .toList();
    }

    private List<MetadataCandidate> retrieveRemote(Scope scope, String question) {
        String documentId = documentId(scope);
        if (!StringUtils.hasText(properties.baseUrl()) || !StringUtils.hasText(properties.apiKey())
                || !StringUtils.hasText(documentId) || properties.datasetIds() == null || properties.datasetIds().isEmpty()) {
            throw new IllegalStateException("RAGFlow remote retrieval is not fully configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("similarity_threshold", properties.similarityThreshold());
        body.put("vector_similarity_weight", properties.vectorSimilarityWeight());
        body.put("use_kg", false);
        body.put("question", question);
        body.put("top_k", properties.topK());
        body.put("dataset_ids", properties.datasetIds());
        body.put("document_ids", List.of(documentId));
        body.put("page", properties.page());
        body.put("page_size", properties.pageSize());
        if (StringUtils.hasText(properties.rerankId())) {
            body.put("rerank_id", properties.rerankId());
        }
        JsonNode response = restClient.post()
                .uri(URI.create(joinUrl(properties.baseUrl(), properties.retrievalPath())))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("RAGFlow retrieval returned a non-success response");
        }
        List<MetadataCandidate> result = new ArrayList<>();
        for (JsonNode chunk : response.path("data").path("chunks")) {
            String content = chunk.path("content").asText("");
            String id = first(FIELD_ID, content);
            if (!isAllowed(scope, id)) {
                continue;
            }
            String name = first(FIELD_NAME, content);
            if (!StringUtils.hasText(name)) {
                name = QueryMetadataCatalog.displayName(id);
            }
            String value = scope == VALUE ? first(FIELD_VALUE, content) : "";
            result.add(new MetadataCandidate(
                    scope, id, name, value, abbreviate(content), chunk.path("similarity").asDouble(0.5),
                    "ragflow:" + documentId(scope)));
        }
        return result;
    }

    private List<Entry> index() {
        List<Entry> cached = mockIndex;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (mockIndex == null) {
                mockIndex = loadWorkbookIndex();
            }
            return mockIndex;
        }
    }

    private List<Entry> loadWorkbookIndex() {
        List<Entry> result = new ArrayList<>();
        RagflowProperties.MockFiles files = properties.mockFiles();
        if (files != null) {
            readMetrics(files.metrics(), result);
            readDimensions(files.dimensions(), result);
            readValues(files.values(), result);
        }
        if (result.isEmpty()) {
            QueryMetadataCatalog.metricIds().forEach(id -> result.add(new Entry(
                    METRIC, id, QueryMetadataCatalog.displayName(id), "", "", "catalog")));
            AttributionCatalog.dimensions().forEach(dimension -> result.add(new Entry(
                    DIMENSION, dimension.id(), dimension.name(), "",
                    dimension.category() + " " + dimension.description() + " " + dimension.mappingHint(), "catalog")));
        }
        return List.copyOf(result);
    }

    private void readMetrics(String file, List<Entry> target) {
        for (List<String> row : dataRows(file)) {
            if (row.size() < 2 || !QueryMetadataCatalog.isMetric(cell(row, 0))) {
                continue;
            }
            target.add(new Entry(METRIC, cell(row, 0), cell(row, 1), "",
                    String.join(" ", cell(row, 2), cell(row, 3), cell(row, 4), cell(row, 5), cell(row, 6)), file));
        }
    }

    private void readDimensions(String file, List<Entry> target) {
        for (List<String> row : dataRows(file)) {
            if (row.size() < 3 || !QueryMetadataCatalog.isDimension(cell(row, 1))) {
                continue;
            }
            target.add(new Entry(DIMENSION, cell(row, 1), cell(row, 2), "",
                    String.join(" ", cell(row, 0), cell(row, 3), cell(row, 4), cell(row, 5), cell(row, 6)), file));
        }
    }

    private void readValues(String file, List<Entry> target) {
        for (List<String> row : dataRows(file)) {
            if (row.size() < 4 || !QueryMetadataCatalog.isDimension(cell(row, 1)) || !StringUtils.hasText(cell(row, 3))) {
                continue;
            }
            target.add(new Entry(VALUE, cell(row, 1), cell(row, 2), cell(row, 3), cell(row, 0), file));
        }
    }

    private List<List<String>> dataRows(String file) {
        if (!StringUtils.hasText(file) || !Files.isRegularFile(Path.of(file))) {
            return List.of();
        }
        try (ZipFile workbook = new ZipFile(file)) {
            List<String> shared = sharedStrings(workbook);
            var sheet = workbook.getEntry("xl/worksheets/sheet1.xml");
            if (sheet == null) {
                return List.of();
            }
            Document document;
            try (InputStream input = workbook.getInputStream(sheet)) {
                document = xml(input);
            }
            List<List<String>> rows = new ArrayList<>();
            NodeList rowNodes = document.getElementsByTagNameNS("*", "row");
            for (int rowIndex = 0; rowIndex < rowNodes.getLength(); rowIndex++) {
                Element row = (Element) rowNodes.item(rowIndex);
                NodeList cells = row.getElementsByTagNameNS("*", "c");
                List<String> values = new ArrayList<>();
                for (int cellIndex = 0; cellIndex < cells.getLength(); cellIndex++) {
                    Element cell = (Element) cells.item(cellIndex);
                    int column = columnIndex(cell.getAttribute("r"));
                    while (values.size() <= column) {
                        values.add("");
                    }
                    String value = childText(cell, "v");
                    if ("s".equals(cell.getAttribute("t")) && value.matches("\\d+")) {
                        value = Integer.parseInt(value) < shared.size() ? shared.get(Integer.parseInt(value)) : "";
                    } else if ("inlineStr".equals(cell.getAttribute("t"))) {
                        value = childText(cell, "t");
                    }
                    values.set(column, value.trim());
                }
                rows.add(values);
            }
            return rows.size() < 2 ? List.of() : rows.subList(1, rows.size());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> sharedStrings(ZipFile workbook) throws Exception {
        var entry = workbook.getEntry("xl/sharedStrings.xml");
        if (entry == null) {
            return List.of();
        }
        try (InputStream input = workbook.getInputStream(entry)) {
            Document document = xml(input);
            NodeList items = document.getElementsByTagNameNS("*", "si");
            List<String> result = new ArrayList<>();
            for (int index = 0; index < items.getLength(); index++) {
                result.add(items.item(index).getTextContent());
            }
            return result;
        }
    }

    private Document xml(InputStream input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(input);
    }

    private List<String> textItems(JsonNode node) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            add(result, item.isTextual() ? item.asText() : item.path("term").asText());
        }
        return result;
    }

    private List<String> filterTerms(JsonNode node) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String context = item.path("dimensionTerm").asText(item.path("raw").asText(""));
            List<String> values = textItems(item.path("values"));
            if (values.isEmpty()) {
                add(result, context);
            } else {
                for (String value : values) {
                    add(result, context + " " + value);
                }
            }
        }
        return result;
    }

    private void add(List<String> target, String term) {
        if (StringUtils.hasText(term) && !target.contains(term.trim())) {
            target.add(term.trim());
        }
    }

    private String documentId(Scope scope) {
        if (properties.documentIds() == null) {
            return "";
        }
        return switch (scope) {
            case METRIC -> properties.documentIds().metrics();
            case DIMENSION -> properties.documentIds().dimensions();
            case VALUE -> properties.documentIds().values();
        };
    }

    private boolean isAllowed(Scope scope, String fieldId) {
        return scope == METRIC ? QueryMetadataCatalog.isMetric(fieldId) : QueryMetadataCatalog.isDimension(fieldId);
    }

    private static String joinUrl(String baseUrl, String path) {
        return baseUrl.replaceAll("/+$", "") + "/" + path.replaceFirst("^/+", "");
    }

    private static String first(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content == null ? "" : content);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(280, normalized.length()));
    }

    private static String cell(List<String> row, int index) {
        return index < row.size() && row.get(index) != null ? row.get(index).trim() : "";
    }

    private static int columnIndex(String cellRef) {
        int result = 0;
        for (int index = 0; index < cellRef.length(); index++) {
            char value = cellRef.charAt(index);
            if (value < 'A' || value > 'Z') {
                break;
            }
            result = result * 26 + value - 'A' + 1;
        }
        return Math.max(0, result - 1);
    }

    private static String childText(Element element, String localName) {
        NodeList children = element.getElementsByTagNameNS("*", localName);
        return children.getLength() == 0 ? "" : children.item(0).getTextContent();
    }

    private static double score(String query, String content) {
        String normalizedQuery = normalize(query);
        String normalizedContent = normalize(content);
        if (normalizedQuery.isBlank() || normalizedContent.isBlank()) {
            return 0;
        }
        if (normalizedContent.equals(normalizedQuery)) {
            return 1.0;
        }
        if (normalizedContent.contains(normalizedQuery)) {
            return 0.8;
        }
        double best = 0;
        for (String token : normalizedQuery.split("[\\s,，;；。]+")) {
            if (token.length() >= 2 && normalizedContent.contains(token)) {
                best = Math.max(best, Math.min(0.9, 0.4 + token.length() * 0.08));
            }
        }
        return best;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[()（）:：|]", " ").trim();
    }

    private record RetrievalPlan(List<String> metrics, List<String> dimensions, List<String> filters) {
    }

    private record Entry(Scope scope, String fieldId, String fieldName, String value, String description, String source) {
        String searchText() {
            return String.join(" ", fieldId, fieldName, value, description);
        }

        MetadataCandidate toCandidate(double score) {
            return new MetadataCandidate(scope, fieldId, fieldName, value, description, score, source);
        }

        double score(String query) {
            String normalizedQuery = normalize(query);
            if (normalizedQuery.equals(normalize(fieldName))) {
                return 1.0;
            }
            if (scope == VALUE && StringUtils.hasText(value) && normalizedQuery.equals(normalize(value))) {
                return 1.0;
            }
            if (scope == VALUE && StringUtils.hasText(value)
                    && !normalizedQuery.matches(".*\\d{4,}.*")
                    && normalizedQuery.contains(normalize(value)) && normalize(value).length() >= 2) {
                return 0.98;
            }
            String normalizedName = normalize(fieldName);
            if (normalizedName.contains(normalizedQuery) || normalizedQuery.contains(normalizedName)) {
                return 0.9;
            }
            return RagflowMetadataTool.score(query, searchText());
        }
    }
}
