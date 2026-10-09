package stirling.software.SPDF.model.api.security;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A named preflight profile: a snapshot of every threshold plus the fixups and disabled checks to
 * apply. Profiles are authoritative — a request carrying {@code profileName} takes all its
 * parameters from the profile; the remaining request fields ({@code fileInput}, {@code
 * reportLanguage}, {@code reportFormat}, {@code iccProfile}) stay request-level. Null fields leave
 * the request's own value (or the built-in default) untouched.
 */
@Data
@NoArgsConstructor
public class PrintPreflightProfile {

    @Schema(
            description = "Unique profile name — the key used in profileName",
            example = "offset-press")
    private String name;

    @Schema(description = "Human-readable summary shown in the profile picker")
    private String description;

    @Schema(description = "Shipped with the application — true for built-ins, ignored on save")
    private boolean builtin;

    @Schema(description = "Bleed width in millimetres required on every side beyond the TrimBox")
    private Float requiredBleedMm;

    @Schema(description = "Images rendered below this effective resolution are reported")
    private Integer minImageDpi;

    @Schema(description = "Strokes thinner than this width in points are reported as hairlines")
    private Float hairlineThresholdPt;

    @Schema(description = "Render each page and check the bleed band is actually painted")
    private Boolean checkBleedCoverage;

    @Schema(description = "Text rendered smaller than this size in points is reported")
    private Float minFontSizePt;

    @Schema(
            description =
                    "Content closer than this distance in millimetres to the trim edge is reported")
    private Float safetyMarginMm;

    @Schema(
            description =
                    "Painted colours whose total ink coverage exceeds this percentage are reported")
    private Integer maxInkCoveragePercent;

    @Schema(
            description =
                    "Measure ink coverage from a rendered CMYK raster instead of painted fills")
    private Boolean renderedInkCoverage;

    @Schema(description = "1-bit images rendered below this effective resolution are reported")
    private Integer minImage1BitDpi;

    @Schema(
            description =
                    "Images rendered above this effective resolution are reported as oversampled")
    private Integer maxImageDpi;

    @Schema(description = "More spot separations than this are reported; 0 disables the limit")
    private Integer maxSpotCount;

    @Schema(description = "Prepend summary pages to the annotated PDF")
    private Boolean includeSummaryPage;

    @Schema(description = "Finding codes to skip entirely")
    private List<String> disabledChecks;

    @Schema(description = "Fixup codes to apply; \"NONE\" disables all fixups")
    private List<String> fixups;
}
