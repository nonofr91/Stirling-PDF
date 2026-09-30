package stirling.software.SPDF.model.api.general;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

import stirling.software.common.model.api.PDFFile;

@Data
@EqualsAndHashCode(callSuper = true)
public class CutContourRequest extends PDFFile {

    @Schema(
            description =
                    "How the subject silhouette is extracted. ALPHA uses existing transparency,"
                            + " BACKGROUND flood-fills a uniform background from the page edges, AI"
                            + " runs subject matting (ONNX model required), AUTO tries the sources in"
                            + " autoOrder",
            allowableValues = {"ALPHA", "BACKGROUND", "AI", "AUTO"},
            defaultValue = "AUTO")
    private String extractionMode = "AUTO";

    @Schema(
            description =
                    "Comma-separated sources AUTO tries in order (subset of ALPHA,BACKGROUND,AI)",
            defaultValue = "ALPHA,BACKGROUND,AI")
    private String autoOrder = "ALPHA,BACKGROUND,AI";

    @Schema(
            description =
                    "Mask render resolution in dpi; large pages are clamped to a memory budget",
            minimum = "72",
            maximum = "600",
            defaultValue = "150")
    private int dpi = 150;

    @Schema(
            description = "Alpha threshold 0-255; pixels more transparent than this are background",
            minimum = "0",
            maximum = "255",
            defaultValue = "16")
    private int alphaThreshold = 16;

    @Schema(
            description =
                    "BACKGROUND mode: per-channel RGB distance from the page-edge colour that still"
                            + " counts as background",
            minimum = "0",
            maximum = "255",
            defaultValue = "24")
    private int backgroundTolerance = 24;

    @Schema(
            description =
                    "Artwork elements separated by less than this gap (mm) merge under a single"
                            + " outer cut contour; 0 keeps every piece separate",
            type = "number",
            minimum = "0",
            defaultValue = "8")
    private float mergeGapMm = 8f;

    @Schema(
            description =
                    "Optional rough perimeter drawn by the user, as flat x,y pairs in page"
                            + " fractions (top-left origin), e.g. \"0.1,0.2,0.9,0.2,0.9,0.9,0.1,0.9\"."
                            + " The ring inside the polygon provides the background reference and"
                            + " the cut stays bounded by it",
            type = "string",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String roi;

    @Schema(
            description = "1-based page the roi applies to; 0 or unset applies it to every page",
            type = "integer",
            minimum = "0",
            defaultValue = "0")
    private int roiPage = 0;

    @Schema(
            description = "Connected components smaller than this area (mm²) are dropped as noise",
            minimum = "0",
            defaultValue = "1")
    private float minAreaMm2 = 1f;

    @Schema(
            description =
                    "0..100: higher values simplify harder and apply more smoothing passes to the"
                            + " traced contour",
            minimum = "0",
            maximum = "100",
            defaultValue = "20")
    private float smoothness = 20f;

    @Schema(
            description =
                    "Distance the cut path is moved outward from the silhouette in millimetres"
                            + " (negative moves it inside)",
            defaultValue = "0")
    private float offsetMm = 0f;

    @Schema(
            description = "Keep fully enclosed holes (the counter of an 'o') as inner cut contours",
            type = "boolean",
            defaultValue = "true")
    private boolean keepHoles = false;

    @Schema(
            description = "Confidence threshold applied to AI masks, 0..1",
            minimum = "0",
            maximum = "1",
            defaultValue = "0.4")
    private float aiThreshold = 0.4f;

    @Schema(
            description = "Matting model id for AI mode; blank selects the catalog default (u2net)",
            defaultValue = "")
    private String aiModelId = "";

    @Schema(
            description =
                    "Spot colour name the RIP keys on. Case-sensitive; keep 'CutContour' unless"
                            + " the shop specifies a different colourant",
            defaultValue = "CutContour")
    private String spotName = "CutContour";

    @Schema(description = "Cut stroke width in points", minimum = "0", defaultValue = "0.25")
    private float strokeWidthPt = 0.25f;

    @Schema(
            description =
                    "Replace page content with the artwork clipped to the cut path. When false"
                            + " (default) the original PDF content stays untouched and only the"
                            + " CutContour layer is added",
            type = "boolean",
            defaultValue = "false")
    private boolean clipArtwork = false;

    @Schema(
            description =
                    "Millimetres of bleed painted beyond the cut line by repeating edge pixels"
                            + " (irregular-contour bleed); 0 disables",
            minimum = "0",
            maximum = "50",
            defaultValue = "0")
    private float bleedMm = 0f;

    @Schema(
            description =
                    "Set TrimBox to the contour bounding box (BleedBox follows the bleed when"
                            + " bleedMm is positive)",
            type = "boolean",
            defaultValue = "false")
    private boolean trimToContour = false;

    @Schema(
            description =
                    "Tag the cut layer with ISO 19593-1 processing-step metadata"
                            + " (Structural/Cutting) and suppress it in print output",
            type = "boolean",
            defaultValue = "true")
    private boolean processingSteps = true;

    @Schema(
            description = "Optional-content layer name; blank defaults to the spot name",
            defaultValue = "")
    private String layerName = "";
}
