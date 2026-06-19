package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import com.etiya.replayfix.model.SuspectSignalCategory;
import com.etiya.replayfix.model.SuspectSignalExtractionResponse;
import com.etiya.replayfix.model.SuspectSignalStrength;
import com.etiya.replayfix.model.SuspectSourceSignal;
import com.etiya.replayfix.repository.EvidenceRepository;
import com.etiya.replayfix.repository.ReplayCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SuspectSignalExtractionService {

    private static final Pattern ENDPOINT_PATTERN =
            Pattern.compile("/[A-Za-z0-9_{}-]+(?:/[A-Za-z0-9_{}-]+)+");
    private static final Pattern CONSTANT_PATTERN =
            Pattern.compile("\\b[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+\\b");
    private static final Pattern SNAKE_PATTERN =
            Pattern.compile("\\b[a-z][a-z0-9]+(?:_[a-z0-9]+)+\\b");
    private static final Pattern KEBAB_PATTERN =
            Pattern.compile("\\b[a-z][a-z0-9]+(?:-[a-z0-9]+)+\\b");
    private static final Pattern CAMEL_PATTERN =
            Pattern.compile("\\b[a-z]+(?:[A-Z][a-z0-9]+)+\\b");
    private static final Pattern PASCAL_PATTERN =
            Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+\\b");
    private static final Pattern BUSINESS_TERM_PATTERN =
            Pattern.compile("\\b[a-z][a-z0-9]+(?:\\s+[a-z][a-z0-9]+){1,3}\\b");

    private static final Set<String> BUSINESS_KEYWORDS = Set.of(
            "account",
            "billing",
            "business",
            "flow",
            "gl",
            "i2i",
            "mismatch",
            "province",
            "region",
            "tax",
            "timezone",
            "user"
    );

    private static final Set<String> KNOWN_DOMAIN_TERMS = Set.of(
            "billingaccount",
            "businessflow",
            "gl",
            "i2i",
            "initialize",
            "preferredprovince",
            "province",
            "provincemismatch",
            "region",
            "taxinfo",
            "timezone",
            "update"
    );

    private static final Set<String> STRONG_TECHNICAL_TOKENS = Set.of(
            "businessflow",
            "billingaccount",
            "gl",
            "i2i",
            "initialize",
            "preferredprovince",
            "region",
            "regionupdate",
            "taxinfo",
            "timezone",
            "user"
    );

    private static final Set<String> FILLER_WORDS = Set.of(
            "account ya da",
            "edilirken",
            "description",
            "icin",
            "ile",
            "mevcut",
            "olan",
            "selamlar",
            "sirasinda",
            "var",
            "ve",
            "ya",
            "ya da"
    );

    private final ReplayCaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    public SuspectSignalExtractionService(
            ReplayCaseRepository caseRepository,
            EvidenceRepository evidenceRepository,
            ObjectMapper objectMapper
    ) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SuspectSignalExtractionResponse extract(UUID caseId) {
        return extract(caseId, false);
    }

    @Transactional(readOnly = true)
    public SuspectSignalExtractionResponse extract(
            UUID caseId,
            boolean includeWeak
    ) {
        ReplayCaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Case not found: " + caseId
                ));

        List<EvidenceEntity> evidenceList =
                evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId);

        ExtractionContext context = new ExtractionContext(includeWeak);
        List<String> warnings = new ArrayList<>();

        Optional<EvidenceEntity> rovoEvidence =
                latest(evidenceList, EvidenceType.ROVO_RCA);
        if (rovoEvidence.isPresent()) {
            extractFromRovo(rovoEvidence.get(), context);
        } else if (hasAnyRelevantEvidence(evidenceList)) {
            warnings.add(
                    "ROVO_RCA evidence not found; signals were extracted from other case evidence."
            );
        }

        latest(evidenceList, EvidenceType.DETERMINISTIC_ROOT_CAUSE)
                .ifPresent(evidence -> extractFromEvidence(evidence, context));
        latest(evidenceList, EvidenceType.AI_INPUT_BUNDLE)
                .ifPresent(evidence -> extractFromEvidence(evidence, context));
        latest(evidenceList, EvidenceType.JIRA_ISSUE)
                .ifPresent(evidence -> extractFromEvidence(evidence, context));

        RepositoryContext repositoryContext =
                repositoryContext(caseEntity, evidenceList);

        return new SuspectSignalExtractionResponse(
                caseId,
                caseEntity.getJiraKey(),
                repositoryContext.repository(),
                repositoryContext.branch(),
                context.signals().values()
                        .stream()
                        .map(SignalAccumulator::toSignal)
                        .toList(),
                context.filteredCount(),
                warnings
        );
    }

    private void extractFromRovo(
            EvidenceEntity evidence,
            ExtractionContext context
    ) {
        Optional<JsonNode> json = readJson(evidence);
        if (json.isEmpty()) {
            extractFromEvidence(evidence, context);
            return;
        }

        JsonNode envelope = json.get();

        JsonNode normalized = envelope.get("normalizedRovoJson");
        if (normalized != null) {
            extractFromText(
                    collectText(normalized),
                    evidence.getEvidenceType(),
                    context,
                    "Signal from Rovo RCA normalized JSON"
            );
        } else {
            extractFromText(
                    collectText(envelope),
                    evidence.getEvidenceType(),
                    context,
                    "Signal from Rovo RCA JSON"
            );
        }

        JsonNode rawHumanReport = envelope.get("rawHumanReport");
        if (rawHumanReport != null && rawHumanReport.isTextual()) {
            extractFromText(
                    rawHumanReport.asText(),
                    evidence.getEvidenceType(),
                    context,
                    "Signal from Rovo RCA human report"
            );
        }
    }

    private void extractFromEvidence(
            EvidenceEntity evidence,
            ExtractionContext context
    ) {
        String text = readJson(evidence)
                .map(this::collectText)
                .orElseGet(() -> firstNonBlank(
                        evidence.getContentText(),
                        evidence.getBody()
                ));

        extractFromText(
                text,
                evidence.getEvidenceType(),
                context,
                "Signal from " + evidence.getEvidenceType()
        );
    }

    private void extractFromText(
            String text,
            EvidenceType evidenceType,
            ExtractionContext context,
            String reason
    ) {
        if (text == null || text.isBlank()) {
            return;
        }

        extractEndpoints(text, evidenceType, context, reason);
        extractPattern(
                text,
                CONSTANT_PATTERN,
                SuspectSignalCategory.CONSTANT,
                evidenceType,
                context,
                reason
        );
        extractPattern(
                text,
                SNAKE_PATTERN,
                SuspectSignalCategory.BUSINESS_TERM,
                evidenceType,
                context,
                reason
        );
        extractPattern(
                text,
                KEBAB_PATTERN,
                SuspectSignalCategory.BUSINESS_TERM,
                evidenceType,
                context,
                reason
        );
        extractPattern(
                text,
                CAMEL_PATTERN,
                SuspectSignalCategory.BUSINESS_TERM,
                evidenceType,
                context,
                reason
        );
        extractPattern(
                text,
                PASCAL_PATTERN,
                SuspectSignalCategory.BUSINESS_TERM,
                evidenceType,
                context,
                reason
        );
        extractBusinessTerms(text, evidenceType, context, reason);
        extractKeywordTerms(text, evidenceType, context, reason);
        extractKnownTechnicalTokens(text, evidenceType, context, reason);
    }

    private void extractEndpoints(
            String text,
            EvidenceType evidenceType,
            ExtractionContext context,
            String reason
    ) {
        Matcher matcher = ENDPOINT_PATTERN.matcher(text);
        while (matcher.find()) {
            String endpoint = cleanToken(matcher.group());
            addSignal(
                    endpoint,
                    SuspectSignalCategory.ENDPOINT,
                    SuspectSignalStrength.STRONG,
                    evidenceType,
                    context,
                    reason
            );

            List<String> segments = endpointSegments(endpoint);
            for (String segment : segments) {
                addSignal(
                        segment,
                        SuspectSignalCategory.VARIANT,
                        endpointSegmentStrength(segment),
                        evidenceType,
                        context,
                        "Endpoint segment from " + endpoint
                );
                addVariants(segment, evidenceType, context, endpoint);
            }

            if (segments.size() >= 2) {
                List<String> tail = segments.subList(
                        segments.size() - 2,
                        segments.size()
                );
                addSignal(
                        toCamelCase(tail),
                        SuspectSignalCategory.VARIANT,
                        SuspectSignalStrength.STRONG,
                        evidenceType,
                        context,
                        "Endpoint operation variant from " + endpoint
                );
                addSignal(
                        toPascalCase(tail),
                        SuspectSignalCategory.VARIANT,
                        SuspectSignalStrength.STRONG,
                        evidenceType,
                        context,
                        "Endpoint operation variant from " + endpoint
                );
            }
        }

        Matcher singleEndpointMatcher =
                Pattern.compile("(?<!:)\\/[A-Za-z][A-Za-z0-9_{}-]+\\b")
                        .matcher(text);
        while (singleEndpointMatcher.find()) {
            String endpoint = cleanToken(singleEndpointMatcher.group());
            if (endpoint.contains("//")) {
                continue;
            }
            addSignal(
                    endpoint,
                    SuspectSignalCategory.ENDPOINT,
                    SuspectSignalStrength.STRONG,
                    evidenceType,
                    context,
                    reason
            );
        }
    }

    private void extractPattern(
            String text,
            Pattern pattern,
            SuspectSignalCategory category,
            EvidenceType evidenceType,
            ExtractionContext context,
            String reason
    ) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = cleanToken(matcher.group());
            if (!isUsefulSignal(value)) {
                context.incrementFilteredCount();
                continue;
            }
            SuspectSignalStrength strength = strength(value, category);
            addSignal(value, category, strength, evidenceType, context, reason);
            if (shouldGenerateVariants(value, strength)) {
                addVariants(value, evidenceType, context, value);
            }
        }
    }

    private void extractBusinessTerms(
            String text,
            EvidenceType evidenceType,
            ExtractionContext context,
            String reason
    ) {
        Matcher matcher = BUSINESS_TERM_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String value = matcher.group().trim();
            if (!containsBusinessKeyword(value) || isFillerPhrase(value)) {
                context.incrementFilteredCount();
                continue;
            }
            SuspectSignalStrength strength = strength(
                    value,
                    SuspectSignalCategory.BUSINESS_TERM
            );
            addSignal(
                    value,
                    SuspectSignalCategory.BUSINESS_TERM,
                    strength,
                    evidenceType,
                    context,
                    reason
            );
            if (shouldGenerateVariants(value, strength)) {
                addVariants(value, evidenceType, context, value);
            }
        }
    }

    private void extractKeywordTerms(
            String text,
            EvidenceType evidenceType,
            ExtractionContext context,
            String reason
    ) {
        List<String> words = normalizedWords(text);

        for (String word : words) {
            if (BUSINESS_KEYWORDS.contains(word)) {
                addSignal(
                        word,
                        SuspectSignalCategory.BUSINESS_TERM,
                        strength(word, SuspectSignalCategory.BUSINESS_TERM),
                        evidenceType,
                        context,
                        reason
                );
                addVariants(word, evidenceType, context, word);
            }
        }

        for (int size = 2; size <= 3; size++) {
            for (int index = 0; index + size <= words.size(); index++) {
                List<String> phraseWords = words.subList(index, index + size);
                String phrase = String.join(" ", phraseWords);
                if (!containsBusinessKeyword(phrase) || isFillerPhrase(phrase)) {
                    context.incrementFilteredCount();
                    continue;
                }
                SuspectSignalStrength strength = strength(
                        phrase,
                        SuspectSignalCategory.BUSINESS_TERM
                );
                addSignal(
                        phrase,
                        SuspectSignalCategory.BUSINESS_TERM,
                        strength,
                        evidenceType,
                        context,
                        reason
                );
                if (shouldGenerateVariants(phrase, strength)) {
                    addVariants(phrase, evidenceType, context, phrase);
                }
            }
        }
    }

    private void extractKnownTechnicalTokens(
            String text,
            EvidenceType evidenceType,
            ExtractionContext context,
            String reason
    ) {
        Matcher matcher = Pattern.compile(
                        "\\b(?:GL|i2i)\\b",
                        Pattern.CASE_INSENSITIVE
                )
                .matcher(text);
        while (matcher.find()) {
            String value = matcher.group();
            if ("gl".equalsIgnoreCase(value)) {
                value = "GL";
            } else if ("i2i".equalsIgnoreCase(value)) {
                value = "i2i";
            }

            addSignal(
                    value,
                    SuspectSignalCategory.BUSINESS_TERM,
                    strength(value, SuspectSignalCategory.BUSINESS_TERM),
                    evidenceType,
                    context,
                    reason
            );
        }
    }

    private void addVariants(
            String value,
            EvidenceType evidenceType,
            ExtractionContext context,
            String base
    ) {
        List<String> words = words(value);
        if (words.isEmpty()) {
            return;
        }

        List<String> variants = List.of(
                toCamelCase(words),
                toPascalCase(words),
                String.join("_", words),
                String.join("-", words)
        );

        for (String variant : variants) {
            if (!variant.equals(value) && isUsefulSignal(variant)) {
                addSignal(
                        variant,
                        SuspectSignalCategory.VARIANT,
                        variantStrength(variant),
                        evidenceType,
                        context,
                        "Search variant of " + base
                );
            }
        }
    }

    private void addSignal(
            String value,
            SuspectSignalCategory category,
            SuspectSignalStrength strength,
            EvidenceType evidenceType,
            ExtractionContext context,
            String reason
    ) {
        if (value == null || value.isBlank() || !isUsefulSignal(value)) {
            context.incrementFilteredCount();
            return;
        }

        String cleaned = cleanToken(value);
        if (cleaned.isBlank()) {
            context.incrementFilteredCount();
            return;
        }

        if (isFillerPhrase(cleaned)) {
            context.incrementFilteredCount();
            return;
        }

        if (strength == SuspectSignalStrength.WEAK && !context.includeWeak()) {
            context.incrementFilteredCount();
            return;
        }

        String key = signalKey(cleaned, category);
        SignalAccumulator accumulator = context.signals().computeIfAbsent(
                key,
                ignored -> new SignalAccumulator(
                        cleaned,
                        category,
                        strength,
                        reason
                )
        );
        accumulator.promoteStrength(strength);
        accumulator.addEvidenceType(evidenceType);
    }

    private String signalKey(String value, SuspectSignalCategory category) {
        if (category == SuspectSignalCategory.BUSINESS_TERM
                && value.contains(" ")) {
            return category + ":" + value.toLowerCase(Locale.ROOT);
        }
        return category + ":" + value;
    }

    private SuspectSignalStrength strength(
            String value,
            SuspectSignalCategory category
    ) {
        String normalized = normalizeSignal(value);

        if (category == SuspectSignalCategory.ENDPOINT
                || category == SuspectSignalCategory.CONSTANT) {
            return SuspectSignalStrength.STRONG;
        }

        if (category == SuspectSignalCategory.BUSINESS_TERM
                && value.contains(" ")
                && STRONG_TECHNICAL_TOKENS.contains(normalized)) {
            return SuspectSignalStrength.MEDIUM;
        }

        if (STRONG_TECHNICAL_TOKENS.contains(normalized)) {
            return SuspectSignalStrength.STRONG;
        }

        if (KNOWN_DOMAIN_TERMS.contains(normalized)
                || containsStrongTechnicalToken(value)) {
            return SuspectSignalStrength.MEDIUM;
        }

        if (!value.contains(" ") && containsBusinessKeyword(value)) {
            return SuspectSignalStrength.MEDIUM;
        }

        if (value.contains(" ") && containsBusinessKeyword(value)) {
            return SuspectSignalStrength.WEAK;
        }

        return SuspectSignalStrength.WEAK;
    }

    private SuspectSignalStrength endpointSegmentStrength(String value) {
        String normalized = normalizeSignal(value);
        if (STRONG_TECHNICAL_TOKENS.contains(normalized)
                || "businessflow".equals(normalized)
                || "initialize".equals(normalized)) {
            return SuspectSignalStrength.STRONG;
        }
        return SuspectSignalStrength.MEDIUM;
    }

    private SuspectSignalStrength variantStrength(String value) {
        String normalized = normalizeSignal(value);
        if (STRONG_TECHNICAL_TOKENS.contains(normalized)
                || Character.isUpperCase(value.charAt(0))
                || value.contains("_")
                || value.contains("-")) {
            return SuspectSignalStrength.STRONG;
        }
        return SuspectSignalStrength.MEDIUM;
    }

    private boolean shouldGenerateVariants(
            String value,
            SuspectSignalStrength strength
    ) {
        return strength != SuspectSignalStrength.WEAK
                && !isFillerPhrase(value)
                && (isKnownDomainTerm(value)
                || containsStrongTechnicalToken(value)
                || value.contains("_")
                || value.contains("-")
                || hasCaseBoundary(value)
                || value.contains(" "));
    }

    private boolean isKnownDomainTerm(String value) {
        return KNOWN_DOMAIN_TERMS.contains(normalizeSignal(value));
    }

    private boolean containsStrongTechnicalToken(String value) {
        for (String word : words(value)) {
            if (STRONG_TECHNICAL_TOKENS.contains(word)
                    || KNOWN_DOMAIN_TERMS.contains(word)) {
                return true;
            }
        }
        return STRONG_TECHNICAL_TOKENS.contains(normalizeSignal(value));
    }

    private boolean hasCaseBoundary(String value) {
        return Pattern.compile("[a-z0-9][A-Z]").matcher(value).find();
    }

    private boolean isFillerPhrase(String value) {
        String lower = " " + foldTurkish(value)
                .toLowerCase(Locale.ROOT)
                .trim() + " ";

        for (String filler : FILLER_WORDS) {
            if (filler.contains(" ")) {
                if (lower.contains(" " + filler + " ")) {
                    return true;
                }
            } else if (lower.contains(" " + filler + " ")) {
                return true;
            }
        }

        return false;
    }

    private String foldTurkish(String value) {
        return value == null
                ? ""
                : value
                .replace('\u00e7', 'c')
                .replace('\u00c7', 'C')
                .replace('\u011f', 'g')
                .replace('\u011e', 'G')
                .replace('\u0131', 'i')
                .replace('\u0130', 'I')
                .replace('\u00f6', 'o')
                .replace('\u00d6', 'O')
                .replace('\u015f', 's')
                .replace('\u015e', 'S')
                .replace('\u00fc', 'u')
                .replace('\u00dc', 'U');
    }

    private String normalizeSignal(String value) {
        return cleanToken(value)
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
    }

    private Optional<EvidenceEntity> latest(
            List<EvidenceEntity> evidenceList,
            EvidenceType type
    ) {
        return evidenceList.stream()
                .filter(item -> item.getEvidenceType() == type)
                .max(Comparator.comparing(
                        EvidenceEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ));
    }

    private RepositoryContext repositoryContext(
            ReplayCaseEntity caseEntity,
            List<EvidenceEntity> evidenceList
    ) {
        Optional<JsonNode> repositoryJson =
                latest(evidenceList, EvidenceType.REPOSITORY_RESOLUTION)
                        .flatMap(this::readJson);

        String repository = repositoryJson
                .flatMap(json -> findText(
                        json,
                        "repository",
                        "repositoryFullName",
                        "repositorySlug",
                        "primaryRepositorySlug"
                ))
                .orElse(null);

        if (repository != null
                && !repository.contains("/")
                && repositoryJson.isPresent()) {
            String projectKey = findText(repositoryJson.get(), "projectKey")
                    .orElse(null);
            if (projectKey != null && !projectKey.isBlank()) {
                repository = projectKey + "/" + repository;
            }
        }

        String branch = repositoryJson
                .flatMap(json -> findText(
                        json,
                        "branch",
                        "targetBranch",
                        "defaultBranch"
                ))
                .orElse(firstNonBlank(caseEntity.getSourceBranch(), null));

        return new RepositoryContext(repository, branch);
    }

    private Optional<JsonNode> readJson(EvidenceEntity evidence) {
        String content = firstNonBlank(
                evidence.getContentText(),
                evidence.getBody()
        );
        if (content == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(content));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> findText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            Optional<String> value = findTextField(node, fieldName);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findTextField(JsonNode node, String fieldName) {
        if (node == null) {
            return Optional.empty();
        }

        if (node.isObject()) {
            JsonNode value = node.get(fieldName);
            if (value != null) {
                if (value.isTextual()) {
                    return Optional.of(value.asText());
                }
                if (value.isNumber() || value.isBoolean()) {
                    return Optional.of(value.asText());
                }
            }

            var fields = node.fields();
            while (fields.hasNext()) {
                Optional<String> childValue =
                        findTextField(fields.next().getValue(), fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<String> childValue = findTextField(child, fieldName);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        return Optional.empty();
    }

    private String collectText(JsonNode node) {
        List<String> values = new ArrayList<>();
        collectText(node, values);
        return String.join(" ", values);
    }

    private void collectText(JsonNode node, List<String> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            values.add(node.asText());
            return;
        }
        if (node.isNumber() || node.isBoolean()) {
            values.add(node.asText());
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                values.add(entry.getKey());
                collectText(entry.getValue(), values);
            });
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectText(child, values);
            }
        }
    }

    private boolean hasAnyRelevantEvidence(List<EvidenceEntity> evidenceList) {
        return evidenceList.stream()
                .anyMatch(item -> item.getEvidenceType()
                        == EvidenceType.DETERMINISTIC_ROOT_CAUSE
                        || item.getEvidenceType() == EvidenceType.AI_INPUT_BUNDLE
                        || item.getEvidenceType() == EvidenceType.JIRA_ISSUE);
    }

    private boolean containsBusinessKeyword(String value) {
        for (String word : value.split("\\s+")) {
            if (BUSINESS_KEYWORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private List<String> normalizedWords(String text) {
        List<String> words = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\b[a-z][a-z0-9]+\\b")
                .matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private boolean isUsefulSignal(String value) {
        if (value == null) {
            return false;
        }
        String cleaned = cleanToken(value);
        if ("GL".equals(cleaned) || "i2i".equalsIgnoreCase(cleaned)) {
            return true;
        }
        if (cleaned.length() < 3) {
            return false;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        return !Set.of(
                "and",
                "but",
                "for",
                "from",
                "not",
                "null",
                "the",
                "with"
        ).contains(lower);
    }

    private String cleanToken(String value) {
        return value == null
                ? ""
                : value.trim()
                .replaceAll("^[\"'`({\\[]+", "")
                .replaceAll("[\"'`.,;:)}\\]]+$", "");
    }

    private List<String> endpointSegments(String endpoint) {
        List<String> segments = new ArrayList<>();
        for (String segment : endpoint.split("/")) {
            String cleaned = cleanToken(segment);
            if (isUsefulSignal(cleaned)) {
                segments.add(cleaned);
            }
        }
        return segments;
    }

    private List<String> words(String value) {
        String cleaned = cleanToken(value);
        if (cleaned.equalsIgnoreCase("timezone")) {
            return List.of("time", "zone");
        }

        String separated = cleaned
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ');

        List<String> words = new ArrayList<>();
        for (String part : separated.split("\\s+")) {
            String word = part.trim();
            if (!word.isBlank()) {
                words.add(word.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    private String toCamelCase(List<String> words) {
        if (words.isEmpty()) {
            return "";
        }
        StringBuilder value = new StringBuilder(words.get(0));
        for (int index = 1; index < words.size(); index++) {
            value.append(capitalize(words.get(index)));
        }
        return value.toString();
    }

    private String toPascalCase(List<String> words) {
        return words.stream()
                .map(this::capitalize)
                .reduce("", String::concat);
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT)
                + value.substring(1);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record RepositoryContext(
            String repository,
            String branch
    ) {
    }

    private static final class ExtractionContext {
        private final boolean includeWeak;
        private final Map<String, SignalAccumulator> signals =
                new LinkedHashMap<>();
        private int filteredCount;

        private ExtractionContext(boolean includeWeak) {
            this.includeWeak = includeWeak;
        }

        private boolean includeWeak() {
            return includeWeak;
        }

        private Map<String, SignalAccumulator> signals() {
            return signals;
        }

        private int filteredCount() {
            return filteredCount;
        }

        private void incrementFilteredCount() {
            filteredCount++;
        }
    }

    private static final class SignalAccumulator {
        private final String value;
        private final SuspectSignalCategory category;
        private SuspectSignalStrength strength;
        private final Set<String> sourceEvidenceTypes = new LinkedHashSet<>();
        private final String reason;

        private SignalAccumulator(
                String value,
                SuspectSignalCategory category,
                SuspectSignalStrength strength,
                String reason
        ) {
            this.value = value;
            this.category = category;
            this.strength = strength;
            this.reason = reason;
        }

        private void promoteStrength(SuspectSignalStrength candidate) {
            if (candidate.ordinal() < strength.ordinal()) {
                strength = candidate;
            }
        }

        private void addEvidenceType(EvidenceType evidenceType) {
            sourceEvidenceTypes.add(evidenceType.name());
        }

        private SuspectSourceSignal toSignal() {
            return new SuspectSourceSignal(
                    value,
                    category,
                    strength,
                    List.copyOf(sourceEvidenceTypes),
                    reason
            );
        }
    }
}
