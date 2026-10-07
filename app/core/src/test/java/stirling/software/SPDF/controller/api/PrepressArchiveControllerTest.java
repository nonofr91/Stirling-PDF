package stirling.software.SPDF.controller.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.service.prepress.PrepressArchiveService;
import stirling.software.SPDF.service.prepress.PrepressArchiveService.Handle;
import stirling.software.common.model.ApplicationProperties;

class PrepressArchiveControllerTest {

    @TempDir Path archiveRoot;

    private PrepressArchiveService archive;
    private PrepressArchiveController controller;

    @BeforeEach
    void setUp() {
        ApplicationProperties props = new ApplicationProperties();
        props.getPrepress().getArchive().setRoot(archiveRoot.toString());
        archive = new PrepressArchiveService(props);
        controller = new PrepressArchiveController(archive);
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("fileInput", name, "application/pdf", content.getBytes());
    }

    @Test
    void downloadServesStoredBytesVerbatim() throws Exception {
        Handle handle =
                archive.recordVersion(
                                "crop",
                                file("job.pdf", "in"),
                                "archived-out".getBytes(),
                                "o.pdf",
                                null)
                        .orElseThrow();

        ResponseEntity<FileSystemResource> response =
                controller.downloadVersion(handle.chainId(), handle.version());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        FileSystemResource body = response.getBody();
        assertTrue(body != null && body.exists());
        assertArrayEquals("archived-out".getBytes(), Files.readAllBytes(body.getFile().toPath()));
    }

    @Test
    void unknownOrMalformedChainIs404() {
        assertThrows(ResponseStatusException.class, () -> controller.getChain("zzzzzzzzzzzzzzzz"));
        assertThrows(ResponseStatusException.class, () -> controller.getChain("0123456789abcdef"));
        assertThrows(
                ResponseStatusException.class,
                () -> controller.downloadVersion("0123456789abcdef", 1));
    }

    @Test
    void chainListingAndDetailRoundTrip() throws Exception {
        archive.recordVersion("crop", file("job.pdf", "in"), "out".getBytes(), "o.pdf", null);

        List<Map<String, Object>> chains = controller.listChains().getBody();
        assertEquals(1, chains.size());
        String chainId = (String) chains.get(0).get("chainId");

        List<Map<String, Object>> steps = controller.getChain(chainId).getBody();
        assertEquals(2, steps.size());
        assertEquals("source", steps.get(0).get("kind"));
        assertEquals("version", steps.get(1).get("kind"));
    }
}
