import { describe, expect, it } from "vitest";
import {
  PREFLIGHT_DETECTIONS,
  PREFLIGHT_FIXUPS,
  PREFLIGHT_CHECK_IDS,
  PREFLIGHT_FIXUP_IDS,
  detectionByCode,
  validateFixupParams,
} from "@app/data/preflightCatalog";
import {
  PREFLIGHT_REPORT,
  fixOnlyReportPaths,
  reportAvailabilityFromEndpoints,
  reportFieldByPath,
} from "@app/data/reportCatalog";

/**
 * Catalog integrity: the declarative mirror of the backend enums must be
 * internally consistent — every referenced code exists, every parameter spec
 * is well-formed. Catches a catalog edit that drifts from the contract
 * (devGuide/prepress-tool-contract.md) without a matching backend change.
 */
describe("preflight catalog", () => {
  it("declares unique detection and fixup codes", () => {
    const detections = PREFLIGHT_DETECTIONS.map((d) => d.code);
    expect(new Set(detections).size).toBe(detections.length);
    const fixups = PREFLIGHT_FIXUPS.map((f) => f.code);
    expect(new Set(fixups).size).toBe(fixups.length);
  });

  it("uses UPPER_SNAKE codes throughout", () => {
    const upperSnake = /^[A-Z][A-Z0-9_]*$/;
    for (const code of [...PREFLIGHT_CHECK_IDS, ...PREFLIGHT_FIXUP_IDS]) {
      expect(code, code).toMatch(upperSnake);
    }
  });

  it("points every addressesChecks entry at a declared detection", () => {
    for (const fixup of PREFLIGHT_FIXUPS) {
      for (const check of fixup.addressesChecks) {
        expect(
          detectionByCode(check),
          `${fixup.code} addresses undeclared check ${check}`,
        ).toBeDefined();
      }
    }
  });

  it("declares well-formed parameter specs", () => {
    for (const fixup of PREFLIGHT_FIXUPS) {
      for (const spec of fixup.params) {
        if (spec.kind === "enum") {
          expect(
            spec.options?.length,
            `${fixup.code}.${spec.key} needs options`,
          ).toBeGreaterThan(0);
          expect(
            spec.default === undefined ||
              spec.options?.includes(String(spec.default)),
            `${fixup.code}.${spec.key} default must be one of the options`,
          ).toBe(true);
        } else {
          expect(
            spec.min === undefined ||
              spec.max === undefined ||
              spec.min < spec.max,
            `${fixup.code}.${spec.key} bounds are inverted`,
          ).toBe(true);
        }
      }
    }
  });
});

describe("validateFixupParams", () => {
  it("accepts declared params on declared fixups", () => {
    expect(
      validateFixupParams({
        EXTEND_BLEED: { method: "PIXEL_REPEAT" },
        DOWNSAMPLE_IMAGES: { jpegQuality: 0.8 },
        PURE_BLACK_TEXT: { maxPt: 12 },
      }),
    ).toBe(true);
    expect(validateFixupParams({})).toBe(true);
  });

  it("rejects what the backend would reject", () => {
    expect(validateFixupParams({ NOT_A_FIXUP: {} })).toBe(false);
    expect(validateFixupParams({ EXTEND_BLEED: { bogus: 1 } })).toBe(false);
    expect(validateFixupParams({ EXTEND_BLEED: { method: "SIDEWAYS" } })).toBe(
      false,
    );
    expect(
      validateFixupParams({ DOWNSAMPLE_IMAGES: { jpegQuality: 1.5 } }),
    ).toBe(false);
    expect(validateFixupParams({ DOWNSAMPLE_IMAGES: { jpegQuality: 0 } })).toBe(
      false,
    );
    expect(validateFixupParams({ PURE_BLACK_TEXT: { maxPt: -1 } })).toBe(false);
    expect(
      validateFixupParams({ DOWNSAMPLE_IMAGES: { jpegQuality: "high" } }),
    ).toBe(false);
  });
});

describe("report catalog", () => {
  it("declares every fix-only field under a fix endpoint", () => {
    expect(PREFLIGHT_REPORT.fixEndpoints.length).toBeGreaterThan(0);
    for (const path of fixOnlyReportPaths()) {
      const field = reportFieldByPath(path);
      expect(field?.producedBy).toBe("fix");
    }
    // The corrector outcome lists must stay distinguishable for gates.
    expect(fixOnlyReportPaths()).toContain("report.preflight.fixupsApplied");
    expect(fixOnlyReportPaths()).toContain("report.preflight.fixupsSkipped");
  });

  it("carries the backend enum vocabularies the frontend catalogs declare", () => {
    // `x-stirling-report` inlines the Java enum members as `values` — so a
    // check or fixup code added to preflightCatalog without its backend enum
    // entry (or vice versa) fails right here.
    const codeListFields = PREFLIGHT_REPORT.fields.filter(
      (f) => f.kind === "code-list",
    );
    expect(codeListFields.length).toBeGreaterThan(0);
    for (const field of PREFLIGHT_REPORT.fields) {
      if (field.kind === "code-list" || field.kind === "enum") {
        expect(field.values?.length, field.path).toBeGreaterThan(0);
      }
    }
    const checkFields = codeListFields.filter((f) => f.path.endsWith("Checks"));
    const fixupFields = codeListFields.filter(
      (f) =>
        f.path.endsWith("fixupsApplied") || f.path.endsWith("fixupsSkipped"),
    );
    expect(checkFields.length).toBeGreaterThan(0);
    expect(fixupFields.length).toBeGreaterThan(0);
    for (const field of checkFields) {
      expect([...field.values!].sort(), field.path).toEqual(
        [...PREFLIGHT_CHECK_IDS].sort(),
      );
    }
    for (const field of fixupFields) {
      expect([...field.values!].sort(), field.path).toEqual(
        [...PREFLIGHT_FIXUP_IDS].sort(),
      );
    }
  });
});

describe("reportAvailabilityFromEndpoints", () => {
  const FIX = "/api/v1/security/print-preflight-fix";
  const ANALYSIS = "/api/v1/security/print-preflight-annotated";

  it("marks fix fields once a fix endpoint ran", () => {
    expect(reportAvailabilityFromEndpoints([ANALYSIS])).toEqual({
      preflight: "analysis",
    });
    expect(reportAvailabilityFromEndpoints([FIX])).toEqual({
      preflight: "fix",
    });
    expect(reportAvailabilityFromEndpoints([])).toEqual({});
  });

  it("lets the last producer win — a later analysis replaces the fix report", () => {
    // mergeReports replaces the whole namespace per step, so fix fields the
    // earlier fix emitted are gone once an analysis step rewrites them.
    expect(reportAvailabilityFromEndpoints([FIX, ANALYSIS])).toEqual({
      preflight: "analysis",
    });
    expect(reportAvailabilityFromEndpoints([ANALYSIS, FIX])).toEqual({
      preflight: "fix",
    });
  });
});
