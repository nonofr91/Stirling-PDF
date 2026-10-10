# Detector/Corrector Tool Contract

Construction rules for tools that **detect** issues in a document and/or
**correct** them — the pattern the print preflight introduced and every future
tool of that family must follow. The contract exists so that pipeline steps can
be gated per document on precisely identified detections, and corrections can be
selected individually with their own parameters.

Related reading:

- [`docs/prepress-guide.md`](../docs/prepress-guide.md) — user-facing toolset guide
- [`docs/preflight-fixups-roadmap.md`](../docs/preflight-fixups-roadmap.md) — finding→fixup status
- [`ADDING_TOOLS.md`](../ADDING_TOOLS.md) — registering a tool in the app

## The model at a glance

A detector/corrector tool family has four declared vocabularies:

| Vocabulary | Backend source | Frontend source |
|---|---|---|
| Detection codes | enum (`PreflightCheck`) | `PREFLIGHT_DETECTIONS` (catalog) |
| Correction codes | enum (`PreflightFixer.Code`) | `PREFLIGHT_FIXUPS` (catalog) |
| Step-report fields | compact record in the `X-Stirling-Tool-Report` header | `ReportProducerDescriptor` in `src/core/data/reportCatalog.ts` |
| Correction params | per-fixup accepted keys (`FIXUP_PARAMS`) | `FixupDescriptor.params` |

Backend enums decide what is *valid*; the frontend catalog decides what is
*shown* (labels, grouping, which checks a fixup addresses, which params it
takes). Codes and parameters are added together in the same PR on both sides.

## R1 — Detections

- Every detection has a stable `UPPER_SNAKE` code. Codes are **append-only**:
  never rename, never reuse; retiring a code means deprecating it, not deleting
  it — stored pipelines may still reference it.
- The code is declared in the backend check enum with its metadata: category,
  default severity, and whether the check needs a rendered-page pass.
- **Emission rule**: a `Finding` is built with `PreflightCheck.X.code()` —
  never a string literal. Emitting an undeclared code must fail to compile.
  (Counter-example that motivated the rule: `INK_COVERAGE_HIGH_RENDERED` was
  emitted as a literal with no enum entry — it could not be disabled and had no
  declared metadata.)
- `disabledChecks` accepts only catalog codes; every emitted code is
  individually disableable by its own code.
- A `Finding` carries `code`, `severity`, `pages`/`areas` when the issue is
  located, and a `message` resolved through the `finding.<CODE>` i18n key.
- Each detection documents which request thresholds it consumes.

## R2 — Corrections (fixups)

- Every correction has a stable `UPPER_SNAKE` code in the fixup enum —
  append-only, same as detections.
- Each fixup declares in the catalog:
  - `addressesChecks` — the detection codes it is meant to resolve;
  - `params` — fixup-specific parameters (key, kind, default, bounds);
  - `engine` — `document` | `stream` | `ghostscript`, which sets where the
    implementation lives and what it may touch;
  - `destructive` — when the correction removes content or semantics;
  - prerequisites — when the fixup only makes sense if a given finding fired.
- Selection semantics of the `fixups` request field: absent or empty applies
  **every fixup that has something to correct**, the sentinel `NONE` applies
  none, an explicit list runs those fixups **unconditionally** (even when the
  matching finding did not fire — the caller asserted intent).
- Execution order is fixed and documented: cheap document-level corrections
  first, content-stream rewrites next, destructive corrections
  (`REMOVE_EMPTY_PAGES`, `CLIP_TO_CROPBOX`) and the Ghostscript pass last.
- A fixup with no target reports *skipped*, not *applied* and not *failed*.

## R3 — Parameters

Parameters follow the hybrid rule: shared quantities stay flat,
fixup-specific tuning is namespaced.

- **Shared thresholds stay flat** on the request (`requiredBleedMm`,
  `maxInkCoveragePercent`, `maxImageDpi`, `iccProfile`…): they are the same
  physical quantity for the detector and the corrector — duplicating them per
  fixup would let a step say "bleed under 3 mm fails" while "extend to 5 mm".
- **Fixup-specific parameters are namespaced** under the fixup code:
  `fixupParams.<FIXUP_CODE>.<param>` — e.g.
  `fixupParams.EXTEND_BLEED.method`, `fixupParams.DOWNSAMPLE_IMAGES.jpegQuality`.
  Declare a param here only when no existing flat field already carries the
  same quantity.
- **Wire shape: a JSON string.** `fixupParams` is a single request field whose
  value is a JSON object (`{"EXTEND_BLEED":{"method":"MIRROR_IMAGE"}}`), both in
  direct multipart calls and inside `PipelineStep.parameters` — the policy
  executor posts parameters as form fields and cannot nest maps. This matches
  the existing convention for structured fields (`redactions`, `edits`).
- **Strict validation**: an unknown fixup code key or an unknown inner
  parameter key fails the request with 400 — a mis-addressed correction or a
  mistyped parameter must surface, never be silently dropped. The backend's
  `FIXUP_PARAMS` declares the accepted keys per fixup; the frontend catalog
  mirrors it for editing.
- Profiles carry `fixupParams` too and merge field-by-field like any other
  profile value.

## R4 — Step report

- A reporting endpoint stamps `X-Stirling-Tool-Report` with
  `{<namespace>: <compact summary>}`. The top-level key becomes the
  `report.<namespace>.*` fact tree the executor merges onto the produced file —
  keep it compact (HTTP header size limits): verdict, counts, code lists, never
  full findings.
- Every field is declared in the frontend `reportCatalog.ts` producer descriptor:
  `path`, `kind` (`enum` | `count` | `code-list`), `vocabulary`
  (literal values, or the `checks`/`fixups` catalogs), and `producedBy`
  (`analysis` | `fix`).
- A corrector step re-analyses the delivered document and emits post-fix
  fields; the pre-fix state travels in `pre*` fields so a gate can compare
  before and after.
- Correction outcome is fully observable: `fixupsApplied` (changed something),
  `fixupsSkipped` (requested but nothing to correct) — a gate can distinguish
  "fix ran and resolved" from "fix ran but had no target".
- Every code a report list can carry is catalog-valid by construction.

## R5 — Pipeline UI derives from the catalog

- The frontend catalog is the **only** place that maps endpoint → produced
  report fields, detection codes → labels, fixup codes → labels/params/checks.
  No editor, builder or predicate hardcodes endpoint sets, field paths or
  vocabularies.
- The condition editor offers a `report.<ns>.<field>` option exactly when a
  step whose endpoint declares `producesReport(ns)` precedes (for step gates)
  or exists in the chain (for output routing). A field declared
  `producedBy: "fix"` additionally needs a fix variant upstream.
- Value pickers come from the field's vocabulary: enum fields get a `Select`
  of their values, `code-list` fields get a `MultiSelect` over the
  check/fixup catalog ids.
- Correction parameters are edited through a generic `FixupParamsEditor`
  driven by `FixupDescriptor.params` — registering a new parameterized fixup
  gives it a UI with no bespoke component.
- A new reporting tool family gets gates, routing options and blockers by
  adding one catalog entry — no editor or builder code.

## R6 — Registering a new detector/corrector tool (checklist)

1. Detection codes in the backend check enum; every `new Finding` emitted via
   `enum.code()`; `finding.<CODE>` message keys.
2. Correction codes in the fixup enum; implementation in the right engine;
   accepted params in `FIXUP_PARAMS`.
3. Endpoint stamps `X-Stirling-Tool-Report` under its namespace and declares
   `@ToolIO` for the file contract.
4. Frontend catalog entry: detections, fixups (with `addressesChecks`,
   `params`, `engine`), report descriptor, endpoint→produces registration.
5. i18n labels (check labels, fixup labels, param label/help) in all locales.
6. Tests: catalog integrity, parameter validation, report field presence.

## Current non-goals

- No full DAG execution model: steps stay ordered; branching happens through
  per-step `when` gates and output routing.
- No backend capabilities endpoint: the catalog is a frontend-declared mirror
  of the backend enums. A future `x-stirling-report` OpenAPI extension could
  serve descriptors from Java the same way `@ToolIO` serves file contracts —
  deliberately deferred until a second tool family proves the shape.
