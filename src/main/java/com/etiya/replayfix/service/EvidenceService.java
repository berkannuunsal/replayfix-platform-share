package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.EvidenceEntity;
import com.etiya.replayfix.domain.EvidenceType;
import com.etiya.replayfix.repository.EvidenceRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class EvidenceService {
    private final EvidenceRepository repository;
    private final EvidenceSanitizer sanitizer;

    public EvidenceService(
            EvidenceRepository repository,
            EvidenceSanitizer sanitizer
    ) {
        this.repository = repository;
        this.sanitizer = sanitizer;
    }

    public EvidenceEntity save(
            UUID caseId,
            EvidenceType type,
            String source,
            String content,
            boolean sanitize
    ) {
        String finalContent = sanitize
                ? sanitizer.sanitize(content)
                : content;

        EvidenceEntity entity = new EvidenceEntity();
        entity.setCaseId(caseId);
        entity.setEvidenceType(type);
        entity.setSource(source);
        entity.setContentText(finalContent);
        entity.setContentHash(hash(finalContent));
        entity.setSanitized(sanitize);
        return repository.save(entity);
    }

    public List<EvidenceEntity> list(UUID caseId) {
        return repository.findByCaseIdOrderByCreatedAtAsc(caseId);
    }

    public EvidenceEntity find(UUID caseId, UUID evidenceId) {
        return repository.findById(evidenceId)
                .filter(e -> e.getCaseId().equals(caseId))
                .orElseThrow(() -> new IllegalArgumentException("Evidence not found: " + evidenceId));
    }

    public String sanitize(String content) {
        return sanitizer.sanitize(content);
    }

    public String hashContent(String content) {
        return hash(content);
    }

    private String hash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(
                            (content == null ? "" : content)
                                    .getBytes(StandardCharsets.UTF_8)
                    );
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
