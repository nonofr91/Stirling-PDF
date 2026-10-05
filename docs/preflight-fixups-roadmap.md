# Print Preflight — Fixups Roadmap

The preflight engine (`stirling.software.SPDF.service.preflight`) reports
problems; this document maps each finding code to the automatic correction
("fixup") a commercial tool like Enfocus PitStop would apply, and how it would
plug into Stirling-PDF. Fixups stay opt-in: they mutate the document and can
change visual output or violate the production intent the check was guarding.

## Delivery model

Each fixup is an explicit, user-chosen action — per finding or batch — exposed
as its own endpoint/operation, not a silent side effect of preflight. A finding
carries its location (`FindingArea`) so a fixup can scope to the affected pages.

## Roadmap by finding

| Finding | Fixup | Notes |
|---|---|---|
| `FONT_NOT_EMBEDDED` | Embed/subset font | Only when a substitute is licensed and metrics match; otherwise document-level no-op with warning. |
| `COLOR_RGB_USED` | Convert to CMYK via ICC | Needs a target output profile (new param `targetProfile`); preserve pure black K-only. |
| `COLOR_SPOT` | Map spot → CMYK or rename alias | `SPOT_ALIAS` merges separations whose normalized names collide. |
| `OVERPRINT_WHITE` | Set knockout on the object | Safe: white overprint prints nothing either way. |
| `OVERPRINT_BLACK` | Set fill overprint on 100% K text | The standard press behaviour; reversible. |
| `TEXT_RICH_BLACK` | Convert 4C text to K-only | Only below the small-text threshold; large display type keeps rich black. |
| `INK_COVERAGE_HIGH` | Re-map CMYK to the TAC limit | GCR/UCA-style reduction; needs a target limit (`maxInkCoveragePercent`). |
| `IMAGE_LOW_RES` | No reliable fixup — resampling cannot invent detail | Report only. |
| `IMAGE_OVERSAMPLED` | Downsample to `maxImageDpi` | Reuses existing image compression code paths. |
| `IMAGE_1BIT_LOW_RES` | Resample to `minImage1BitDpi` | Upsampling line art is lossy; flag for user decision. |
| `TRANSPARENCY` | Flatten transparency | Existing flatten tool can be wired as a chained fixup. |
| `BLEED_MISSING` / `BLEED_INSUFFICIENT` / `BLEED_UNPAINTED` | Extend bleed (edge replication) | `PageBleedGenerator` already implements band rendering — wire it here. |
| `TRIMBOX_MISSING` / `CROPBOX_NE_MEDIA` | Normalize page boxes | Existing set-page-boxes tool covers this. |
| `SAFETY_MARGIN` | No fixup — content is genuinely too close | Report only; scaling pages is a user decision. |
| `ANNOTATION_IN_TRIM` | Move or remove the annotation | Interactive-only decision. |
| `FORM_FIELDS` / `XFA_FORM` | Flatten form appearances | Merges widget appearances into content streams. |
| `EMBEDDED_FILES` | Strip name-tree attachments | Existing attachment tooling. |
| `JAVASCRIPT` | Remove `/JavaScript` name tree | Document-level strip. |
| `SIGNATURES` | No fixup — removing a signature invalidates the signing contract | Report only. |
| `LAYERS_PRINT_OFF` | Delete non-printing OCGs or set their print state | ISO 19593-1 finishing layers should usually be kept for the press room. |
| `INVISIBLE_TEXT` | Remove invisible-mode text runs | Often OCR remnants — stripping is usually safe. |
| `USER_UNIT` | Rescale page boxes to unit 1 | Pure geometry fixup. |
| `OBJECT_OUTSIDE_PAGE` | Clip content to the CropBox | Optional: drop fully-outside objects. |
| `OUTPUT_INTENT_MISSING` | Attach a configurable ICC output intent | First step toward PDF/X-style conformance. |
| `REGISTRATION_PAINT` | Re-map `All`/registration colorant to process black | Only where the paint is not crop/fold marks. |

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
