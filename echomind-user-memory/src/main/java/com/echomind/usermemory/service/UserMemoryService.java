package com.echomind.usermemory.service;

import com.echomind.common.model.UserMemoryEvent;
import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.usermemory.UserMemoryEntry;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import com.echomind.usermemory.config.UserMemoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserMemoryService {

    private final UserMemoryStore vectorStore;
    private final UserProfileExtractor extractor;
    private final EmbeddingClient embeddingClient;
    private final UserMemoryProperties properties;

    public void process(UserMemoryEvent event) {
        if (!properties.isEnabled() || event == null || event.sessionId() == null || event.sessionId().isBlank()) {
            return;
        }
        List<UserMemoryHit> existing = vectorStore.listBySession(
            event.sessionId(),
            properties.getExistingProfileLimit()
        );
        List<ExtractedUserMemory> extracted = extractor.extract(event.sessionId(), existing, event.messages());
        if (extracted.isEmpty()) {
            return;
        }
        int saved = 0;
        int skipped = 0;
        Set<String> seen = new HashSet<>();
        for (UserMemoryHit hit : existing) {
            seen.add(signature(hit.category(), hit.content()));
        }
        for (ExtractedUserMemory item : extracted) {
            String signature = signature(item.category(), item.content());
            List<UserMemoryHit> duplicates = existing.stream()
                .filter(hit -> signature.equals(signature(hit.category(), hit.content())))
                .toList();
            boolean hasSameOrBetter = duplicates.stream()
                .anyMatch(hit -> hit.confidence() >= item.confidence());
            if (hasSameOrBetter) {
                skipped++;
                continue;
            }
            if (!duplicates.isEmpty()) {
                seen.remove(signature);
            }
            if (!seen.add(signature)) {
                skipped++;
                continue;
            }
            Optional<double[]> embedding = embeddingClient.embed(item.content());
            if (embedding.isEmpty()) {
                continue;
            }
            duplicates.forEach(hit -> vectorStore.deleteEntry(event.sessionId(), hit.entryId()));
            vectorStore.save(new UserMemoryEntry(
                event.sessionId(),
                UUID.randomUUID().toString(),
                item.category(),
                item.content(),
                item.evidence(),
                item.confidence(),
                embedding.get()
            ));
            saved++;
        }
        log.info("Saved {} user memory entries sessionId={} skippedDuplicates={}", saved, event.sessionId(), skipped);
    }

    private String signature(com.echomind.memory.usermemory.UserMemoryCategory category, String content) {
        String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", "")
            .replaceAll("[，。！？、；：“”‘’（）【】《》,.!?;:'\"()\\[\\]<>-]", "");
        return category.storageValue() + ":" + normalized;
    }
}
