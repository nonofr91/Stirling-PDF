package stirling.software.proprietary.policy.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import stirling.software.proprietary.policy.source.Source;
import stirling.software.proprietary.policy.source.SourceAccessGuard;
import stirling.software.proprietary.policy.source.SourceStore;

/**
 * {@link NetworkBrowseController} over a fake remote: listing, streaming download, upload write,
 * the source-shape guards (unknown id, disabled, non-network type, wrong team), and the path
 * sanitizer that keeps relative paths inside the source root.
 */
class NetworkBrowseControllerTest {

    private SourceStore store;
    private SourceAccessGuard guard;
    private NetworkConnectionResolver resolver;
    private RemoteFileClientFactory factory;
    private FakeRemote remote;
    private NetworkBrowseController controller;

    @BeforeEach
    void setUp() {
        store = mock(SourceStore.class);
        guard = mock(SourceAccessGuard.class);
        resolver = mock(NetworkConnectionResolver.class);
        remote = new FakeRemote();
        factory = mock(RemoteFileClientFactory.class);
        controller = new NetworkBrowseController(store, guard, resolver, factory);
        when(guard.canAccess(any())).thenReturn(true);
        try {
            when(factory.connect(any())).thenAnswer(inv -> remote);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Source ftpSource(Map<String, Object> options) {
        return new Source("s1", "ftp box", "ftp", options, true, "me", 1L);
    }

    private NetworkConfig config() {
        return new NetworkConfig(
                NetworkProtocol.FTP,
                "ftp.local",
                21,
                "u",
                "p",
                null,
                null,
                null,
                null,
                null,
                NetworkConfig.FtpSecurity.NONE,
                true,
                "incoming",
                false,
                false);
    }

    private void wire(Source source, NetworkConfig config) {
        when(store.get(source.id())).thenReturn(Optional.of(source));
        when(resolver.resolve(source.options())).thenReturn(config);
    }

    @Test
    void listReturnsDirectoriesAndFilesUnderTheSourceRoot() {
        Source source = ftpSource(Map.of("connectionId", 7L, "directory", "incoming"));
        wire(source, config());
        remote.entries.put(
                "incoming",
                List.of(
                        new RemoteEntry("incoming/sub", "sub", true, 0, 0),
                        new RemoteEntry("incoming/a.pdf", "a.pdf", false, 12, 99)));

        ResponseEntity<List<RemoteEntry>> response = controller.list("s1", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().size());
        assertTrue(response.getBody().get(0).directory());
        // Caller-facing paths are relative to the source root so they can be echoed back.
        assertEquals("sub", response.getBody().get(0).path());
        assertEquals("a.pdf", response.getBody().get(1).path());
    }

    @Test
    void downloadStreamsTheRemoteFile() throws IOException {
        Source source = ftpSource(Map.of("connectionId", 7L, "directory", "incoming"));
        wire(source, config());
        remote.files.put("incoming/nested/doc.pdf", "%PDF-fake".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<StreamingResponseBody> response =
                controller.download("s1", "nested/doc.pdf");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertEquals("%PDF-fake", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void uploadWritesIntoTheSourceRoot() {
        Source source = ftpSource(Map.of("connectionId", 7L, "directory", "incoming"));
        wire(source, config());
        MockMultipartFile file =
                new MockMultipartFile(
                        "fileInput", "out.pdf", "application/pdf", new byte[] {1, 2, 3});

        ResponseEntity<Void> response;
        try {
            response = controller.upload("s1", "done", file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(remote.files.containsKey("incoming/done/out.pdf"));
    }

    @Test
    void nonNetworkSourceIsNotFound() {
        Source folder = new Source("s2", "folder", "folder", Map.of(), true, "me", 1L);
        when(store.get("s2")).thenReturn(Optional.of(folder));
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> controller.list("s2", null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void disabledSourceIsNotFound() {
        Source source = new Source("s3", "ftp box", "ftp", Map.of(), false, "me", 1L);
        when(store.get("s3")).thenReturn(Optional.of(source));
        assertThrows(ResponseStatusException.class, () -> controller.list("s3", null));
    }

    @Test
    void foreignTeamSourceIsNotFound() {
        Source source = ftpSource(Map.of());
        when(store.get("s1")).thenReturn(Optional.of(source));
        when(guard.canAccess(any())).thenReturn(false);
        assertThrows(ResponseStatusException.class, () -> controller.list("s1", null));
    }

    @Test
    void pathTraversalIsRejected() {
        assertThrows(
                ResponseStatusException.class,
                () -> NetworkBrowseController.resolvePath(config(), "../secrets"));
        assertThrows(
                ResponseStatusException.class,
                () -> NetworkBrowseController.resolvePath(config(), "/etc/passwd"));
        assertThrows(
                ResponseStatusException.class,
                () -> NetworkBrowseController.resolvePath(config(), "a/../../b"));
    }

    @Test
    void relativePathJoinsUnderTheSourceRoot() {
        assertEquals("incoming/sub", NetworkBrowseController.resolvePath(config(), "sub"));
        assertEquals("incoming/a/b", NetworkBrowseController.resolvePath(config(), "a//b/./"));
        assertEquals("incoming", NetworkBrowseController.resolvePath(config(), null));
        assertEquals("incoming", NetworkBrowseController.resolvePath(config(), "  "));
    }

    /** In-memory remote: browse and open read {@link #files}, write stores into it. */
    private static final class FakeRemote implements RemoteFileClient {
        private final Map<String, byte[]> files = new LinkedHashMap<>();
        private final Map<String, List<RemoteEntry>> entries = new LinkedHashMap<>();

        @Override
        public List<RemoteFile> list(String directory, boolean recursive) {
            return List.of();
        }

        @Override
        public RemoteFile stat(String path) {
            return null;
        }

        @Override
        public InputStream open(String path) {
            byte[] content = files.get(path);
            return content == null ? null : new ByteArrayInputStream(content);
        }

        @Override
        public List<RemoteEntry> browse(String directory) {
            return entries.getOrDefault(directory, List.of());
        }

        @Override
        public void write(String path, InputStream data) throws IOException {
            files.put(path, data.readAllBytes());
        }

        @Override
        public void delete(String path) {
            files.remove(path);
        }

        @Override
        public void close() {}
    }
}
