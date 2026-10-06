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
form XObjects and tiling patterns). A fixup with nothing to do is silently
skipped. The frontend exposes the fixups picker under "Automatic fixes"
(interactive) and the `fixedPdf` report format (automation, keeps a PDF in the
pipeline).

## Roadmap by finding

| Finding | Fixup code | Status / notes |
|---|---|---|
| `FONT_NOT_EMBEDDED` | — | Pending: embed/subset only when a licensed substitute's metrics match. |
| `COLOR_RGB_USED` | — | Pending: CMYK conversion needs a target profile + K-preserving intent. |
| `COLOR_SPOT` | `MERGE_SPOT_ALIASES` | Done: renames colliding Separation/DeviceN colorants to one plate. Spot → CMYK mapping pending. |
| `OVERPRINT_WHITE` | `KNOCKOUT_WHITE` | Done: token-level rewrite wraps white paint ops in `q /PFN gs … Q` (op/OP false). |
| `OVERPRINT_BLACK` | `OVERPRINT_BLACK_TEXT` | Done: wraps pure-K text show ops in `q /PFO gs … Q` (op/OP true, OPM 1). Black fills/strokes stay report-only — press-op choice. |
| `TEXT_RICH_BLACK` | `PURE_BLACK_TEXT` | Done: rewrites rich-black fills on text below 24pt effective to `0 0 0 K k`, preserving the K weight; original colour restored after the show op. |
| `INK_COVERAGE_HIGH` | — | Pending: GCR-style CMYK remapping to the TAC limit. |
| `IMAGE_LOW_RES` | — | Report only — resampling cannot invent detail. |
| `IMAGE_OVERSAMPLED` | `DOWNSAMPLE_IMAGES` | Done: re-encodes placements above `maxImageDpi` (JPEG if DCT source, else lossless); skips CMYK/separations, 1-bit art, soft-masked images. |
| `IMAGE_1BIT_LOW_RES` | — | Upsampling is lossy — flag for user decision. |
| `TRANSPARENCY` | — | Pending: wire the flatten pass as a fixup. |
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
| `OBJECT_OUTSIDE_PAGE` | — | Pending: clip or drop fully-outside objects. |
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
