package stirling.software.SPDF.controller.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.service.prepress.PrepressArchiveService;
import stirling.software.SPDF.service.prepress.PrepressArchiveService.StoredVersion;
import stirling.software.common.annotations.api.GeneralApi;

/**
 * Read API over {@link PrepressArchiveService}: lists the recorded document chains, their step
 * history, and streams back any stored version. Write side lives in the prepress endpoints
 * themselves via {@code recordVersion}/{@code recordAudit}.
 *
 * <p>The archive is instance-wide: anyone who can reach these endpoints sees every document the
 * prepress tools have processed, matching Stirling's shared-instance model. Deployments that must
 * not retain or expose documents turn the feature off via {@code prepress.archive.enabled} or
 * disable the {@code prepress-archive} endpoint.
 */
@GeneralApi
@RequiredArgsConstructor
public class PrepressArchiveController {

    private final PrepressArchiveService archive;

    @GetMapping("/prepress-archive")
    @ResponseBody
    @Operation(
            summary = "List prepress document chains",
            description =
                    "Every document chain known to the archive: source file, version count, last"
                            + " operation. Newest activity first.")
    public ResponseEntity<List<Map<String, Object>>> listChains() throws IOException {
        return ResponseEntity.ok(archive.listChains());
    }

    @GetMapping("/prepress-archive/{chainId}")
    @ResponseBody
    @Operation(
            summary = "Show one document chain",
            description =
                    "Full step history of a chain — source upload, each transform with the applied"
                            + " parameters and content hashes, and audit entries.")
    public ResponseEntity<List<Map<String, Object>>> getChain(@PathVariable String chainId)
            throws IOException {
        List<Map<String, Object>> steps = archive.getChain(chainId);
        if (steps.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown chain " + chainId);
        }
        return ResponseEntity.ok(steps);
    }

    @GetMapping("/prepress-archive/{chainId}/versions/{version}")
    @Operation(
            summary = "Download one archived version",
            description =
                    "Streams the stored bytes of a chain version, byte-identical to what the"
                            + " operation originally produced.")
    public ResponseEntity<FileSystemResource> downloadVersion(
            @PathVariable String chainId, @PathVariable int version) throws IOException {
        StoredVersion stored =
                archive.resolveVersion(chainId, version)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "no stored version " + version));
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + stored.name() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(java.nio.file.Files.size(stored.file()))
                .body(new FileSystemResource(stored.file()));
    }
}
