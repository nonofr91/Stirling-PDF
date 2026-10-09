package stirling.software.SPDF.model.api.general;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

import stirling.software.common.model.api.PDFFile;

@Data
@EqualsAndHashCode(callSuper = true)
public class SetPageBoxesRequest extends PDFFile {

    @Schema(
            description = "MediaBox as \"x,y,width,height\" in points, applied to every page",
            example = "0,0,595.28,841.89")
    private String mediaBox;

    @Schema(
            description = "CropBox as \"x,y,width,height\" in points, applied to every page",
            example = "0,0,595.28,841.89")
    private String cropBox;

    @Schema(
            description = "TrimBox as \"x,y,width,height\" in points, applied to every page",
            example = "20,20,555.28,801.89")
    private String trimBox;

    @Schema(
            description = "BleedBox as \"x,y,width,height\" in points, applied to every page",
            example = "14.17,14.17,567.11,813.71")
    private String bleedBox;

    @Schema(
            description = "ArtBox as \"x,y,width,height\" in points, applied to every page",
            example = "20,20,555.28,801.89")
    private String artBox;

    @Schema(
            description =
                    "BleedBox expanded by this many millimetres around the resolved TrimBox on"
                            + " every page. Ignored when bleedBox is set",
            minimum = "0",
            defaultValue = "0")
    private float bleedMm = 0;

    @Schema(
            description =
                    "TrimBox set to the MediaBox shrunk by this margin in millimetres on every"
                            + " side. Ignored when trimBox is set",
            minimum = "0",
            defaultValue = "0")
    private float trimMarginMm = 0;

    @Schema(
            description =
                    "Derive the TrimBox from crop marks painted on the page (like pdfToolbox's"
                            + " derive geometry fixup). Used only when no explicit trimBox or"
                            + " trimMarginMm resolves a trim; fails the page when the mark layout"
                            + " is absent or ambiguous",
            type = "boolean",
            defaultValue = "false")
    private boolean deriveFromCropMarks = false;

    @Schema(
            description =
                    "Copy the MediaBox into any of CropBox/TrimBox/BleedBox/ArtBox still unset"
                            + " after the other parameters are applied",
            type = "boolean",
            defaultValue = "false")
    private boolean copyMissingFromMediaBox = false;

    @Schema(
            description =
                    "Paint real bleed content between TrimBox and BleedBox on every page (mirrored"
                            + " or repeated edge content), so trimming leaves no white edge."
                            + " Requires a positive bleedMm or per-side amount, or an explicit"
                            + " bleedBox larger than the trim",
            type = "boolean",
            defaultValue = "false")
    private boolean generateBleed = false;

    @Schema(
            description =
                    "How bleed content is generated. MIRROR reflects the page's vector content"
                            + " across the trim edge (lossless). MIRROR_IMAGE mirrors a rendered"
                            + " strip (robust on shadings/transparency). PIXEL_REPEAT stretches the"
                            + " last edge pixel (safer when text touches the trim edge). UPSCALE"
                            + " enlarges the page content until it covers the BleedBox (final"
                            + " printed size shrinks slightly)",
            allowableValues = {"MIRROR", "MIRROR_IMAGE", "PIXEL_REPEAT", "UPSCALE"},
            defaultValue = "MIRROR")
    private String bleedMethod = "MIRROR";

    @Schema(
            description =
                    "Bleed width in millimetres on the top edge. Negative falls back to bleedMm",
            defaultValue = "-1")
    private float bleedTopMm = -1;

    @Schema(
            description =
                    "Bleed width in millimetres on the right edge. Negative falls back to bleedMm",
            defaultValue = "-1")
    private float bleedRightMm = -1;

    @Schema(
            description =
                    "Bleed width in millimetres on the bottom edge. Negative falls back to bleedMm",
            defaultValue = "-1")
    private float bleedBottomMm = -1;

    @Schema(
            description =
                    "Bleed width in millimetres on the left edge. Negative falls back to bleedMm",
            defaultValue = "-1")
    private float bleedLeftMm = -1;

    @Schema(
            description = "Generate bleed in the corners in addition to the edges",
            type = "boolean",
            defaultValue = "true")
    private boolean bleedCorners = true;

    @Schema(
            description = "Render resolution used by MIRROR_IMAGE and PIXEL_REPEAT",
            minimum = "72",
            maximum = "600",
            defaultValue = "300")
    private int bleedDpi = 300;

    @Schema(
            description =
                    "Skip this many millimetres of content inside the trim edge before mirroring,"
                            + " to jump over an inner white margin",
            minimum = "0",
            defaultValue = "0")
    private float bleedInsetMm = 0;

    @Schema(
            description =
                    "Draw crop marks at the TrimBox corners, in the slug area beyond the bleed",
            type = "boolean",
            defaultValue = "false")
    private boolean addCropMarks = false;

    @Schema(description = "Crop mark length in millimetres", minimum = "0", defaultValue = "5")
    private float cropMarkLengthMm = 5;

    @Schema(
            description =
                    "Gap in millimetres between the trim edge and where each crop mark starts",
            minimum = "0",
            defaultValue = "3")
    private float cropMarkOffsetMm = 3;

    @Schema(description = "Crop mark stroke width in points", minimum = "0", defaultValue = "0.25")
    private float cropMarkWeightPt = 0.25f;
}
