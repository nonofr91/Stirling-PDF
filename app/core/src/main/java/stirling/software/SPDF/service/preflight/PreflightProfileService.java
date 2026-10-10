package stirling.software.SPDF.service.preflight;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.security.PrintPreflightProfile;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;
import stirling.software.common.configuration.InstallationPathConfig;
import stirling.software.common.util.ExceptionUtils;

/**
 * Named preflight profiles: a snapshot of every threshold plus fixups and disabled checks. Built-in
 * profiles ship in {@code resources/preflight-profiles.json}; user profiles persist in {@code
 * configs/preflight-profiles.json} so they survive restarts and redeployments. A request carrying
 * {@code profileName} runs with the profile as its complete parameter set.
 */
@Slf4j
@Service
public class PreflightProfileService {

    private static final String BUILTIN_RESOURCE = "preflight-profiles.json";
    private static final String CUSTOM_FILE = "preflight-profiles.json";
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_CUSTOM_PROFILES = 200;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, PrintPreflightProfile> builtins;
    private final Path customFile;

    public PreflightProfileService() {
        this(Path.of(InstallationPathConfig.getConfigPath(), CUSTOM_FILE));
    }

    PreflightProfileService(Path customFile) {
        this.customFile = customFile;
        this.builtins = loadBuiltins();
    }

    /** All profiles: built-ins first, then customs in insertion order. */
    public List<PrintPreflightProfile> list() {
        Map<String, PrintPreflightProfile> all = new LinkedHashMap<>(builtins);
        all.putAll(loadCustoms());
        return List.copyOf(all.values());
    }

    public Optional<PrintPreflightProfile> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        PrintPreflightProfile custom = loadCustoms().get(name);
        return Optional.ofNullable(custom != null ? custom : builtins.get(name));
    }

    /**
     * Applies the profile named by {@code request.profileName} onto the request: the profile is
     * authoritative — every field it declares replaces the request value; fields the profile leaves
     * null keep the request's own value. No-op when no profile is named.
     */
    public void applyProfile(PrintPreflightRequest request) {
        String name = request.getProfileName();
        if (name == null || name.isBlank()) {
            return;
        }
        PrintPreflightProfile profile =
                find(name)
                        .orElseThrow(
                                () ->
                                        ExceptionUtils.createIllegalArgumentException(
                                                "error.invalidArgument",
                                                "Unknown preflight profile: {0}",
                                                name));
        if (profile.getRequiredBleedMm() != null)
            request.setRequiredBleedMm(profile.getRequiredBleedMm());
        if (profile.getMinImageDpi() != null) request.setMinImageDpi(profile.getMinImageDpi());
        if (profile.getHairlineThresholdPt() != null)
            request.setHairlineThresholdPt(profile.getHairlineThresholdPt());
        if (profile.getCheckBleedCoverage() != null)
            request.setCheckBleedCoverage(profile.getCheckBleedCoverage());
        if (profile.getMinFontSizePt() != null)
            request.setMinFontSizePt(profile.getMinFontSizePt());
        if (profile.getSafetyMarginMm() != null)
            request.setSafetyMarginMm(profile.getSafetyMarginMm());
        if (profile.getMaxInkCoveragePercent() != null)
            request.setMaxInkCoveragePercent(profile.getMaxInkCoveragePercent());
        if (profile.getRenderedInkCoverage() != null)
            request.setRenderedInkCoverage(profile.getRenderedInkCoverage());
        if (profile.getMinImage1BitDpi() != null)
            request.setMinImage1BitDpi(profile.getMinImage1BitDpi());
        if (profile.getMaxImageDpi() != null) request.setMaxImageDpi(profile.getMaxImageDpi());
        if (profile.getMaxSpotCount() != null) request.setMaxSpotCount(profile.getMaxSpotCount());
        if (profile.getIncludeSummaryPage() != null)
            request.setIncludeSummaryPage(profile.getIncludeSummaryPage());
        if (profile.getDisabledChecks() != null)
            request.setDisabledChecks(profile.getDisabledChecks());
        if (profile.getFixups() != null) request.setFixups(profile.getFixups());
        if (profile.getFixupParams() != null) {
            try {
                request.setFixupParams(mapper.writeValueAsString(profile.getFixupParams()));
            } catch (JsonProcessingException e) {
                throw ExceptionUtils.createIllegalArgumentException(
                        "error.invalidArgument", "Profile fixupParams could not be serialized");
            }
        }
    }

    /** Creates or replaces a custom profile. Built-in names are reserved. */
    public synchronized PrintPreflightProfile save(PrintPreflightProfile profile) {
        validate(profile);
        Map<String, PrintPreflightProfile> customs = new LinkedHashMap<>(loadCustoms());
        if (!customs.containsKey(profile.getName()) && customs.size() >= MAX_CUSTOM_PROFILES) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "Too many custom profiles (max {0})",
                    MAX_CUSTOM_PROFILES);
        }
        profile.setBuiltin(false);
        customs.put(profile.getName(), profile);
        writeCustoms(customs.values());
        return profile;
    }

    /** Deletes a custom profile; built-ins cannot be deleted. Returns false if nothing matched. */
    public synchronized boolean delete(String name) {
        if (name == null || builtins.containsKey(name)) {
            return false;
        }
        Map<String, PrintPreflightProfile> customs = new LinkedHashMap<>(loadCustoms());
        if (customs.remove(name) == null) {
            return false;
        }
        writeCustoms(customs.values());
        return true;
    }

    private Map<String, PrintPreflightProfile> loadBuiltins() {
        try (InputStream in = getClass().getResourceAsStream("/" + BUILTIN_RESOURCE)) {
            if (in == null) {
                log.warn("No built-in preflight profiles resource ({})", BUILTIN_RESOURCE);
                return Map.of();
            }
            Map<String, PrintPreflightProfile> map = new LinkedHashMap<>();
            for (PrintPreflightProfile p : mapper.readValue(in, PrintPreflightProfile[].class)) {
                p.setBuiltin(true);
                map.put(p.getName(), p);
            }
            return Map.copyOf(map);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load built-in preflight profiles", e);
        }
    }

    private Map<String, PrintPreflightProfile> loadCustoms() {
        Path file = customFile();
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            Map<String, PrintPreflightProfile> map = new LinkedHashMap<>();
            for (PrintPreflightProfile p :
                    mapper.readValue(file.toFile(), PrintPreflightProfile[].class)) {
                if (p.getName() != null
                        && !p.getName().isBlank()
                        && !builtins.containsKey(p.getName())) {
                    map.put(p.getName(), p);
                }
            }
            return map;
        } catch (IOException e) {
            log.error("Cannot parse {} — ignoring custom profiles", file, e);
            return Map.of();
        }
    }

    private void writeCustoms(java.util.Collection<PrintPreflightProfile> profiles) {
        Path file = customFile();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), profiles);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist " + file, e);
        }
    }

    private Path customFile() {
        return customFile;
    }

    private void validate(PrintPreflightProfile p) {
        String name = p.getName() == null ? "" : p.getName().trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "Profile name must be 1-{0} characters",
                    MAX_NAME_LENGTH);
        }
        if (builtins.containsKey(name)) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "Profile name '{0}' is reserved by a built-in", name);
        }
        p.setName(name);
        requireRange(p.getRequiredBleedMm(), 0, Float.MAX_VALUE, "requiredBleedMm");
        requireRange(p.getMinImageDpi(), 1, Integer.MAX_VALUE, "minImageDpi");
        requireRange(p.getHairlineThresholdPt(), 0, Float.MAX_VALUE, "hairlineThresholdPt");
        requireRange(p.getMinFontSizePt(), 0, Float.MAX_VALUE, "minFontSizePt");
        requireRange(p.getSafetyMarginMm(), 0, Float.MAX_VALUE, "safetyMarginMm");
        requireRange(p.getMaxInkCoveragePercent(), 0, Integer.MAX_VALUE, "maxInkCoveragePercent");
        requireRange(p.getMinImage1BitDpi(), 1, Integer.MAX_VALUE, "minImage1BitDpi");
        requireRange(p.getMaxImageDpi(), 1, Integer.MAX_VALUE, "maxImageDpi");
        requireRange(p.getMaxSpotCount(), 0, Integer.MAX_VALUE, "maxSpotCount");
        if (p.getDisabledChecks() != null) {
            for (String code : p.getDisabledChecks()) {
                try {
                    PreflightCheck.valueOf(code);
                } catch (IllegalArgumentException e) {
                    throw ExceptionUtils.createIllegalArgumentException(
                            "error.invalidArgument", "Unknown check code in profile: {0}", code);
                }
            }
        }
        if (p.getFixups() != null) {
            for (String code : p.getFixups()) {
                if ("NONE".equalsIgnoreCase(code.trim())) {
                    continue;
                }
                try {
                    PreflightFixer.Code.valueOf(code);
                } catch (IllegalArgumentException e) {
                    throw ExceptionUtils.createIllegalArgumentException(
                            "error.invalidArgument", "Unknown fixup code in profile: {0}", code);
                }
            }
        }
        if (p.getFixupParams() != null) {
            // Serialize then run the same strict parse as a live request — a profile cannot store
            // codes or keys a fixup would reject at run time.
            try {
                String json = mapper.writeValueAsString(p.getFixupParams());
                PrintPreflightRequest probe = new PrintPreflightRequest();
                probe.setFixupParams(json);
                PreflightFixer.parseFixupParams(probe);
            } catch (JsonProcessingException e) {
                throw ExceptionUtils.createIllegalArgumentException(
                        "error.invalidArgument", "Invalid fixupParams in profile");
            }
        }
    }

    private void requireRange(Number value, double min, double max, String field) {
        if (value == null) {
            return;
        }
        double v = value.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v) || v < min || v > max) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "Profile field {0} must be between {1} and {2}",
                    field,
                    min,
                    max);
        }
    }
}
