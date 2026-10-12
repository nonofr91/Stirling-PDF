package stirling.software.SPDF.model.api.general;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

import stirling.software.common.model.api.PDFFile;

@Data
@EqualsAndHashCode(callSuper = true)
public class EyeletMarksRequest extends PDFFile {

    @Schema(
            description =
                    "How marks are distributed along each side. COUNT places the side's count"
                            + " of marks equidistantly including both ends; MAX_SPACING computes"
                            + " the count from the largest allowed gap between marks",
            allowableValues = {"COUNT", "MAX_SPACING"},
            defaultValue = "MAX_SPACING")
    private String mode = "MAX_SPACING";

    @Schema(
            description =
                    "Eyelet (grommet) diameter in millimetres; the cross mark's span matches it",
            minimum = "0",
            exclusiveMinimum = true,
            defaultValue = "10")
    private float eyeletDiameterMm = 10f;

    @Schema(
            description = "Cross stroke width in points",
            minimum = "0",
            exclusiveMinimum = true,
            defaultValue = "0.5")
    private float strokeWidthPt = 0.5f;

    @Schema(
            description =
                    "Signed distance in millimetres from the top trim edge: positive draws the"
                            + " marks inside the trim (on the product), negative outside into the"
                            + " slug",
            defaultValue = "15")
    private float offsetTopMm = 15f;

    @Schema(
            description =
                    "Signed distance in millimetres from the right trim edge (see"
                            + " offsetTopMm)",
            defaultValue = "15")
    private float offsetRightMm = 15f;

    @Schema(
            description =
                    "Signed distance in millimetres from the bottom trim edge (see"
                            + " offsetTopMm)",
            defaultValue = "15")
    private float offsetBottomMm = 15f;

    @Schema(
            description =
                    "Signed distance in millimetres from the left trim edge (see" + " offsetTopMm)",
            defaultValue = "15")
    private float offsetLeftMm = 15f;

    @Schema(
            description = "COUNT mode: marks on the top side, ends included; 0 disables the side",
            minimum = "0",
            defaultValue = "0")
    private int countTop = 0;

    @Schema(
            description = "COUNT mode: marks on the right side (see countTop)",
            minimum = "0",
            defaultValue = "0")
    private int countRight = 0;

    @Schema(
            description = "COUNT mode: marks on the bottom side (see countTop)",
            minimum = "0",
            defaultValue = "0")
    private int countBottom = 0;

    @Schema(
            description = "COUNT mode: marks on the left side (see countTop)",
            minimum = "0",
            defaultValue = "0")
    private int countLeft = 0;

    @Schema(
            description =
                    "MAX_SPACING mode: largest allowed gap between marks on the top side in"
                            + " millimetres; a value <= 0 disables the side",
            defaultValue = "500")
    private float maxSpacingTopMm = 500f;

    @Schema(
            description = "MAX_SPACING mode: largest gap on the right side (see maxSpacingTopMm)",
            defaultValue = "500")
    private float maxSpacingRightMm = 500f;

    @Schema(
            description =
                    "MAX_SPACING mode: largest gap on the bottom side (see" + " maxSpacingTopMm)",
            defaultValue = "500")
    private float maxSpacingBottomMm = 500f;

    @Schema(
            description = "MAX_SPACING mode: largest gap on the left side (see maxSpacingTopMm)",
            defaultValue = "500")
    private float maxSpacingLeftMm = 500f;

    @Schema(
            description =
                    "Optional-content (layer) name the marks are grouped under; blank draws"
                            + " them as plain page content",
            defaultValue = "")
    private String layerName = "";
}
