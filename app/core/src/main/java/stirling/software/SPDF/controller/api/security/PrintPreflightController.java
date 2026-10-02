package stirling.software.SPDF.controller.api.security;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.security.PrintPreflightReport;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;
import stirling.software.SPDF.service.preflight.PreflightAnnotator;
import stirling.software.SPDF.service.preflight.PrintPreflightService;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.SecurityApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@SecurityApi
@RequiredArgsConstructor
@Slf4j
public class PrintPreflightController {

    private final PrintPreflightService printPreflightService;
    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

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
        validate(file, request);

        try (PDDocument document = pdfDocumentFactory.load(request)) {
            PrintPreflightReport report =
                    printPreflightService.analyze(
                            document, file.getOriginalFilename(), file.getSize(), request);
            PreflightAnnotator.annotate(document, report.getFindings());
            return WebResponseUtils.pdfDocToWebResponse(
                    document,
                    GeneralUtils.generateFilename(file.getOriginalFilename(), "_preflight.pdf"),
                    tempFileManager);
        }
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
    }
}
