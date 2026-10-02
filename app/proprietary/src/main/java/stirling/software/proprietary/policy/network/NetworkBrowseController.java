package stirling.software.proprietary.policy.network;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import stirling.software.proprietary.policy.source.Source;
import stirling.software.proprietary.policy.source.SourceAccessGuard;
import stirling.software.proprietary.policy.source.SourceStore;

/**
 * Interactive browse/read/write on a stored network source (FTP, SFTP, SMB). The source supplies
 * the connection and the root directory; relative paths underneath it are sanitized so a crafted
 * {@code dir}/{@code path} cannot escape the configured root. Each call opens its own short-lived
 * {@link RemoteFileClient}, matching the layer's no-pooling contract.
 */
@RestController
@RequestMapping("/api/v1/sources")
@Hidden
@RequiredArgsConstructor
@Tag(name = "Sources", description = "Reusable policy input connections")
public class NetworkBrowseController {

    private final SourceStore sourceStore;
    private final SourceAccessGuard sourceAccessGuard;
    private final NetworkConnectionResolver connectionResolver;
    private final RemoteFileClientFactory clientFactory;

    @GetMapping("/{sourceId}/network/list")
    @Operation(summary = "List one directory of a network source")
    public ResponseEntity<List<RemoteEntry>> list(
            @PathVariable String sourceId, @RequestParam(required = false) String dir) {
        Source source = requireNetworkSource(sourceId);
        NetworkConfig config = connectionResolver.resolve(source.options());
        try (RemoteFileClient client = clientFactory.connect(config)) {
            String root = resolvePath(config, null);
            return ResponseEntity.ok(relativize(client.browse(resolvePath(config, dir)), root));
        } catch (IOException e) {
            throw remoteFailure("list", e);
        }
    }

    @GetMapping("/{sourceId}/network/file")
    @Operation(summary = "Download one file from a network source")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String sourceId, @RequestParam String path) {
        Source source = requireNetworkSource(sourceId);
        NetworkConfig config = connectionResolver.resolve(source.options());
        String remotePath = resolvePath(config, path);
        String name = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        // The client must stay open while the response streams: ownership moves into the body.
        final RemoteFileClient client;
        try {
            client = clientFactory.connect(config);
        } catch (IOException e) {
            throw remoteFailure("connect", e);
        }
        StreamingResponseBody body =
                output -> {
                    try (client;
                            InputStream in = client.open(remotePath)) {
                        in.transferTo(output);
                    }
                };
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''"
                                + java.net.URLEncoder.encode(
                                                name, java.nio.charset.StandardCharsets.UTF_8)
                                        .replace("+", "%20"))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    @PostMapping(path = "/{sourceId}/network/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload one file into a network source directory")
    public ResponseEntity<Void> upload(
            @PathVariable String sourceId,
            @RequestParam(required = false) String dir,
            @RequestParam("fileInput") MultipartFile file)
            throws IOException {
        Source source = requireNetworkSource(sourceId);
        String filename = safeFilename(file.getOriginalFilename());
        NetworkConfig config = connectionResolver.resolve(source.options());
        String targetDir = resolvePath(config, dir);
        String target = targetDir.isBlank() ? filename : targetDir + "/" + filename;
        try (RemoteFileClient client = clientFactory.connect(config);
                InputStream data = file.getInputStream()) {
            client.write(target, data);
        } catch (IOException e) {
            throw remoteFailure("write", e);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * The source must exist, belong to the caller's team, be enabled, and speak a network protocol;
     * anything else is 404 so ids and types cannot be probed.
     */
    private Source requireNetworkSource(String sourceId) {
        Source source = sourceStore.get(sourceId).filter(sourceAccessGuard::canAccess).orElse(null);
        if (source == null
                || !source.enabled()
                || NetworkProtocol.forSourceType(source.type()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such network source");
        }
        return source;
    }

    /**
     * The source's configured root joined with a caller-supplied relative path. Absolute paths and
     * {@code ..} segments are refused outright; stray separators and dot segments are normalized
     * away. A blank result means the root itself.
     */
    static String resolvePath(NetworkConfig config, String relative) {
        String root = config.directory() == null ? "" : config.directory().trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        StringBuilder out = new StringBuilder(root);
        if (relative != null && !relative.isBlank()) {
            String rel = relative.replace('\\', '/').trim();
            if (rel.startsWith("/")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "path must be relative to the source root");
            }
            for (String segment : rel.split("/")) {
                if (segment.isBlank() || segment.equals(".")) {
                    continue;
                }
                if (segment.equals("..")) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "path must stay inside the source root");
                }
                if (!out.isEmpty()) {
                    out.append('/');
                }
                out.append(segment);
            }
        }
        return out.toString();
    }

    /**
     * Clients report absolute remote paths; callers only ever see paths relative to the source
     * root, so an entry's {@code path} can be echoed straight back as {@code dir}/{@code path}
     * without leaking the configured root layout.
     */
    private static List<RemoteEntry> relativize(List<RemoteEntry> entries, String root) {
        String prefix = root.replace('\\', '/');
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        String withSlash = prefix.isEmpty() ? "" : prefix + "/";
        List<RemoteEntry> out = new java.util.ArrayList<>(entries.size());
        for (RemoteEntry entry : entries) {
            String path = entry.path().replace('\\', '/');
            if (!withSlash.isEmpty() && path.startsWith(withSlash)) {
                path = path.substring(withSlash.length());
            } else if (path.startsWith("/")) {
                path = path.substring(1);
            }
            out.add(
                    new RemoteEntry(
                            path,
                            entry.name(),
                            entry.directory(),
                            entry.size(),
                            entry.lastModifiedMs()));
        }
        return out;
    }

    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "uploaded file has no name");
        }
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.startsWith(".")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid remote filename: " + filename);
        }
        return name;
    }

    private static ResponseStatusException remoteFailure(String op, IOException e) {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "network source " + op + " failed: " + e.getMessage(), e);
    }
}
