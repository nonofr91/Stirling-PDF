package stirling.software.proprietary.matting.catalog;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Loads the curated subject-matting model catalog from a bundled JSON resource. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MattingCatalogService {

    private static final String CATALOG_RESOURCE = "matting/model-catalog.json";

    private final ObjectMapper objectMapper;

    private volatile List<MattingCatalogEntry> entries = List.of();
    private volatile Map<String, MattingCatalogEntry> byId = Map.of();

    @PostConstruct
    void load() {
        try (InputStream is = new ClassPathResource(CATALOG_RESOURCE).getInputStream()) {
            List<MattingCatalogEntry> loaded = objectMapper.readValue(is, new TypeReference<>() {});
            Map<String, MattingCatalogEntry> map = new LinkedHashMap<>();
            for (MattingCatalogEntry entry : loaded) {
                if (entry.getId() != null && !entry.getId().isBlank()) {
                    map.put(entry.getId(), entry);
                }
            }
            this.entries = List.copyOf(map.values());
            this.byId = Map.copyOf(map);
            log.info("Loaded {} subject-matting model catalog entries", entries.size());
        } catch (Exception e) {
            log.error("Failed to load matting model catalog from {}", CATALOG_RESOURCE, e);
        }
    }

    public List<MattingCatalogEntry> getAll() {
        return entries;
    }

    public Optional<MattingCatalogEntry> getById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<MattingCatalogEntry> getDefault() {
        return entries.stream().filter(MattingCatalogEntry::isDefaultModel).findFirst();
    }
}
