package stirling.software.SPDF.service.preflight;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.SPDF.model.api.security.PrintPreflightProfile;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;

class PreflightProfileServiceTest {

    @TempDir Path dir;

    private PreflightProfileService service;

    @BeforeEach
    void setUp() {
        service = new PreflightProfileService(dir.resolve("preflight-profiles.json"));
    }

    private PrintPreflightProfile profile(String name) {
        PrintPreflightProfile p = new PrintPreflightProfile();
        p.setName(name);
        p.setRequiredBleedMm(5f);
        p.setMinImageDpi(300);
        p.setFixups(List.of("EXTEND_BLEED", "RGB_TO_CMYK"));
        p.setDisabledChecks(List.of("SAFETY_MARGIN"));
        return p;
    }

    @Test
    void builtinsLoadFromClasspath() {
        List<PrintPreflightProfile> profiles = service.list();
        assertFalse(profiles.isEmpty());
        PrintPreflightProfile offset = service.find("offset-press").orElseThrow();
        assertTrue(offset.isBuiltin());
        assertEquals(300, offset.getMaxInkCoveragePercent());
    }

    @Test
    void saveListReloadPersistAcrossInstances() {
        service.save(profile("client-a"));

        // A fresh service instance must see the profile: it reads the persisted file.
        PreflightProfileService reloaded =
                new PreflightProfileService(dir.resolve("preflight-profiles.json"));
        PrintPreflightProfile saved = reloaded.find("client-a").orElseThrow();
        assertFalse(saved.isBuiltin());
        assertEquals(5f, saved.getRequiredBleedMm());
        assertEquals(List.of("EXTEND_BLEED", "RGB_TO_CMYK"), saved.getFixups());
        assertTrue(reloaded.list().size() > service.list().size() - 1);
    }

    @Test
    void saveOverwritesSameName() {
        service.save(profile("client-a"));
        PrintPreflightProfile updated = profile("client-a");
        updated.setMinImageDpi(72);
        service.save(updated);

        assertEquals(72, service.find("client-a").orElseThrow().getMinImageDpi());
        long count = service.list().stream().filter(p -> p.getName().equals("client-a")).count();
        assertEquals(1, count);
    }

    @Test
    void deleteRemovesCustomOnly() {
        service.save(profile("client-a"));
        assertTrue(service.delete("client-a"));
        assertFalse(service.delete("client-a"));
        assertTrue(service.find("client-a").isEmpty());
        assertFalse(service.delete("offset-press"), "built-ins must not be deletable");
        assertTrue(service.find("offset-press").isPresent());
    }

    @Test
    void builtinNamesAreReserved() {
        assertThrows(IllegalArgumentException.class, () -> service.save(profile("offset-press")));
    }

    @Test
    void saveRejectsBlankNameAndBadValues() {
        PrintPreflightProfile blank = profile(" ");
        assertThrows(IllegalArgumentException.class, () -> service.save(blank));

        PrintPreflightProfile negative = profile("bad");
        negative.setRequiredBleedMm(-1f);
        assertThrows(IllegalArgumentException.class, () -> service.save(negative));

        PrintPreflightProfile unknownCheck = profile("bad2");
        unknownCheck.setDisabledChecks(List.of("NO_SUCH_CHECK"));
        assertThrows(IllegalArgumentException.class, () -> service.save(unknownCheck));

        PrintPreflightProfile unknownFixup = profile("bad3");
        unknownFixup.setFixups(List.of("FLY_TO_THE_MOON"));
        assertThrows(IllegalArgumentException.class, () -> service.save(unknownFixup));
    }

    @Test
    void applyProfileOverridesThresholds() {
        service.save(profile("client-a"));
        PrintPreflightRequest request = new PrintPreflightRequest();
        request.setProfileName("client-a");
        request.setMinImageDpi(150);

        service.applyProfile(request);

        assertEquals(300, request.getMinImageDpi());
        assertEquals(5f, request.getRequiredBleedMm());
        assertEquals(List.of("SAFETY_MARGIN"), request.getDisabledChecks());
        assertEquals(List.of("EXTEND_BLEED", "RGB_TO_CMYK"), request.getFixups());
    }

    @Test
    void applyProfileIsNoOpWithoutName() {
        PrintPreflightRequest request = new PrintPreflightRequest();
        service.applyProfile(request);
        assertEquals(150, request.getMinImageDpi());
        request.setProfileName(" ");
        service.applyProfile(request);
        assertEquals(150, request.getMinImageDpi());
    }

    @Test
    void applyProfileThrowsOnUnknownName() {
        PrintPreflightRequest request = new PrintPreflightRequest();
        request.setProfileName("ghost");
        assertThrows(IllegalArgumentException.class, () -> service.applyProfile(request));
    }

    @Test
    void partialProfileLeavesRequestDefaults() {
        PrintPreflightProfile partial = new PrintPreflightProfile();
        partial.setName("partial");
        partial.setMaxInkCoveragePercent(280);
        service.save(partial);

        PrintPreflightRequest request = new PrintPreflightRequest();
        request.setProfileName("partial");
        service.applyProfile(request);

        assertEquals(280, request.getMaxInkCoveragePercent());
        assertEquals(150, request.getMinImageDpi(), "untouched fields keep request defaults");
    }

    @Test
    void noneSentinelDisablesAllFixups() {
        PrintPreflightRequest request = new PrintPreflightRequest();
        request.setFixups(List.of("NONE"));
        assertTrue(
                PreflightFixer.resolveWanted(request.getFixups()).isEmpty(),
                "NONE must resolve to an empty fixup set");
    }

    @Test
    void fixupParamsRoundTripThroughProfile() throws Exception {
        PrintPreflightProfile p = profile("client-params");
        p.setFixupParams(
                java.util.Map.of(
                        "EXTEND_BLEED", java.util.Map.of("method", "PIXEL_REPEAT"),
                        "DOWNSAMPLE_IMAGES", java.util.Map.of("jpegQuality", 0.75)));
        service.save(p);

        PreflightProfileService reloaded =
                new PreflightProfileService(dir.resolve("preflight-profiles.json"));
        PrintPreflightRequest request = new PrintPreflightRequest();
        request.setProfileName("client-params");
        reloaded.applyProfile(request);

        String json = request.getFixupParams();
        assertNotNull(json, "profile fixupParams must serialize into the request field");
        com.fasterxml.jackson.databind.JsonNode parsed =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals("PIXEL_REPEAT", parsed.get("EXTEND_BLEED").get("method").asText());
        assertEquals(0.75, parsed.get("DOWNSAMPLE_IMAGES").get("jpegQuality").asDouble(), 0.001);
        // And the request-side contract still accepts what the profile emitted.
        assertDoesNotThrow(() -> PreflightFixer.parseFixupParams(request));
    }

    @Test
    void saveRejectsBadFixupParams() {
        PrintPreflightProfile unknownFixup = profile("bad-params");
        unknownFixup.setFixupParams(java.util.Map.of("NOT_A_FIXUP", java.util.Map.of()));
        assertThrows(IllegalArgumentException.class, () -> service.save(unknownFixup));

        PrintPreflightProfile unknownKey = profile("bad-params2");
        unknownKey.setFixupParams(java.util.Map.of("EXTEND_BLEED", java.util.Map.of("bogus", 1)));
        assertThrows(IllegalArgumentException.class, () -> service.save(unknownKey));

        PrintPreflightProfile badValue = profile("bad-params3");
        badValue.setFixupParams(
                java.util.Map.of("DOWNSAMPLE_IMAGES", java.util.Map.of("jpegQuality", 9)));
        assertThrows(IllegalArgumentException.class, () -> service.save(badValue));
    }
}
