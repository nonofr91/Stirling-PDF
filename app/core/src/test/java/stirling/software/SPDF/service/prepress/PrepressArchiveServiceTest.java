package stirling.software.SPDF.service.prepress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.SPDF.service.prepress.PrepressArchiveService.Handle;
import stirling.software.common.model.ApplicationProperties;

class PrepressArchiveServiceTest {

    @TempDir Path archiveRoot;

    private PrepressArchiveService archive;

    @BeforeEach
    void setUp() {
        ApplicationProperties props = new ApplicationProperties();
        props.getPrepress().getArchive().setRoot(archiveRoot.toString());
        archive = new PrepressArchiveService(props);
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("fileInput", name, "application/pdf", content.getBytes());
    }

    @Test
    void firstTransformCreatesChainWithSourceAndVersion() throws Exception {
        MockMultipartFile input = file("job.pdf", "original-bytes");
        Optional<Handle> handle =
                archive.recordVersion(
                        "crop", input, "cropped-bytes".getBytes(), "job_cropped.pdf", null);

        assertTrue(handle.isPresent());
        String chainId = handle.get().chainId();
        assertEquals(16, chainId.length());
        assertEquals(2, handle.get().version());

        List<Map<String, Object>> steps = archive.getChain(chainId);
        assertEquals(2, steps.size());
        assertEquals("source", steps.get(0).get("kind"));
        assertEquals("version", steps.get(1).get("kind"));
        assertEquals("crop", steps.get(1).get("tool"));
        assertEquals(1, steps.get(1).get("parent"));

        Optional<PrepressArchiveService.StoredVersion> stored = archive.resolveVersion(chainId, 2);
        assertTrue(stored.isPresent());
        assertEquals("cropped-bytes", Files.readString(stored.get().file()));
    }

    @Test
    void outputReuploadedChainsItself() throws Exception {
        MockMultipartFile input = file("job.pdf", "step-one");
        archive.recordVersion("crop", input, "after-crop".getBytes(), "job_crop.pdf", null);

        // The produced file is uploaded again for a second operation.
        MockMultipartFile intermediate = file("job_crop.pdf", "after-crop");
        Optional<Handle> second =
                archive.recordVersion(
                        "set-page-boxes",
                        intermediate,
                        "after-boxes".getBytes(),
                        "job_boxes.pdf",
                        null);

        assertTrue(second.isPresent());
        assertEquals(3, second.get().version());

        List<Map<String, Object>> steps = archive.getChain(second.get().chainId());
        assertEquals(3, steps.size());
        assertEquals(2, steps.get(2).get("parent"));
        assertEquals("set-page-boxes", steps.get(2).get("tool"));
    }

    @Test
    void sameInputTwiceStaysOnOneChain() throws Exception {
        MockMultipartFile input = file("job.pdf", "same-input");
        Handle first =
                archive.recordVersion("crop", input, "out-a".getBytes(), "a.pdf", null)
                        .orElseThrow();
        Handle second =
                archive.recordVersion("scale-pages", input, "out-b".getBytes(), "b.pdf", null)
                        .orElseThrow();

        assertEquals(first.chainId(), second.chainId());
        assertEquals(3, second.version());
        List<Map<String, Object>> steps = archive.getChain(first.chainId());
        assertEquals(3, steps.size());
        // Both transforms point at the source as parent — a fork in the workflow.
        assertEquals(1, steps.get(1).get("parent"));
        assertEquals(1, steps.get(2).get("parent"));
    }

    @Test
    void auditEntriesDoNotConsumeVersions() throws Exception {
        MockMultipartFile input = file("job.pdf", "audit-me");
        archive.recordAudit("print-preflight", input, Map.of("errors", 0, "warnings", 2));
        Optional<Handle> handle =
                archive.recordVersion("crop", input, "out".getBytes(), "o.pdf", null);

        assertTrue(handle.isPresent());
        assertEquals(2, handle.get().version()); // audit didn't take a number
        List<Map<String, Object>> steps = archive.getChain(handle.get().chainId());
        assertEquals(4, steps.size());
        assertEquals("audit", steps.get(1).get("kind"));
        assertEquals("print-preflight", steps.get(1).get("tool"));
        assertEquals(1, steps.get(1).get("onVersion"));
    }

    @Test
    void auditOnFreshInputChainsWithoutStoringBytes() throws Exception {
        archive.recordAudit("print-preflight", file("job.pdf", "audit-only"), null);

        List<Map<String, Object>> chains = archive.listChains();
        assertEquals(1, chains.size());
        String chainId = (String) chains.get(0).get("chainId");
        assertEquals(0L, ((Number) chains.get(0).get("versions")).longValue());

        List<Map<String, Object>> steps = archive.getChain(chainId);
        assertEquals(2, steps.size()); // source entry + audit
        assertEquals("source", steps.get(0).get("kind"));
        assertFalse(steps.get(0).containsKey("file"), "audit must not store source bytes");
        assertTrue(archive.resolveVersion(chainId, 1).isEmpty());
    }

    @Test
    void transformBackfillsSourceAfterAudit() throws Exception {
        MockMultipartFile input = file("job.pdf", "audited-then-transformed");
        archive.recordAudit("print-preflight", input, null);
        String chainId = (String) archive.listChains().get(0).get("chainId");

        Handle handle =
                archive.recordVersion("crop", input, "out".getBytes(), "o.pdf", null).orElseThrow();

        assertEquals(chainId, handle.chainId());
        Optional<PrepressArchiveService.StoredVersion> source = archive.resolveVersion(chainId, 1);
        assertTrue(source.isPresent(), "source bytes should be backfilled on first transform");
        assertEquals("audited-then-transformed", Files.readString(source.get().file()));
        List<Map<String, Object>> steps = archive.getChain(chainId);
        assertEquals(Boolean.TRUE, steps.get(2).get("backfilled"));
    }

    @Test
    void degenerateFilenamesFallBackToDocumentPdf() throws Exception {
        String[] names = {"..", ".", "   ", null};
        for (int i = 0; i < names.length; i++) {
            archive.recordVersion("crop", file(names[i], "x" + i), "o".getBytes(), null, null);
        }
        List<Map<String, Object>> chains = archive.listChains();
        assertEquals(4, chains.size());
        for (Map<String, Object> chain : chains) {
            String chainId = (String) chain.get("chainId");
            List<Map<String, Object>> steps = archive.getChain(chainId);
            assertEquals("document.pdf", steps.get(0).get("name"));
            assertEquals("document.pdf", archive.resolveVersion(chainId, 2).orElseThrow().name());
        }
    }

    @Test
    void chainHeadersSetOnlyWhenHandlePresent() {
        ResponseEntity<String> with = ResponseEntity.ok("x");
        PrepressArchiveService.setChainHeaders(
                with, Optional.of(new Handle("0123456789abcdef", 2)));
        assertEquals(
                "0123456789abcdef",
                with.getHeaders().getFirst(PrepressArchiveService.HEADER_CHAIN_ID));
        assertEquals("2", with.getHeaders().getFirst(PrepressArchiveService.HEADER_VERSION));

        ResponseEntity<String> without = ResponseEntity.ok("x");
        PrepressArchiveService.setChainHeaders(without, Optional.empty());
        assertFalse(without.getHeaders().containsHeader(PrepressArchiveService.HEADER_CHAIN_ID));
    }

    @Test
    void listChainsSummarizesNewestFirst() throws Exception {
        archive.recordVersion("crop", file("a.pdf", "aaa"), "x".getBytes(), "a1.pdf", null);
        // Distinct input → distinct chain.
        archive.recordVersion("crop", file("b.pdf", "bbb"), "y".getBytes(), "b1.pdf", null);

        List<Map<String, Object>> chains = archive.listChains();
        assertEquals(2, chains.size());
        assertNotEquals(chains.get(0).get("chainId"), chains.get(1).get("chainId"));
        for (Map<String, Object> c : chains) {
            assertEquals(1L, ((Number) c.get("versions")).longValue());
        }
    }

    @Test
    void disabledArchiveIsNoOp() {
        ApplicationProperties props = new ApplicationProperties();
        props.getPrepress().getArchive().setRoot(archiveRoot.toString());
        props.getPrepress().getArchive().setEnabled(false);
        PrepressArchiveService off = new PrepressArchiveService(props);

        Optional<Handle> handle =
                off.recordVersion("crop", file("x.pdf", "x"), "y".getBytes(), "y.pdf", null);
        assertTrue(handle.isEmpty());
        assertFalse(archiveRoot.resolve("chains").toFile().exists());
    }

    @Test
    void evictionKeepsSourceAndTrimsOldestVersions() throws Exception {
        ApplicationProperties props = new ApplicationProperties();
        props.getPrepress().getArchive().setRoot(archiveRoot.toString());
        props.getPrepress().getArchive().setMaxVersionsPerChain(2);
        PrepressArchiveService capped = new PrepressArchiveService(props);

        MockMultipartFile input = file("job.pdf", "root");
        Handle h1 =
                capped.recordVersion("crop", input, "v2".getBytes(), "v2.pdf", null).orElseThrow();
        capped.recordVersion("crop", file("v2.pdf", "v2"), "v3".getBytes(), "v3.pdf", null)
                .orElseThrow();
        Handle h3 =
                capped.recordVersion("crop", file("v3.pdf", "v3"), "v4".getBytes(), "v4.pdf", null)
                        .orElseThrow();

        // Source (v1) always kept; oldest transform (v2) evicted; v3+v4 retained.
        assertTrue(capped.resolveVersion(h3.chainId(), 1).isPresent());
        assertFalse(capped.resolveVersion(h3.chainId(), 2).isPresent());
        assertTrue(capped.resolveVersion(h3.chainId(), 3).isPresent());
        assertTrue(capped.resolveVersion(h3.chainId(), 4).isPresent());
        // Manifest still records the full history.
        assertEquals(4, capped.getChain(h3.chainId()).size());
        assertTrue(h1.chainId().equals(h3.chainId()));
    }
}
