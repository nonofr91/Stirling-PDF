# Print Prepress — Usage Guide

This guide covers the print-production toolset of the `prepress` branch: when
to pick each tool, what every preflight check and fixup does, and how to wire
them into automation pipelines with per-document gates and routing.

For the implementation-level mapping of findings to corrections, see
[`preflight-fixups-roadmap.md`](preflight-fixups-roadmap.md).

## The preflight tools at a glance

All five operations share the same analysis engine (`print-preflight`) and the
same request parameters — they differ only in what they hand back.

| Operation | Endpoint | Returns | Use it when… |
|---|---|---|---|
| **Preflight** | `print-preflight` | JSON report | You are exploring interactively: the UI renders verdict, findings with page locations, fonts, colours and image stats. |
| **Annotated preflight** | `print-preflight-annotated` | Annotated PDF | Someone must *see* the problems: every finding is framed on a scaled A4 copy. Right before a fix step in a pipeline so the audit trail keeps proof of what was flagged. |
| **Preflight report** | `print-preflight-report` | Standalone PDF report | You need a deliverable document for a client or a MIS: verdict, document facts, fonts, colours, findings — without the source pages. |
| **Preflight fix** | `print-preflight-fix` | Corrected PDF | You want the problems gone: applies the requested `fixups` (empty list = everything applicable), re-analyses and returns the fixed file. Never mutates the source. |
| **Fix preview** | `print-preflight-fix-preview` | JSON audit | You want proof before committing: same corrections in memory, returns the resolved / remaining / introduced findings — no PDF produced. |

Rule of thumb: **analyse first, fix second, verify third**. A fix that resolves
nothing still rewrites the file; a preview tells you what would change before
you spend the bytes.

## Profiles

A profile is a named bundle of thresholds, enabled checks and preferred fixups.

| Profile | Intent |
|---|---|
| `offset-press` | Commercial offset — strictest: 300 dpi images, 3 mm bleed, ≤ 300 % ink, aggressive fixup list (CMYK conversion, bleed, boxes…). |
| `digital-press` | Toner/inkjet digital — more tolerant: 200 dpi, ≤ 320 % ink, keeps RGB and spot untouched. |
| `large-format` | Signage/large format — lower dpi expectations, wider margins. |
| `inspect-only` | Analysis with every fixup disabled — pure diagnosis. |

Pick a profile with `profileName` (UI: profile picker). Everything it sets can
still be overridden per request; named profiles can also be saved/edited from
the UI and are shared server-side.

### Tunable thresholds

`requiredBleedMm`, `minImageDpi`, `maxImageDpi`, `minImage1BitDpi`,
`hairlineThresholdPt`, `safetyMarginMm`, `minFontSizePt`,
`maxInkCoveragePercent`, `maxSpotCount`, `checkBleedCoverage`,
`renderedInkCoverage`, `includeSummaryPage`, `reportLanguage` (`en`/`fr`),
`disabledChecks` (check codes to skip), `fixups`, `iccProfile` (output intent
upload).

`renderedInkCoverage=true` adds a Ghostscript raster pass that measures **real**
pixel TAC — slower but authoritative; its finding is `INK_COVERAGE_HIGH_RENDERED`
which then *replaces* the painted-fill estimate.

## The checks

Default severities — profiles can disable a check entirely via `disabledChecks`.

### Fonts
| Code | Severity | Detects |
|---|---|---|
| `FONT_NOT_EMBEDDED` | Error | Non-embedded fonts — output not guaranteed. |
| `FONT_TYPE3` | Warning | Type 3 fonts — may rasterise or be refused by the RIP. |

### Colours & separations
| Code | Severity | Detects |
|---|---|---|
| `COLOR_RGB_USED` | Warning | RGB paint — needs CMYK conversion for offset. |
| `COLOR_SPOT` | Info | Spot colorants in use (plates!). |
| `SPOT_ALIAS` | Warning | Spot names normalising to the same ink — duplicate plates. |
| `SPOT_COUNT` | Info | More separations than the press allows. |
| `OVERPRINT_WHITE` | Error | White set to overprint — prints nothing. |
| `OVERPRINT_BLACK` | Warning | Black knocking out — registration drift shows slivers. |
| `TEXT_RICH_BLACK` | Warning | Small text in rich/composite black — fuzzy type. |
| `INK_COVERAGE_HIGH` | Warning | Painted TAC above the limit — drying/registration issues. |
| `INK_COVERAGE_HIGH_RENDERED` | Warning | Rendered TAC above the limit (opt-in raster pass). |
| `REGISTRATION_PAINT` | Info | `All`/`Registration` colorant — hits every plate. |
| `OUTPUT_INTENT_MISSING` | Warning | No output intent — conversion will guess. |
| `PATTERN_USED` / `SHADING_USED` | Info | Patterns / smooth shadings — flattening varies by RIP. |

### Geometry
| Code | Severity | Detects |
|---|---|---|
| `TRIMBOX_MISSING` | Warning | No TrimBox — finished size undefined. |
| `BLEED_MISSING` / `BLEED_INSUFFICIENT` | Error | No/short BleedBox — white edges after trimming. |
| `BLEED_UNPAINTED` | Warning | Declared bleed band not painted (expensive check). |
| `SAFETY_MARGIN` | Warning | Content within X mm of trim — may be cut. |
| `CROPBOX_NE_MEDIA` | Info | CropBox ≠ MediaBox — slug margin, usually fine. |
| `USER_UNIT` | Warning | Non-default /UserUnit scaling. |
| `OBJECT_OUTSIDE_PAGE` | Info | Paint entirely outside the crop — dead weight. |

### Content
| Code | Severity | Detects |
|---|---|---|
| `HAIRLINE` | Warning | Strokes below the pt threshold — may drop out. |
| `TEXT_SMALL` | Warning | Text below the pt threshold. |
| `TRANSPARENCY` | Info | Live transparency — flatten for PDF/X-1a. |
| `OPTIONAL_CONTENT` | Info | OCG layers — print behaviour varies. |
| `ANNOTATION_IN_TRIM` | Warning | Annotations inside the trim may print. |
| `INVISIBLE_TEXT` | Info | OCR/search-layer text — never prints. |
| `CONTENT_PARSE_ERROR` | Warning | A page could not be fully analysed. |

### Document
| Code | Severity | Detects |
|---|---|---|
| `MIXED_PAGE_SIZES` | Info | Distinct page sizes/rotations. |
| `EMPTY_PAGE` | Info | Pages with no painted content. |
| `EMBEDDED_FILES` | Warning | Attachments travelling with the PDF. |
| `FORM_FIELDS` / `XFA_FORM` | Warning | Interactive fields — flatten before print. |
| `JAVASCRIPT` | Info | JavaScript actions — ignored by RIPs. |
| `SIGNATURES` | Info | Signature fields — edits invalidate them. |
| `LAYERS_PRINT_OFF` | Info | OCGs configured off for print. |

Technical separations (cut, crease, foil, white…) are excluded from print
findings — the engine knows `CutContour` & co. are not process inks.

## The fixups

Requested via the `fixups` list; empty = everything applicable. A fixup with
nothing to do is silently skipped — the applied codes come back in
`X-Preflight-Fixups` and `report.preflight.fixupsApplied`.

| Fixup | What it does | Resolves |
|---|---|---|
| `SET_MISSING_BOXES` | Declares TrimBox = CropBox. | `TRIMBOX_MISSING` |
| `EXTEND_BLEED` | Mirrors edge content into the missing bleed band, grows the boxes. | `BLEED_MISSING`, `BLEED_INSUFFICIENT`, `BLEED_UNPAINTED` |
| `CLIP_TO_CROPBOX` | Wraps each page in a CropBox clip — paint beyond it can no longer render. | `OBJECT_OUTSIDE_PAGE` |
| `DISCARD_CROPBOX` | Drops a CropBox that differs from MediaBox. | `CROPBOX_NE_MEDIA` |
| `NORMALIZE_USER_UNIT` | Scales page boxes by the unit, drops `/UserUnit`. | `USER_UNIT` |
| `REMOVE_EMPTY_PAGES` | Deletes pages counted empty (never the last one). | `EMPTY_PAGE` |
| `RGB_TO_CMYK` | Ghostscript ICC conversion, images included. | `COLOR_RGB_USED` |
| `SPOT_TO_CMYK` | Pixel-wise remap of spot paints/images into the CMYK alternate. | `COLOR_SPOT` (when conversion intended) |
| `MERGE_SPOT_ALIASES` | Folds aliased spot names onto one plate. | `SPOT_ALIAS` |
| `REGISTRATION_TO_BLACK` | Rewrites `All`/`Registration` paint to plain K. | `REGISTRATION_PAINT` |
| `KNOCKOUT_WHITE` | Clears overprint on white paint ops. | `OVERPRINT_WHITE` |
| `OVERPRINT_BLACK_TEXT` | Sets op/OP+OPM1 on pure-K text show ops. | `OVERPRINT_BLACK` (text) |
| `PURE_BLACK_TEXT` | Rewrites rich-black text under 24 pt to `0 0 0 1`. | `TEXT_RICH_BLACK` |
| `REDUCE_INK_COVERAGE` | UCR/GCR remap on CMYK ops *and* CMYK image pixels. | `INK_COVERAGE_HIGH`, `INK_COVERAGE_HIGH_RENDERED` |
| `SET_OUTPUT_INTENT` | Attaches the uploaded `iccProfile` (or bundled sRGB) when none exists. | `OUTPUT_INTENT_MISSING` |
| `DOWNSAMPLE_IMAGES` | Re-encodes images above `maxImageDpi`. | `IMAGE_OVERSAMPLED` |
| `FLATTEN_TRANSPARENCY` | Ghostscript pdfwrite pass at PDF 1.3. | `TRANSPARENCY` |
| `TEXT_TO_OUTLINES` | Ghostscript `-dNoOutputFonts` — text becomes vectors. **Destructive: kills text semantics.** | `FONT_NOT_EMBEDDED` (opt-in) |
| `FLATTEN_FORM` | AcroForm flatten; XFA dropped first. | `FORM_FIELDS`, `XFA_FORM` |
| `REMOVE_ANNOTATIONS_IN_TRIM` | Drops print-flagged annotations overlapping trim. | `ANNOTATION_IN_TRIM` |
| `REMOVE_ATTACHMENTS` | Strips the embedded-files name tree. | `EMBEDDED_FILES` |
| `REMOVE_JAVASCRIPT` | Strips the `/JavaScript` name tree. | `JAVASCRIPT` |
| `REMOVE_INVISIBLE_TEXT` | Drops `Tr 3` text ops (line-moves preserved). | `INVISIBLE_TEXT` |
| `ENABLE_LAYER_PRINTING` | Flips `/Usage/Print/PrintState` back to ON on every OCG. | `LAYERS_PRINT_OFF` |

Report-only by design — `IMAGE_LOW_RES`, `IMAGE_1BIT_LOW_RES`, `SAFETY_MARGIN`,
`SIGNATURES`: resampling cannot invent detail, scaling/outs are your call.

## Pipelines: gates and routing

Every step in an automation pipeline can carry a **`when` gate** and every
destination can sit behind a **routing rule** — both read the same facts:
`document.*` fields plus the report each step leaves behind.

### The preflight step report

Both analysis and fix endpoints attach `report.preflight.*` to the produced
file; it survives any number of later steps until the next preflight-family
step replaces it (a fix keeps the pre-fixup state under `pre*`).

| Field | Type | Meaning |
|---|---|---|
| `verdict` | `pass`/`warn`/`fail` | Overall state of the delivered file. |
| `errors` / `warnings` | count | Remaining findings per severity. |
| `failingChecks` | list | Distinct finding codes at error severity. |
| `warningChecks` / `infoChecks` | list | Same for warnings / infos. |
| `fixupsApplied` | list | Fixup codes that changed something (empty on analysis). |
| `preErrors` / `preWarnings` | count | Pre-fixup counts (fix steps only). |
| `preFailingChecks` / `preWarningChecks` / `preInfoChecks` | list | Pre-fixup check codes (fix steps only). |

Conditions are *matches-any*: a gate on `failingChecks` fires when **any** of
the selected codes is present — pick several codes to share one step.

### Recipes

**Fix only what failed.** Preflight (annotated) → `print-preflight-fix` gated
`verdict = fail` → clean files bypass untouched, broken files get corrected.

**One step per error type.** Preflight finds `COLOR_RGB_USED` +
`BLEED_MISSING` → a CMYK conversion step gated `failingChecks ∋ COLOR_RGB_USED`,
then a bleed step gated `failingChecks ∋ BLEED_MISSING`. Each file only sees
the corrections it needs; gates read the same carried report regardless of how
many steps sit between.

**Retry-aware chains.** After a fix, `failingChecks` holds *remaining* errors
and `preFailingChecks` the pre-fix ones — a second fix can gate on the former,
a routing rule on the latter.

**Quarantine routing.** Output node → routes: `verdict = fail` → "rejected"
folder + e-mailed report; fallback → "ready". Or `failingChecks ∋
OVERPRINT_WHITE` → manual-review queue before it reaches the press.

**Never-guess fields.** A gate on `report.preflight.*` only offers itself when
a preflight step precedes it; `pre*`/`fixupsApplied` only when a **fix** step
does. Incomplete conditions block the save — the builder names the step.

## Reading a report

Verdicts: `pass` (no errors), `warn` (warnings only), `fail` (errors remain).
Every finding carries its code, severity, message, the pages it hits and —
when located — rectangles in unrotated page space used by the annotated copy
and the viewer overlay. Reports localize (`reportLanguage`, en/fr).

The archive records every prepress operation versioned by content hash — the
source is v1, each transform a new version — so a pipeline run leaves a full
audit trail.
