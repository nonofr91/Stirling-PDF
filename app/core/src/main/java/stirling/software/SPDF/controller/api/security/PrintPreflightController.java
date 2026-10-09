package stirling.software.SPDF.controller.api.security;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.security.PrintPreflightFixAudit;
import stirling.software.SPDF.model.api.security.PrintPreflightProfile;
import stirling.software.SPDF.model.api.security.PrintPreflightReport;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;
import stirling.software.SPDF.service.preflight.PreflightA4Scaler;
import stirling.software.SPDF.service.preflight.PreflightAnnotator;
import stirling.software.SPDF.service.preflight.PreflightFixer;
import stirling.software.SPDF.service.preflight.PreflightGhostscriptFixer;
import stirling.software.SPDF.service.preflight.PreflightProfileService;
import stirling.software.SPDF.service.preflight.PreflightReportRenderer;
import stirling.software.SPDF.service.preflight.PreflightReportText;
import stirling.software.SPDF.service.preflight.PrintPreflightService;
import stirling.software.SPDF.service.prepress.PrepressArchiveService;
import stirling.software.SPDF.service.prepress.PrepressReportHeaders;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.SecurityApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@SecurityApi
@RequiredArgsConstructor
@Slf4j
public class PrintPreflightController {

    private final PrintPreflightService printPreflightService;
    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;
    private final PreflightGhostscriptFixer ghostscriptFixer;
    private final PreflightProfileService profileService;
    private final PrepressArchiveService prepressArchive;
    // Header serialization is the only JSON work here; Spring only exposes a Jackson 3 mapper,
    // so like the other prepress services this keeps its own Jackson 2 instance.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Operation(
            summary = "List preflight profiles",
            description =
                    "Named snapshots of every preflight threshold plus fixups and disabled checks."
                            + " Built-ins ship with the app; customs persist in"
                            + " configs/preflight-profiles.json.")
    @GetMapping("/print-preflight-profiles")
    public ResponseEntity<List<PrintPreflightProfile>> listProfiles() {
        return ResponseEntity.ok(profileService.list());
    }

    @Operation(
            summary = "Save a preflight profile",
            description =
                    "Creates or replaces a custom preflight profile under its name. Built-in names"
                            + " are reserved.")
    @PostMapping(
            value = "/print-preflight-profiles",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PrintPreflightProfile> saveProfile(
            @RequestBody PrintPreflightProfile profile) {
        return ResponseEntity.ok(profileService.save(profile));
    }

    @Operation(
            summary = "Delete a preflight profile",
            description = "Deletes a custom preflight profile; built-ins cannot be deleted.")
    @DeleteMapping("/print-preflight-profiles/{name}")
    public ResponseEntity<Void> deleteProfile(@PathVariable String name) {
        if (!profileService.delete(name)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @ToolIO(produces = ToolFormat.JSON)
    @Operation(
            summary = "Print preflight report",
            description =
                    "Analyzes a PDF for print production and reports issues: fonts not embedded,"
                            + " RGB or spot colors, low-resolution images, missing or unpainted bleed,"
                            + " hairline strokes, transparency, annotations inside the trim and mixed"
                            + " page sizes.")
    @AutoJobPostMapping(
            value = "/print-preflight",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    public ResponseEntity<PrintPreflightReport> printPreflight(
            @ModelAttribute PrintPreflightRequest request) throws IOException {

        MultipartFile file = request.getFileInput();
        profileService.applyProfile(request);
        validate(file, request);

        try (PDDocument document = pdfDocumentFactory.load(request)) {
            PrintPreflightReport report =
                    printPreflightService.analyze(
                            document, file.getOriginalFilename(), file.getSize(), request);
            log.info(
                    "Preflight of '{}' finished: {} error(s), {} warning(s), {} info",
                    file.getOriginalFilename(),
                    report.getCounts().getErrors(),
                    report.getCounts().getWarnings(),
                    report.getCounts().getInfos());
            prepressArchive.recordAudit(
                    "print-preflight",
                    file,
                    Map.of(
                            "errors", report.getCounts().getErrors(),
                            "warnings", report.getCounts().getWarnings(),
                            "infos", report.getCounts().getInfos(),
                            "renderedInkCoverage", request.isRenderedInkCoverage()));
            return ResponseEntity.ok(report);
        }
    }

    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Annotated print preflight",
            description =
                    "Same analysis as print-preflight, but returns a copy of the PDF with each"
                            + " located issue framed by a colored square annotation (red/orange/blue"
                            + " by severity) and a note per page for document-wide findings.")
    @AutoJobPostMapping(
            value = "/print-preflight-annotated",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    public ResponseEntity<Resource> printPreflightAnnotated(
            @ModelAttribute PrintPreflightRequest request) throws IOException {

        MultipartFile file = request.getFileInput();
        profileService.applyProfile(request);
        validate(file, request);

        try (PDDocument document = pdfDocumentFactory.load(request)) {
            PrintPreflightReport report =
                    printPreflightService.analyze(
                            document, file.getOriginalFilename(), file.getSize(), request);
            List<PDAnnotation> marks =
                    PreflightAnnotator.annotate(
                            document, report.getFindings(), PreflightReportText.bundleFor(request));
            // After annotating: the marks ride along inside each page's scaled content,
            // and report pages are inserted later already at A4.
            PreflightA4Scaler.fitToA4(document, marks);
            if (request.isIncludeSummaryPage()) {
                // Only after annotating: report pages would shift every finding's page index.
                List<PDPage> summaryPages =
                        PreflightReportRenderer.render(document, report, request);
                PreflightReportRenderer.insertAtFront(document, summaryPages);
            }
            String filename =
                    GeneralUtils.generateFilename(file.getOriginalFilename(), "_preflight.pdf");
            TempFile out = tempFileManager.createManagedTempFile(".pdf");
            try {
                document.save(out.getFile());
            } catch (IOException | RuntimeException e) {
                out.close();
                throw e;
            }
            var handle =
                    prepressArchive.recordVersion(
                            "print-preflight-annotated", file, out.getPath(), filename, null);
            ResponseEntity<Resource> response =
                    WebResponseUtils.pdfFileToWebResponse(out, filename);
            response.getHeaders()
                    .set(
                            PrepressReportHeaders.TOOL_REPORT,
                            toolReport(PrintPreflightReport.Preflight.of(report.getCounts())));
            PrepressArchiveService.setChainHeaders(response, handle);
            return response;
        }
    }

    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Print preflight report document",
            description =
                    "Same analysis as print-preflight, but returns a standalone PDF report:"
                            + " verdict, document facts, fonts, colours, images and the full"
                            + " findings list — without the source document's pages.")
    @AutoJobPostMapping(
            value = "/print-preflight-report",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    public ResponseEntity<Resource> printPreflightReport(
            @ModelAttribute PrintPreflightRequest request) throws IOException {

        MultipartFile file = request.getFileInput();
        profileService.applyProfile(request);
        validate(file, request);

        try (PDDocument document = pdfDocumentFactory.load(request);
                PDDocument reportDoc = new PDDocument()) {
            PrintPreflightReport report =
                    printPreflightService.analyze(
                            document, file.getOriginalFilename(), file.getSize(), request);
            PreflightReportRenderer.render(reportDoc, report, request);
            String filename =
                    GeneralUtils.generateFilename(
                            file.getOriginalFilename(), "_preflight-report.pdf");
            TempFile out = tempFileManager.createManagedTempFile(".pdf");
            try {
                reportDoc.save(out.getFile());
            } catch (IOException | RuntimeException e) {
                out.close();
                throw e;
            }
            var handle =
                    prepressArchive.recordVersion(
                            "print-preflight-report", file, out.getPath(), filename, null);
            ResponseEntity<Resource> response =
                    WebResponseUtils.pdfFileToWebResponse(out, filename);
            response.getHeaders()
                    .set(
                            PrepressReportHeaders.TOOL_REPORT,
                            toolReport(PrintPreflightReport.Preflight.of(report.getCounts())));
            PrepressArchiveService.setChainHeaders(response, handle);
            return response;
        }
    }

    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Print preflight fix",
            description =
                    "Applies opt-in corrections (PitStop-style fixups) to the PDF: flatten forms,"
                            + " merge spot aliases, generate missing bleed, downsample oversampled"
                            + " images, normalize page geometry, drop JavaScript/attachments and"
                            + " attach an output intent. Returns the corrected copy — the uploaded"
                            + " file is never modified. Applied fixup codes are listed in the"
                            + " X-Preflight-Fixups response header.")
    @AutoJobPostMapping(
            value = "/print-preflight-fix",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    public ResponseEntity<Resource> printPreflightFix(@ModelAttribute PrintPreflightRequest request)
            throws IOException {

        MultipartFile file = request.getFileInput();
        profileService.applyProfile(request);
        validate(file, request);

        try (PDDocument document = pdfDocumentFactory.load(request)) {
            // Analysis feeds the fixups that depend on computed facts (spot alias groups, empty
            // pages) and keeps every correction auditable against a shared finding set.
            PrintPreflightReport report =
                    printPreflightService.analyze(
                            document, file.getOriginalFilename(), file.getSize(), request);
            List<String> applied = PreflightFixer.apply(document, request, report);
            String filename =
                    GeneralUtils.generateFilename(
                            file.getOriginalFilename(), "_preflight-fixed.pdf");
            // Ghostscript fixups run last: they rebuild the whole file, so the PDFBox-level
            // corrections must already be baked into the bytes they receive.
            Set<PreflightFixer.Code> gsWanted = PreflightFixer.ghostscriptWanted(request, report);
            TempFile gsOutput = ghostscriptFixer.apply(document, gsWanted);
            if (gsOutput != null) {
                for (PreflightFixer.Code code : gsWanted) {
                    applied.add(code.name());
                }
            }
            log.info(
                    "Preflight fixups on '{}': {}",
                    file.getOriginalFilename(),
                    applied.isEmpty() ? "none applicable" : String.join(", ", applied));
            // Re-analyse the delivered document so pipeline routing sees the post-fix state —
            // the pre-fix report cannot say whether the fixups resolved everything (the
            // fix-preview endpoint already pays this same second analysis pass). gsOutput is
            // only owned by the response once pdfFileToWebResponse wraps it — every throwing
            // call before that must close it or the Ghostscript output leaks on disk.
            PrintPreflightReport postReport;
            ResponseEntity<Resource> response;
            Optional<PrepressArchiveService.Handle> handle;
            if (gsOutput != null) {
                try {
                    postReport = reanalyzeFixed(request, file, document, gsOutput);
                    handle =
                            prepressArchive.recordVersion(
                                    "print-preflight-fix",
                                    file,
                                    gsOutput.getPath(),
                                    filename,
                                    Map.of("fixups", applied));
                    response = WebResponseUtils.pdfFileToWebResponse(gsOutput, filename);
                } catch (IOException | RuntimeException e) {
                    gsOutput.close();
                    throw e;
                }
            } else {
                postReport = reanalyzeFixed(request, file, document, null);
                TempFile out = tempFileManager.createManagedTempFile(".pdf");
                try {
                    document.save(out.getFile());
                    handle =
                            prepressArchive.recordVersion(
                                    "print-preflight-fix",
                                    file,
                                    out.getPath(),
                                    filename,
                                    Map.of("fixups", applied));
                    response = WebResponseUtils.pdfFileToWebResponse(out, filename);
                } catch (IOException | RuntimeException e) {
                    out.close();
                    throw e;
                }
            }
            ResponseEntity<Resource> withHeaders =
                    ResponseEntity.status(response.getStatusCode())
                            .headers(response.getHeaders())
                            .header("X-Preflight-Fixups", String.join(", ", applied))
                            .header(
                                    PrepressReportHeaders.TOOL_REPORT,
                                    toolReport(
                                            PrintPreflightReport.Preflight.afterFix(
                                                    postReport.getCounts(),
                                                    applied,
                                                    report.getCounts())))
                            .body(response.getBody());
            PrepressArchiveService.setChainHeaders(withHeaders, handle);
            return withHeaders;
        }
    }

    @ToolIO(produces = ToolFormat.JSON)
    @Operation(
            summary = "Print preflight fix preview",
            description =
                    "Dry run of print-preflight-fix: applies the same requested fixups to an"
                            + " in-memory copy, re-analyses the result and reports which fixups"
                            + " applied plus the before/after finding sets — the corrected document"
                            + " is discarded. Use it to audit what the corrections change before"
                            + " committing to them.")
    @AutoJobPostMapping(
            value = "/print-preflight-fix-preview",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    public ResponseEntity<PrintPreflightFixAudit> printPreflightFixPreview(
            @ModelAttribute PrintPreflightRequest request) throws IOException {

        MultipartFile file = request.getFileInput();
        profileService.applyProfile(request);
        validate(file, request);

        try (PDDocument document = pdfDocumentFactory.load(request)) {
            PrintPreflightReport before =
                    printPreflightService.analyze(
                            document, file.getOriginalFilename(), file.getSize(), request);
            List<String> applied = PreflightFixer.apply(document, request, before);
            Set<PreflightFixer.Code> gsWanted = PreflightFixer.ghostscriptWanted(request, before);
            TempFile gsOutput = ghostscriptFixer.apply(document, gsWanted);
            PrintPreflightReport after;
            if (gsOutput != null) {
                try (gsOutput;
                        PDDocument fixed = pdfDocumentFactory.load(gsOutput.getPath())) {
                    for (PreflightFixer.Code code : gsWanted) {
                        applied.add(code.name());
                    }
                    after =
                            printPreflightService.analyze(
                                    fixed, file.getOriginalFilename(), file.getSize(), request);
                }
            } else {
                after =
                        printPreflightService.analyze(
                                document, file.getOriginalFilename(), file.getSize(), request);
            }
            log.info(
                    "Preflight fix preview on '{}': {} applied, {} -> {} error(s)",
                    file.getOriginalFilename(),
                    applied.isEmpty() ? "none" : String.join(", ", applied),
                    before.getCounts().getErrors(),
                    after.getCounts().getErrors());
            prepressArchive.recordAudit(
                    "print-preflight-fix-preview",
                    file,
                    Map.of(
                            "fixups", applied,
                            "errorsBefore", before.getCounts().getErrors(),
                            "errorsAfter", after.getCounts().getErrors(),
                            "warningsBefore", before.getCounts().getWarnings(),
                            "warningsAfter", after.getCounts().getWarnings()));
            return ResponseEntity.ok(PrintPreflightFixAudit.of(before, after, applied));
        }
    }

    /**
     * Analyse the document the caller actually receives: the Ghostscript output when a GS fixup
     * rebuilt the file, else the corrected PDFBox document still in memory.
     */
    private PrintPreflightReport reanalyzeFixed(
            PrintPreflightRequest request,
            MultipartFile file,
            PDDocument document,
            TempFile gsOutput)
            throws IOException {
        if (gsOutput != null) {
            try (PDDocument fixed = pdfDocumentFactory.load(gsOutput.getPath())) {
                return printPreflightService.analyze(
                        fixed, file.getOriginalFilename(), file.getSize(), request);
            }
        }
        return printPreflightService.analyze(
                document, file.getOriginalFilename(), file.getSize(), request);
    }

    private String toolReport(PrintPreflightReport.Preflight summary) {
        return PrepressReportHeaders.toolReportJson(objectMapper, summary);
    }

    private static void validate(MultipartFile file, PrintPreflightRequest request) {
        if (file == null || file.isEmpty()) {
            throw ExceptionUtils.createRuntimeException(
                    "error.pdfRequired", "PDF file is required", null);
        }
        if (!Float.isFinite(request.getRequiredBleedMm()) || request.getRequiredBleedMm() < 0) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "requiredBleedMm must be a finite non-negative number");
        }
        if (request.getMinImageDpi() < 1) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "minImageDpi must be at least 1");
        }
        if (!Float.isFinite(request.getHairlineThresholdPt())
                || request.getHairlineThresholdPt() < 0) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "hairlineThresholdPt must be a finite non-negative number");
        }
        if (!Float.isFinite(request.getMinFontSizePt()) || request.getMinFontSizePt() < 0) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "minFontSizePt must be a finite non-negative number");
        }
        if (!Float.isFinite(request.getSafetyMarginMm()) || request.getSafetyMarginMm() < 0) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "safetyMarginMm must be a finite non-negative number");
        }
        if (request.getMaxInkCoveragePercent() < 0) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "maxInkCoveragePercent must be non-negative");
        }
        if (request.getMinImage1BitDpi() < 1) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "minImage1BitDpi must be at least 1");
        }
        if (request.getMaxImageDpi() < 1) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "maxImageDpi must be at least 1");
        }
        if (request.getMaxSpotCount() < 0) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "maxSpotCount must be non-negative");
        }
    }
}
