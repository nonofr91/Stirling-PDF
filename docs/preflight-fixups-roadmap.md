# Print Preflight — Fixups Roadmap

The preflight engine (`stirling.software.SPDF.service.preflight`) reports
problems; this document maps each finding code to the automatic correction
("fixup") a commercial tool like Enfocus PitStop would apply, and how it would
plug into Stirling-PDF. Fixups stay opt-in: they mutate the document and can
change visual output or violate the production intent the check was guarding.

## Delivery model — implemented

`POST /api/v1/security/print-preflight-fix` runs the analysis, applies the
`fixups` request field (an empty list means "everything applicable") and
returns the corrected PDF — the uploaded file is never modified. The applied
codes come back in the `X-Preflight-Fixups` response header. `PreflightFixer`
performs dictionary- and resource-level corrections; `PreflightStreamFixer`
rewrites content streams token-by-token (colour, overprint, text state and CTM
tracked per stream, one pass for all stream-level fixups, pages plus nested
form XObjects and tiling patterns); `PreflightGhostscriptFixer` runs one
Ghostscript `pdfwrite` pass for engine-level corrections — RGB→CMYK,
transparency flattening, text-to-outlines — gated on an explicit request or
the matching finding firing. A fixup with nothing to do is silently
skipped. The frontend exposes the fixups picker under "Automatic fixes"
(interactive) and the `fixedPdf` report format (automation, keeps a PDF in the
pipeline).

`POST /api/v1/security/print-preflight-fix-preview` is the audit trail: it runs
the same corrections on an in-memory copy, re-analyses the result and returns
a JSON diff — applied fixup codes, error/warning/info counts before and after,
and the resolved / remaining / introduced finding sets — without producing the
mutated PDF. Exposed as "Preview fixes" in the results pane and as the
`fixAuditJson` report format in automation.

## Roadmap by finding

| Finding | Fixup code | Status / notes |
|---|---|---|
| `FONT_NOT_EMBEDDED` | `TEXT_TO_OUTLINES` | Done via Ghostscript `-dNoOutputFonts`: all text becomes vector outlines — destructive to text semantics, explicitly opt-in only. True font embedding stays pending (needs licensed substitutes with matching metrics). |
| `COLOR_RGB_USED` | `RGB_TO_CMYK` | Done via Ghostscript `-sColorConversionStrategy=CMYK` — its ICC engine also converts RGB images, which token rewriting cannot reach. |
| `COLOR_SPOT` | `MERGE_SPOT_ALIASES`, `SPOT_TO_CMYK` | Done: aliases merge to one plate; Separation/DeviceN paints whose tint transform lands in CMYK-family alternate are evaluated and rewritten as `k`/`K`. Spot image XObjects decode to raw samples, remap per-pixel through the same tint transform (Separation → 256-entry LUT, DeviceN → memoised tuples, `/Decode` honoured) and re-encode into the CMYK-family alternate; masks/matte follow. Indexed-over-separation and non-8-bit images stay pending. |
| `OVERPRINT_WHITE` | `KNOCKOUT_WHITE` | Done: token-level rewrite wraps white paint ops in `q /PFN gs … Q` (op/OP false). |
| `OVERPRINT_BLACK` | `OVERPRINT_BLACK_TEXT` | Done: wraps pure-K text show ops in `q /PFO gs … Q` (op/OP true, OPM 1). Black fills/strokes stay report-only — press-op choice. |
| `TEXT_RICH_BLACK` | `PURE_BLACK_TEXT` | Done: rewrites rich-black fills on text below 24pt effective to `0 0 0 K k`, preserving the K weight; original colour restored after the show op. |
| `INK_COVERAGE_HIGH` | `REDUCE_INK_COVERAGE` | Done: GCR-style remap — the shared CMY achromatic part folds into K, residual excess scales down — on CMYK paint ops above `maxInkCoveragePercent`. Raster images are untouched (needs pixel remapping). |
| `IMAGE_LOW_RES` | — | Report only — resampling cannot invent detail. |
| `IMAGE_OVERSAMPLED` | `DOWNSAMPLE_IMAGES` | Done: re-encodes placements above `maxImageDpi` (JPEG if DCT source, else lossless); skips CMYK/separations, 1-bit art, soft-masked images. |
| `IMAGE_1BIT_LOW_RES` | — | Upsampling is lossy — flag for user decision. |
| `TRANSPARENCY` | `FLATTEN_TRANSPARENCY` | Done via Ghostscript `pdfwrite -dCompatibilityLevel=1.3`: transparency flattens during the write-back. |
| `BLEED_MISSING` / `BLEED_INSUFFICIENT` | `EXTEND_BLEED` | Done: `PageBleedGenerator` MIRROR into the missing band, BleedBox/MediaBox/CropBox grown to cover. `BLEED_UNPAINTED` gaps between declared bleed and artwork are separate — the mirror paints the geometry gap. |
| `TRIMBOX_MISSING` | `SET_MISSING_BOXES` | Done: declares TrimBox = CropBox. |
| `CROPBOX_NE_MEDIA` | `DISCARD_CROPBOX` | Done: drops the declared CropBox. |
| `SAFETY_MARGIN` | — | Report only — scaling is a user decision. |
| `ANNOTATION_IN_TRIM` | `REMOVE_ANNOTATIONS_IN_TRIM` | Done: drops print-flagged annotations overlapping the trim. |
| `FORM_FIELDS` / `XFA_FORM` | `FLATTEN_FORM` | Done: AcroForm flatten, XFA stream dropped first (print RIPs only see the AcroForm rendition). |
| `EMBEDDED_FILES` | `REMOVE_ATTACHMENTS` | Done: strips the name tree. |
| `JAVASCRIPT` | `REMOVE_JAVASCRIPT` | Done: strips the `/JavaScript` name tree. |
| `SIGNATURES` | — | Report only — removing a signature invalidates it anyway. |
| `LAYERS_PRINT_OFF` | `ENABLE_LAYER_PRINTING` | Done: flips `/Usage/Print/PrintState` back to `ON` on every OCG. |
| `INVISIBLE_TEXT` | `REMOVE_INVISIBLE_TEXT` | Done: drops `Tj`/`TJ`/`'`/`"` while `Tr`=3; `'`/`"` keep their line move via `T*`. |
| `USER_UNIT` | `NORMALIZE_USER_UNIT` | Done: scales every declared page box by the unit, drops `/UserUnit`. |
| `OBJECT_OUTSIDE_PAGE` | `CLIP_TO_CROPBOX` | Done: wraps each page stream in `q <CropBox> re W n … Q` — paint beyond the crop can no longer render. The analyser tracks clip bounds (with q/Q save-restore) so clipped-away objects clear the finding; objects stay in the file (hidden, not deleted). |
| `OUTPUT_INTENT_MISSING` | `SET_OUTPUT_INTENT` | Done: attaches uploaded `iccProfile` or bundled sRGB2014 when no intent exists. |
| `REGISTRATION_PAINT` | `REGISTRATION_TO_BLACK` | Done: Separation `All`/`Registration` and all-process DeviceN `cs` ops become `DeviceCMYK`, the following `scn` becomes `k`/`K` keeping the tint. |
| `EMPTY_PAGE` | `REMOVE_EMPTY_PAGES` | Done: deletes pages the analysis counted as empty (never the last page). |

## Non-goals / watch items

- PDF/X or GWG certification is out of scope: the checks inform, they do not
  certify. Output-intent attachment gets closer but full conformance is a
  separate feature.
- Effective (rendered) TAC is approximated today from painted fills, not from a
  rasterized ink preview like pdfToolbox. A rendered ink pass is the natural
  next step if `maxInkCoveragePercent` proves too coarse.
- Fixups that change geometry (bleed extension, UserUnit rescale) must keep the
  annotated/report page indexes aligned — run analysis before mutation, or
  re-map areas after.
