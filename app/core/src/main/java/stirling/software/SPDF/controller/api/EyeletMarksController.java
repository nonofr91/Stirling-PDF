package stirling.software.SPDF.controller.api;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.general.EyeletMarksRequest;
import stirling.software.SPDF.service.prepress.PrepressArchiveService;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.GeneralApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.EyeletMarkPlacer;
import stirling.software.common.util.EyeletMarkPlacer.Mode;
import stirling.software.common.util.EyeletMarkPlacer.Side;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@GeneralApi
@RequiredArgsConstructor
@Slf4j
public class EyeletMarksController {

    private static final float MAX_OFFSET_MM = 200f;
    private static final float MAX_DIAMETER_MM = 100f;
    private static final float MAX_SPACING_MM = 20000f;
    private static final int MAX_COUNT = 500;

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;
    private final PrepressArchiveService prepressArchive;

    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Add eyelet position marks",
            description =
                    "Draws a cross at each eyelet (grommet) position along the trim edges, for"
                            + " banner finishing. Each side takes either a fixed mark count"
                            + " (equidistant, ends included) or a maximum spacing that yields"
                            + " the count, a signed offset from the TrimBox (positive inside,"
                            + " negative into the slug), and every enabled corner carries one"
                            + " mark. The cross span matches the eyelet diameter; marks are"
                            + " stroked in pure black on an optional layer.")
    @AutoJobPostMapping(
            value = "/eyelet-marks",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    public ResponseEntity<Resource> eyeletMarks(@ModelAttribute EyeletMarksRequest request)
            throws IOException {
        EyeletMarkPlacer.Settings settings = toSettings(validate(request));

        int total = 0;
        try (PDDocument document = pdfDocumentFactory.load(request)) {
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                PDPage page = document.getPage(i);
                try {
                    total += EyeletMarkPlacer.apply(document, page, settings);
                } catch (IllegalArgumentException e) {
                    throw ExceptionUtils.createIllegalArgumentException(
                            "error.invalidArgument", "Page " + (i + 1) + ": " + e.getMessage());
                }
            }
            String filename =
                    GeneralUtils.generateFilename(
                            request.getFileInput().getOriginalFilename(), "_eyelets.pdf");
            TempFile out = tempFileManager.createManagedTempFile(".pdf");
            try {
                document.save(out.getFile());
            } catch (IOException | RuntimeException e) {
                out.close();
                throw e;
            }
            var handle =
                    prepressArchive.recordVersion(
                            "eyelet-marks",
                            request.getFileInput(),
                            out.getPath(),
                            filename,
                            Map.of("marks", total));
            ResponseEntity<Resource> response =
                    WebResponseUtils.pdfFileToWebResponse(out, filename);
            PrepressArchiveService.setChainHeaders(response, handle);
            return response;
        }
    }

    private static EyeletMarksRequest validate(EyeletMarksRequest request) {
        MultipartFile file = request.getFileInput();
        if (file == null || file.isEmpty()) {
            throw ExceptionUtils.createRuntimeException(
                    "error.pdfRequired", "PDF file is required", null);
        }
        Mode mode = parseMode(request.getMode());
        checkOffset("offsetTopMm", request.getOffsetTopMm());
        checkOffset("offsetRightMm", request.getOffsetRightMm());
        checkOffset("offsetBottomMm", request.getOffsetBottomMm());
        checkOffset("offsetLeftMm", request.getOffsetLeftMm());
        if (!Float.isFinite(request.getEyeletDiameterMm())
                || request.getEyeletDiameterMm() <= 0
                || request.getEyeletDiameterMm() > MAX_DIAMETER_MM) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "eyeletDiameterMm must be finite within 0.." + (int) MAX_DIAMETER_MM + " mm");
        }
        if (!Float.isFinite(request.getStrokeWidthPt()) || request.getStrokeWidthPt() <= 0) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "strokeWidthPt must be a positive finite number");
        }
        boolean any = false;
        if (mode == Mode.COUNT) {
            any =
                    checkCount("countTop", request.getCountTop())
                            | checkCount("countRight", request.getCountRight())
                            | checkCount("countBottom", request.getCountBottom())
                            | checkCount("countLeft", request.getCountLeft());
        } else {
            any =
                    checkSpacing("maxSpacingTopMm", request.getMaxSpacingTopMm())
                            | checkSpacing("maxSpacingRightMm", request.getMaxSpacingRightMm())
                            | checkSpacing("maxSpacingBottomMm", request.getMaxSpacingBottomMm())
                            | checkSpacing("maxSpacingLeftMm", request.getMaxSpacingLeftMm());
        }
        if (!any) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    "at least one side must be enabled (a positive count or maxSpacing)");
        }
        return request;
    }

    private static Mode parseMode(String mode) {
        try {
            return Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", "mode must be COUNT or MAX_SPACING");
        }
    }

    private static void checkOffset(String name, float mm) {
        if (!Float.isFinite(mm) || Math.abs(mm) > MAX_OFFSET_MM) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    name + " must be finite within ±" + (int) MAX_OFFSET_MM + " mm");
        }
    }

    private static boolean checkCount(String name, int count) {
        if (count < 0 || count > MAX_COUNT) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument", name + " must be within 0.." + MAX_COUNT);
        }
        if (count == 1) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    name + " = 1 cannot honour the mandatory corner marks");
        }
        return count > 0;
    }

    private static boolean checkSpacing(String name, float mm) {
        if (!Float.isFinite(mm) || mm > MAX_SPACING_MM) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalidArgument",
                    name + " must be finite within 0.." + (int) MAX_SPACING_MM + " mm");
        }
        return mm > 0;
    }

    private static EyeletMarkPlacer.Settings toSettings(EyeletMarksRequest request) {
        EyeletMarkPlacer.Settings s = new EyeletMarkPlacer.Settings();
        s.mode = parseMode(request.getMode());
        s.eyeletDiameterMm = request.getEyeletDiameterMm();
        s.strokeWidthPt = request.getStrokeWidthPt();
        s.layerName = request.getLayerName();
        side(s, Side.TOP).offsetMm = request.getOffsetTopMm();
        side(s, Side.RIGHT).offsetMm = request.getOffsetRightMm();
        side(s, Side.BOTTOM).offsetMm = request.getOffsetBottomMm();
        side(s, Side.LEFT).offsetMm = request.getOffsetLeftMm();
        side(s, Side.TOP).count = request.getCountTop();
        side(s, Side.RIGHT).count = request.getCountRight();
        side(s, Side.BOTTOM).count = request.getCountBottom();
        side(s, Side.LEFT).count = request.getCountLeft();
        side(s, Side.TOP).maxSpacingMm = request.getMaxSpacingTopMm();
        side(s, Side.RIGHT).maxSpacingMm = request.getMaxSpacingRightMm();
        side(s, Side.BOTTOM).maxSpacingMm = request.getMaxSpacingBottomMm();
        side(s, Side.LEFT).maxSpacingMm = request.getMaxSpacingLeftMm();
        return s;
    }

    private static EyeletMarkPlacer.SideSettings side(EyeletMarkPlacer.Settings s, Side side) {
        return s.sides.get(side);
    }
}
