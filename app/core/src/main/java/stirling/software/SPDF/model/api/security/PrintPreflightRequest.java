package stirling.software.SPDF.model.api.security;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

import stirling.software.common.model.api.PDFFile;

@Data
@EqualsAndHashCode(callSuper = true)
public class PrintPreflightRequest extends PDFFile {

    @Schema(
            description = "Bleed width in millimetres required on every side beyond the TrimBox",
            minimum = "0",
            defaultValue = "3")
    private float requiredBleedMm = 3;

    @Schema(
            description =
                    "Images rendered below this effective resolution are reported as low"
                            + " resolution",
            minimum = "1",
            defaultValue = "150")
    private int minImageDpi = 150;

    @Schema(
            description =
                    "Strokes thinner than this width in points are reported as hairlines at risk of"
                            + " disappearing in print",
            minimum = "0",
            defaultValue = "0.25")
    private float hairlineThresholdPt = 0.25f;

    @Schema(
            description =
                    "Render each page and check the bleed band between TrimBox and BleedBox is"
                            + " actually painted, so trimming cannot reveal white",
            type = "boolean",
            defaultValue = "true")
    private boolean checkBleedCoverage = true;
}
