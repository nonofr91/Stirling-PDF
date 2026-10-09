package stirling.software.SPDF.model.api.security;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

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

    @Schema(
            description =
                    "Text rendered smaller than this size in points is reported as too small to"
                            + " print reliably",
            minimum = "0",
            defaultValue = "5")
    private float minFontSizePt = 5f;

    @Schema(
            description =
                    "Content inside the trim but closer than this distance in millimetres to the"
                            + " trim edge is reported as at risk of being cut off",
            minimum = "0",
            defaultValue = "3")
    private float safetyMarginMm = 3;

    @Schema(
            description =
                    "Painted colours whose total ink coverage exceeds this percentage are reported"
                            + " — drying and registration problems above ~320% in offset",
            minimum = "0",
            defaultValue = "320")
    private int maxInkCoveragePercent = 320;

    @Schema(
            description =
                    "Measure total ink coverage from a Ghostscript-rendered CMYK raster instead of"
                            + " painted fills — sees real stacking and knockouts but adds render"
                            + " time (requires the Ghostscript endpoint group)",
            type = "boolean",
            defaultValue = "false")
    private boolean renderedInkCoverage = false;

    @Schema(
            description =
                    "1-bit (bitmap) images rendered below this effective resolution are reported —"
                            + " line art needs far more resolution than continuous tone",
            minimum = "1",
            defaultValue = "1200")
    private int minImage1BitDpi = 1200;

    @Schema(
            description =
                    "Images rendered above this effective resolution are reported as oversampled —"
                            + " heavier than print can use",
            minimum = "1",
            defaultValue = "600")
    private int maxImageDpi = 600;

    @Schema(
            description =
                    "More spot separations than this are reported — each plate costs makeready;"
                            + " 0 disables the limit",
            minimum = "0",
            defaultValue = "0")
    private int maxSpotCount = 0;

    @Schema(
            description =
                    "Prepend summary pages (verdict, document facts, fonts, colours, findings) to"
                            + " the annotated PDF",
            type = "boolean",
            defaultValue = "true")
    private boolean includeSummaryPage = true;

    @Schema(
            description =
                    "Finding codes to skip (e.g. SAFETY_MARGIN, INK_COVERAGE_HIGH); empty runs"
                            + " every check")
    private List<String> disabledChecks;

    @Schema(
            description =
                    "Fixup codes to apply on the print-preflight-fix endpoint (e.g. EXTEND_BLEED,"
                            + " FLATTEN_FORM, REMOVE_JAVASCRIPT); empty or absent applies every"
                            + " supported fixup that has something to correct, the sentinel NONE"
                            + " applies none")
    private List<String> fixups;

    @Schema(
            description =
                    "ICC profile attached as output intent by the SET_OUTPUT_INTENT fixup; when"
                            + " absent the bundled sRGB2014 profile is used",
            type = "string",
            format = "binary")
    private MultipartFile iccProfile;

    @Schema(
            description =
                    "BCP-47 tag for the language of generated report text and finding messages"
                            + " (e.g. fr-FR); falls back to the session locale, then English",
            example = "fr-FR")
    private String reportLanguage;

    @Schema(
            description =
                    "Named preflight profile to run with — the profile supplies every threshold,"
                            + " fixups and disabledChecks; request-level parameters for those are"
                            + " ignored. Built-ins ship with the app, customs live in"
                            + " configs/preflight-profiles.json",
            example = "offset-press")
    private String profileName;
}
