package stirling.software.SPDF.controller.api;

import java.io.IOException;
import java.util.Map;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.general.SetPageBoxesRequest;
import stirling.software.SPDF.service.prepress.PrepressArchiveService;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.GeneralApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.CropMarkDetector;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.PageBleedGenerator;
import stirling.software.common.util.PageBleedGenerator.BleedEdges;
import stirling.software.common.util.PageBleedGenerator.BleedMethod;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@GeneralApi
@RequiredArgsConstructor
@Slf4j
public class SetPageBoxesController {

    private static final float MM_TO_POINTS = 72f / 25.4f;

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;
    private final PrepressArchiveService prepressArchive;

    @AutoJobPostMapping(
            value = "/set-page-boxes",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Set PDF page boxes",
            description =
                    "Sets MediaBox, CropBox, TrimBox, BleedBox and/or ArtBox on every page of the"
                            + " input PDF, either from explicit rectangles or from prepress"
                            + " shortcuts (bleed around trim, trim inset from media). Can also"
                            + " generate real bleed content between the TrimBox and BleedBox"
                            + " (mirrored or repeated edge content, like PitStop's Add Bleed) and"
                            + " draw crop marks.")
    public ResponseEntity<Resource> setPageBoxes(@ModelAttribute SetPageBoxesRequest request)
            throws IOException {
        if (!hasWork(request)) {
            throw new IllegalArgumentException(
                    "At least one page box, bleedMm, trimMarginMm, copyMissingFromMediaBox,"
                            + " generateBleed or addCropMarks must be provided");
        }
        validateRequest(request);
        BleedMethod method =
                request.isGenerateBleed() ? BleedMethod.parse(request.getBleedMethod()) : null;

        try (PDDocument document = pdfDocumentFactory.load(request)) {
            int pageIndex = 0;
            for (PDPage page : document.getPages()) {
                applyBoxes(document, page, pageIndex++, request, method);
            }

            String filename =
                    GeneralUtils.generateFilename(
                            request.getFileInput().getOriginalFilename(), "_boxes.pdf");
            TempFile out = tempFileManager.createManagedTempFile(".pdf");
            try {
                document.save(out.getFile());
            } catch (IOException | RuntimeException e) {
                out.close();
                throw e;
            }
            var handle =
                    prepressArchive.recordVersion(
                            "set-page-boxes",
                            request.getFileInput(),
                            out.getPath(),
                            filename,
                            Map.of(
                                    "generateBleed", request.isGenerateBleed(),
                                    "addCropMarks", request.isAddCropMarks()));
            ResponseEntity<Resource> response =
                    WebResponseUtils.pdfFileToWebResponse(out, filename);
            PrepressArchiveService.setChainHeaders(response, handle);
            return response;
        }
    }

    private static boolean hasWork(SetPageBoxesRequest request) {
        return notBlank(request.getMediaBox())
                || notBlank(request.getCropBox())
                || notBlank(request.getTrimBox())
                || notBlank(request.getBleedBox())
                || notBlank(request.getArtBox())
                || request.getBleedMm() > 0
                || request.getTrimMarginMm() > 0
                || request.isDeriveFromCropMarks()
                || request.isCopyMissingFromMediaBox()
                || request.isGenerateBleed()
                || request.isAddCropMarks();
    }

    private static void validateRequest(SetPageBoxesRequest request) {
        requireFinite("bleedMm", request.getBleedMm());
        requireFinite("trimMarginMm", request.getTrimMarginMm());
        requireFinite("bleedTopMm", request.getBleedTopMm());
        requireFinite("bleedRightMm", request.getBleedRightMm());
        requireFinite("bleedBottomMm", request.getBleedBottomMm());
        requireFinite("bleedLeftMm", request.getBleedLeftMm());
        requireFinite("bleedInsetMm", request.getBleedInsetMm());
        if (request.getBleedInsetMm() < 0) {
            throw new IllegalArgumentException("bleedInsetMm must be >= 0");
        }
        if (request.isGenerateBleed()) {
            if (request.getBleedDpi() < 72 || request.getBleedDpi() > 600) {
                throw new IllegalArgumentException(
                        "bleedDpi must be between 72 and 600, got: " + request.getBleedDpi());
            }
            boolean anyBleed =
                    request.getBleedMm() > 0
                            || request.getBleedTopMm() > 0
                            || request.getBleedRightMm() > 0
                            || request.getBleedBottomMm() > 0
                            || request.getBleedLeftMm() > 0;
            if (!anyBleed && !notBlank(request.getBleedBox())) {
                throw new IllegalArgumentException(
                        "generateBleed requires bleedMm > 0, a positive per-side bleed value, or"
                                + " a bleedBox larger than the TrimBox");
            }
        }
        if (request.isAddCropMarks()) {
            requireFinite("cropMarkLengthMm", request.getCropMarkLengthMm());
            requireFinite("cropMarkOffsetMm", request.getCropMarkOffsetMm());
            requireFinite("cropMarkWeightPt", request.getCropMarkWeightPt());
            if (request.getCropMarkLengthMm() <= 0) {
                throw new IllegalArgumentException("cropMarkLengthMm must be > 0");
            }
            if (request.getCropMarkOffsetMm() < 0) {
                throw new IllegalArgumentException("cropMarkOffsetMm must be >= 0");
            }
            if (request.getCropMarkWeightPt() <= 0) {
                throw new IllegalArgumentException("cropMarkWeightPt must be > 0");
            }
        }
    }

    private static void requireFinite(String name, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got: " + value);
        }
    }

    private static void applyBoxes(
            PDDocument document,
            PDPage page,
            int pageIndex,
            SetPageBoxesRequest request,
            BleedMethod method)
            throws IOException {
        PDRectangle media = parseRect(request.getMediaBox(), "mediaBox");
        PDRectangle effectiveMedia = media != null ? media : page.getMediaBox();

        PDRectangle trim = parseRect(request.getTrimBox(), "trimBox");
        boolean trimDetected = false;
        if (trim == null && request.getTrimMarginMm() > 0) {
            trim = inset(effectiveMedia, request.getTrimMarginMm(), "trimMarginMm");
        }
        if (trim == null && request.isDeriveFromCropMarks()) {
            trim = CropMarkDetector.detect(page);
            if (trim == null) {
                throw new IllegalArgumentException(
                        "No unambiguous crop marks detected on page " + (pageIndex + 1));
            }
            trimDetected = true;
        }
        PDRectangle workTrim = trim != null ? trim : page.getTrimBox();

        PDRectangle bleed = parseRect(request.getBleedBox(), "bleedBox");
        PDRectangle crop = parseRect(request.getCropBox(), "cropBox");
        PDRectangle art = parseRect(request.getArtBox(), "artBox");

        boolean generate = request.isGenerateBleed();
        boolean marks = request.isAddCropMarks();
        float markOffsetPt = request.getCropMarkOffsetMm() * MM_TO_POINTS;
        float markLengthPt = request.getCropMarkLengthMm() * MM_TO_POINTS;

        // Content is generated before any box is applied: raster methods render the original
        // CropBox, and form import takes the original CropBox as its bounding box.
        PDRectangle required = null;
        if (generate) {
            BleedEdges edges = resolveBleedEdges(request, workTrim, bleed);
            if (!edges.any()) {
                throw new IllegalArgumentException(
                        "generateBleed resolved no bleed on page "
                                + (pageIndex + 1)
                                + " (TrimBox already covers the requested BleedBox?)");
            }
            float insetPt = request.getBleedInsetMm() * MM_TO_POINTS;
            if (insetPt * 2 >= Math.min(workTrim.getWidth(), workTrim.getHeight())) {
                throw new IllegalArgumentException(
                        "bleedInsetMm of "
                                + request.getBleedInsetMm()
                                + "mm leaves no content to mirror inside the TrimBox");
            }
            PageBleedGenerator.generateBleed(
                    document,
                    page,
                    pageIndex,
                    workTrim,
                    edges,
                    method,
                    request.isBleedCorners(),
                    request.getBleedDpi(),
                    insetPt);
            required = edges.unionWith(workTrim, marks, markOffsetPt, markLengthPt);
            PDRectangle generated = edges.unionWith(workTrim, false, 0, 0);
            bleed = bleed != null ? union(bleed, generated) : generated;
        }
        if (marks) {
            PageBleedGenerator.drawCropMarks(
                    document,
                    page,
                    workTrim,
                    markOffsetPt,
                    markLengthPt,
                    request.getCropMarkWeightPt());
            if (required == null) {
                required =
                        new BleedEdges(0, 0, 0, 0)
                                .unionWith(workTrim, true, markOffsetPt, markLengthPt);
            }
        }
        if (bleed == null && request.getBleedMm() > 0 && !generate) {
            // Explicit margin without generation: grow only the box, no content is painted.
            bleed = expand(workTrim, request.getBleedMm());
        }
        // The generated area must become visible, and a BleedBox outside the MediaBox is dead
        // geometry (viewers and printers clip to the page): grow the MediaBox and the visible
        // CropBox to cover whichever region reaches furthest.
        PDRectangle growTo = required;
        if (bleed != null) {
            growTo = growTo == null ? bleed : union(growTo, bleed);
        }
        if (trimDetected) {
            // A trim read off the marks can legitimately extend past the current page edges
            // (marks sit on a larger printed sheet); clip-bound boxes must grow to cover it.
            growTo = growTo == null ? trim : union(growTo, trim);
        }
        if (growTo != null) {
            page.setMediaBox(union(effectiveMedia, growTo));
            PDRectangle effectiveCrop = crop != null ? crop : page.getCropBox();
            page.setCropBox(union(effectiveCrop, growTo));
            if (trim == null && required != null) {
                // The bleed was painted relative to this box; materialize it so viewers and
                // downstream tools see the same geometry the generator used.
                page.setTrimBox(workTrim);
            }
        } else if (media != null) {
            page.setMediaBox(media);
        }
        if (trim != null) {
            page.setTrimBox(trim);
        }
        if (bleed != null) {
            page.setBleedBox(bleed);
        }
        if (crop != null && growTo == null) {
            page.setCropBox(crop);
        }
        if (art != null) {
            page.setArtBox(art);
        }

        if (request.isCopyMissingFromMediaBox()) {
            // All PDPage box getters fall back to CropBox or MediaBox when the entry is
            // absent, so presence is tested on the page dictionary.
            COSDictionary dict = page.getCOSObject();
            PDRectangle appliedMedia = page.getMediaBox();
            if (dict.getItem(COSName.CROP_BOX) == null) {
                page.setCropBox(appliedMedia);
            }
            if (dict.getItem(COSName.TRIM_BOX) == null) {
                page.setTrimBox(appliedMedia);
            }
            if (dict.getItem(COSName.BLEED_BOX) == null) {
                page.setBleedBox(appliedMedia);
            }
            if (dict.getItem(COSName.ART_BOX) == null) {
                page.setArtBox(appliedMedia);
            }
        }
    }

    /**
     * Per-side bleed in points: each explicit side wins, otherwise bleedMm, otherwise the gap
     * between an explicit bleedBox and the trim.
     */
    private static BleedEdges resolveBleedEdges(
            SetPageBoxesRequest request, PDRectangle trim, PDRectangle bleedBox) {
        float left = resolveSide(request.getBleedLeftMm(), request.getBleedMm());
        float right = resolveSide(request.getBleedRightMm(), request.getBleedMm());
        float bottom = resolveSide(request.getBleedBottomMm(), request.getBleedMm());
        float top = resolveSide(request.getBleedTopMm(), request.getBleedMm());
        if (bleedBox != null) {
            left =
                    Math.max(
                            left,
                            Math.max(0, trim.getLowerLeftX() - bleedBox.getLowerLeftX())
                                    / MM_TO_POINTS);
            right =
                    Math.max(
                            right,
                            Math.max(0, bleedBox.getUpperRightX() - trim.getUpperRightX())
                                    / MM_TO_POINTS);
            bottom =
                    Math.max(
                            bottom,
                            Math.max(0, trim.getLowerLeftY() - bleedBox.getLowerLeftY())
                                    / MM_TO_POINTS);
            top =
                    Math.max(
                            top,
                            Math.max(0, bleedBox.getUpperRightY() - trim.getUpperRightY())
                                    / MM_TO_POINTS);
        }
        return new BleedEdges(
                left * MM_TO_POINTS,
                right * MM_TO_POINTS,
                bottom * MM_TO_POINTS,
                top * MM_TO_POINTS);
    }

    private static float resolveSide(float sideMm, float bleedMm) {
        // Negative per-side values mean "not set": fall back to the uniform bleedMm.
        return Math.max(0, sideMm >= 0 ? sideMm : bleedMm);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static PDRectangle parseRect(String value, String name) {
        if (!notBlank(value)) {
            return null;
        }
        String[] parts = value.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    name + " must be \"x,y,width,height\" in points, got: " + value);
        }
        try {
            float x = Float.parseFloat(parts[0].trim());
            float y = Float.parseFloat(parts[1].trim());
            float width = Float.parseFloat(parts[2].trim());
            float height = Float.parseFloat(parts[3].trim());
            // NaN and Infinity parse fine but produce invalid box entries; negative
            // origins stay allowed because BleedBox may extend outside the MediaBox.
            if (!Float.isFinite(x)
                    || !Float.isFinite(y)
                    || !Float.isFinite(width)
                    || !Float.isFinite(height)) {
                throw new IllegalArgumentException(
                        name + " must contain finite numbers, got: " + value);
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                        name + " width and height must be positive, got: " + value);
            }
            return new PDRectangle(x, y, width, height);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    name + " must be \"x,y,width,height\" in points, got: " + value, e);
        }
    }

    private static PDRectangle inset(PDRectangle rect, float marginMm, String name) {
        float m = marginMm * MM_TO_POINTS;
        float width = rect.getWidth() - 2 * m;
        float height = rect.getHeight() - 2 * m;
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    name + " of " + marginMm + "mm leaves no area inside the MediaBox");
        }
        return new PDRectangle(rect.getLowerLeftX() + m, rect.getLowerLeftY() + m, width, height);
    }

    private static PDRectangle expand(PDRectangle rect, float marginMm) {
        float m = marginMm * MM_TO_POINTS;
        return new PDRectangle(
                rect.getLowerLeftX() - m,
                rect.getLowerLeftY() - m,
                rect.getWidth() + 2 * m,
                rect.getHeight() + 2 * m);
    }

    private static PDRectangle union(PDRectangle a, PDRectangle b) {
        float llx = Math.min(a.getLowerLeftX(), b.getLowerLeftX());
        float lly = Math.min(a.getLowerLeftY(), b.getLowerLeftY());
        return new PDRectangle(
                llx,
                lly,
                Math.max(a.getUpperRightX(), b.getUpperRightX()) - llx,
                Math.max(a.getUpperRightY(), b.getUpperRightY()) - lly);
    }
}
