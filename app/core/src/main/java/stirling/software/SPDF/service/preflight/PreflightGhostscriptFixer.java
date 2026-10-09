package stirling.software.SPDF.service.preflight;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.common.util.ProcessExecutor;
import stirling.software.common.util.ProcessExecutor.ProcessExecutorResult;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;

/**
 * Fixups that need a real colour engine rather than token rewriting, delegated to one Ghostscript
 * {@code pdfwrite} pass over the PDFBox-fixed document. Running a single invocation matters:
 * Ghostscript rebuilds the whole file, so each extra pass would reprocess the previous one.
 *
 * <p>Fixups degrade silently: when Ghostscript is disabled or fails, the caller gets {@code null}
 * and the PDFBox-level corrections still ship — the missing codes in {@code X-Preflight-Fixups} are
 * the audit trail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreflightGhostscriptFixer {

    private final TempFileManager tempFileManager;
    private final EndpointConfiguration endpointConfiguration;

    /**
     * Saves {@code document}, applies every wanted Ghostscript fixup in one pass and returns the
     * resulting file — ownership transfers to the caller. Returns null when there is nothing to
     * run, Ghostscript is unavailable, or the pass fails.
     */
    public TempFile apply(PDDocument document, Set<PreflightFixer.Code> wanted) throws IOException {
        if (wanted.isEmpty()) {
            return null;
        }
        if (!endpointConfiguration.isGroupEnabled("Ghostscript")) {
            log.warn(
                    "Ghostscript fixups {} requested but the Ghostscript group is disabled",
                    wanted);
            return null;
        }
        try (TempFile input = tempFileManager.createManagedTempFile(".pdf")) {
            document.save(input.getFile());
            TempFile output = tempFileManager.createManagedTempFile(".pdf");
            if (runGhostscript(input, output, wanted)) {
                return output; // caller owns it — the response stream deletes it on close
            }
            output.close();
            return null;
        }
    }

    private boolean runGhostscript(TempFile input, TempFile output, Set<PreflightFixer.Code> wanted)
            throws IOException {
        List<String> command =
                new ArrayList<>(
                        List.of("gs", "-sDEVICE=pdfwrite", "-dNOPAUSE", "-dQUIET", "-dBATCH"));
        if (wanted.contains(PreflightFixer.Code.TEXT_TO_OUTLINES)) {
            command.add("-dNoOutputFonts");
        }
        if (wanted.contains(PreflightFixer.Code.RGB_TO_CMYK)) {
            // Ghostscript's own ICC path converts images too — token rewriting cannot reach them.
            command.add("-sColorConversionStrategy=CMYK");
            command.add("-dProcessColorModel=/DeviceCMYK");
        }
        // PDF 1.3 predates transparency: pdfwrite flattens it when targeting that level.
        command.add(
                "-dCompatibilityLevel="
                        + (wanted.contains(PreflightFixer.Code.FLATTEN_TRANSPARENCY)
                                ? "1.3"
                                : "1.5"));
        command.add("-o");
        command.add(output.getAbsolutePath());
        command.add(input.getAbsolutePath());

        try {
            ProcessExecutorResult result =
                    ProcessExecutor.getInstance(ProcessExecutor.Processes.GHOSTSCRIPT)
                            .runCommandWithOutputHandling(command);
            if (result.getRc() != 0) {
                log.warn(
                        "Ghostscript fixups {} failed (rc={}): {}",
                        wanted,
                        result.getRc(),
                        result.getMessages());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
