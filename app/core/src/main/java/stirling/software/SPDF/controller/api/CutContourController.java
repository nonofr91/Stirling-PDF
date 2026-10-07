package stirling.software.SPDF.controller.api;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.general.CutContourPreview;
import stirling.software.SPDF.model.api.general.CutContourRequest;
import stirling.software.SPDF.service.prepress.PrepressArchiveService;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.GeneralApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.service.SubjectMattingService;
import stirling.software.common.util.CutContourGenerator;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.SilhouetteTracer;
import stirling.software.common.util.SilhouetteTracer.ExtractionMode;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@GeneralApi
@RequiredArgsConstructor
@Slf4j
public class CutContourController {

    private static final String DEFAULT_AI_MODEL = "u2net";

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    /** Optional: only present in builds bundling the ONNX runtime with a matting model. */
    private final ObjectProvider<SubjectMattingService> mattingServiceProvider;

    private final PrepressArchiveService prepressArchive;

    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Create cut contour",
            description =
                    "Extracts the subject silhouette of each page (transparency, uniform"
                            + " background or AI matting), then writes a closed vector cut path in a"
                            + " spot colour (default CutContour) on a dedicated ISO 19593-1 cutting"
                            + " layer. Optionally clips the artwork to the contour, extends it with"
                            + " bleed beyond the cut line and updates TrimBox/BleedBox.")
    @AutoJobPostMapping(
            value = "/cut-contour",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    public ResponseEntity<Resource> cutContour(@ModelAttribute CutContourRequest request)
            throws IOException {
        SilhouetteTracer.Settings traceSettings = toTraceSettings(validate(request));
        CutContourGenerator.Settings genSettings = toGeneratorSettings(request);
        Function<BufferedImage, float[]> engine = resolveMatting(request, traceSettings);

        try (PDDocument document = pdfDocumentFactory.load(request)) {
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                SilhouetteTracer.TraceResult trace = tracePage(document, i, traceSettings, engine);
                CutContourGenerator.apply(document, i, trace, genSettings);
            }
            String filename =
                    GeneralUtils.generateFilename(
                            request.getFileInput().getOriginalFilename(), "_cutcontour.pdf");
            TempFile out = tempFileManager.createManagedTempFile(".pdf");
            try {
                document.save(out.getFile());
            } catch (IOException | RuntimeException e) {
                out.close();
                throw e;
            }
            var handle =
                    prepressArchive.recordVersion(
                            "cut-contour", request.getFileInput(), out.getPath(), filename, null);
            ResponseEntity<Resource> response =
                    WebResponseUtils.pdfFileToWebResponse(out, filename);
            PrepressArchiveService.setChainHeaders(response, handle);
            return response;
        }
    }

    @ToolIO(produces = ToolFormat.JSON)
    @Operation(
            summary = "Preview cut contour",
            description =
                    "Same extraction as cut-contour but returns the traced paths as JSON (page"
                            + " coordinates in points) without modifying the PDF, so the UI can"
                            + " draw the contour over the document.")
    @AutoJobPostMapping(
            value = "/cut-contour-preview",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    public ResponseEntity<CutContourPreview> cutContourPreview(
            @ModelAttribute CutContourRequest request) throws IOException {
        SilhouetteTracer.Settings traceSettings = toTraceSettings(validate(request));
        Function<BufferedImage, float[]> engine = resolveMatting(request, traceSettings);

        CutContourPreview preview = new CutContourPreview();
        List<CutContourPreview.Page> pages = new ArrayList<>();
        try (PDDocument document = pdfDocumentFactory.load(request)) {
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                SilhouetteTracer.TraceResult trace = tracePage(document, i, traceSettings, engine);
                CutContourPreview.Page p = new CutContourPreview.Page();
                p.setPage(i + 1);
                p.setMode(trace.modeUsed.name());
                p.setSpace(
                        new float[] {
                            trace.userSpace.getLowerLeftX(),
                            trace.userSpace.getLowerLeftY(),
                            trace.userSpace.getWidth(),
                            trace.userSpace.getHeight()
                        });
                List<List<Float>> paths = new ArrayList<>(trace.paths.size());
                for (var ring : trace.paths) {
                    List<Float> flat = new ArrayList<>(ring.size() * 2);
                    for (var pt : ring) {
                        flat.add(pt.x);
                        flat.add(pt.y);
                    }
                    paths.add(flat);
                }
                p.setPaths(paths);
                p.setHoles(trace.holeFlags);
                pages.add(p);
            }
        }
        preview.setPages(pages);
        prepressArchive.recordAudit(
                "cut-contour-preview", request.getFileInput(), Map.of("pages", pages.size()));
        return ResponseEntity.ok(preview);
    }

    private SilhouetteTracer.TraceResult tracePage(
            PDDocument document,
            int pageIndex,
            SilhouetteTracer.Settings settings,
            Function<BufferedImage, float[]> engine)
            throws IOException {
        try {
            return SilhouetteTracer.trace(document, pageIndex, settings, engine);
        } catch (IllegalArgumentException e) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "Page " + (pageIndex + 1) + ": " + e.getMessage());
        }
    }

    private static CutContourRequest validate(CutContourRequest request) {
        MultipartFile file = request.getFileInput();
        if (file == null || file.isEmpty()) {
            throw ExceptionUtils.createRuntimeException(
                    "error.pdfRequired", "PDF file is required", null);
        }
        parseMode(request.getExtractionMode()); // throws on unknown value
        for (ExtractionMode m : parseAutoOrder(request.getAutoOrder())) {
            if (m == ExtractionMode.AUTO) {
                throw ExceptionUtils.createIllegalArgumentException(
                        "error.invalidArgument", "autoOrder cannot contain AUTO");
            }
        }
        if (request.getDpi() < SilhouetteTracer.MIN_DPI
                || request.getDpi() > SilhouetteTracer.MAX_DPI) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "dpi must be between "
                            + SilhouetteTracer.MIN_DPI
                            + " and "
                            + SilhouetteTracer.MAX_DPI);
        }
        if (request.getAlphaThreshold() < 0
                || request.getAlphaThreshold() > 255
                || request.getBackgroundTolerance() < 0
                || request.getBackgroundTolerance() > 255) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "alphaThreshold and backgroundTolerance must be within 0..255");
        }
        if (!Float.isFinite(request.getMinAreaMm2()) || request.getMinAreaMm2() < 0) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "minAreaMm2 must be a finite non-negative number");
        }
        if (request.getSmoothness() < 0 || request.getSmoothness() > 100) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "smoothness must be within 0..100");
        }
        if (!Float.isFinite(request.getOffsetMm()) || Math.abs(request.getOffsetMm()) > 50) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "offsetMm must be finite within ±50 mm");
        }
        if (request.getAiThreshold() < 0 || request.getAiThreshold() > 1) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "aiThreshold must be within 0..1");
        }
        if (request.getSpotName() == null || request.getSpotName().isBlank()) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "spotName must not be blank");
        }
        if (!Float.isFinite(request.getStrokeWidthPt()) || request.getStrokeWidthPt() <= 0) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "strokeWidthPt must be a positive finite number");
        }
        if (!Float.isFinite(request.getBleedMm())
                || request.getBleedMm() < 0
                || request.getBleedMm() > 50) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "bleedMm must be finite within 0..50 mm");
        }
        return request;
    }

    private static ExtractionMode parseMode(String mode) {
        try {
            return ExtractionMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "extractionMode must be one of ALPHA, BACKGROUND, AI, AUTO");
        }
    }

    private static List<ExtractionMode> parseAutoOrder(String autoOrder) {
        List<ExtractionMode> out = new ArrayList<>();
        if (autoOrder == null || autoOrder.isBlank()) {
            return List.of(ExtractionMode.ALPHA, ExtractionMode.BACKGROUND, ExtractionMode.AI);
        }
        for (String part : autoOrder.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                out.add(ExtractionMode.valueOf(p.toUpperCase(Locale.ROOT)));
            } catch (RuntimeException e) {
                throw ExceptionUtils.createIllegalArgumentException(
                        "error.invalidArgument", "Unknown autoOrder entry: " + p);
            }
        }
        if (out.isEmpty()) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "autoOrder must name at least one source");
        }
        return out;
    }

    private static float[] parseRoi(String roi) {
        if (roi == null || roi.isBlank()) {
            return null;
        }
        String[] parts = roi.split(",");
        if (parts.length < 6 || parts.length % 2 != 0) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "roi needs at least 3 x,y pairs");
        }
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                throw ExceptionUtils.createIllegalArgumentException(
                        "error.invalidArgument", "roi holds non-numeric value: " + parts[i]);
            }
            if (out[i] < -0.05f || out[i] > 1.05f) {
                throw ExceptionUtils.createIllegalArgumentException(
                        "error.invalidArgument", "roi coordinates are page fractions 0..1");
            }
        }
        return out;
    }

    private SilhouetteTracer.Settings toTraceSettings(CutContourRequest request) {
        SilhouetteTracer.Settings s = new SilhouetteTracer.Settings();
        s.dpi = request.getDpi();
        s.mode = parseMode(request.getExtractionMode());
        s.autoOrder = parseAutoOrder(request.getAutoOrder());
        s.alphaThreshold = request.getAlphaThreshold();
        s.backgroundTolerance = request.getBackgroundTolerance();
        s.mergeGapMm = request.getMergeGapMm();
        s.roi = parseRoi(request.getRoi());
        s.roiPage = request.getRoiPage();
        s.minAreaMm2 = request.getMinAreaMm2();
        s.smoothness = request.getSmoothness();
        s.offsetMm = request.getOffsetMm();
        s.keepHoles = request.isKeepHoles();
        s.aiThreshold = request.getAiThreshold();
        s.aiModelId =
                request.getAiModelId() == null || request.getAiModelId().isBlank()
                        ? DEFAULT_AI_MODEL
                        : request.getAiModelId().trim();
        return s;
    }

    private static CutContourGenerator.Settings toGeneratorSettings(CutContourRequest request) {
        CutContourGenerator.Settings s = new CutContourGenerator.Settings();
        s.spotName = request.getSpotName().trim();
        s.strokeWidthPt = request.getStrokeWidthPt();
        s.clipArtwork = request.isClipArtwork();
        s.bleedMm = request.getBleedMm();
        s.trimToContour = request.isTrimToContour();
        s.processingSteps = request.isProcessingSteps();
        s.layerName = request.getLayerName();
        return s;
    }

    private Function<BufferedImage, float[]> resolveMatting(
            CutContourRequest request, SilhouetteTracer.Settings settings) {
        boolean aiParticipates =
                settings.mode == ExtractionMode.AI
                        || settings.autoOrder.contains(ExtractionMode.AI);
        if (!aiParticipates) {
            return null;
        }
        SubjectMattingService service = mattingServiceProvider.getIfAvailable();
        String modelId = settings.aiModelId;
        if (service != null && service.isKnownModel(modelId) && service.isAvailable(modelId)) {
            return img -> service.matte(img, modelId);
        }
        if (settings.mode == ExtractionMode.AI) {
            String reason =
                    service == null
                            ? "no matting engine in this build"
                            : !service.isKnownModel(modelId)
                                    ? "unknown model '" + modelId + "'"
                                    : service.isModelInstalling(modelId)
                                            ? "model '"
                                                    + modelId
                                                    + "' is still downloading, retry shortly"
                                            : "model '" + modelId + "' is not installed";
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "AI extraction unavailable: " + reason);
        }
        log.info("AI matting skipped: {}", service == null ? "engine absent" : "model not ready");
        return null;
    }
}
