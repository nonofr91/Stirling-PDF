import { describe, expect, it, vi } from "vitest";
import { fireEvent, render as baseRender, screen } from "@testing-library/react";
import { PortalTestProviders } from "@portal/test/TestQueryProvider";
import { RoutingConditionEditor } from "@app/components/conditions/RoutingConditionEditor";
import { documentFieldCondition } from "@app/data/classificationConditions";
import type { MatchesAnyCondition } from "@app/conditions/types";

vi.mock("react-i18next", () => ({
  useTranslation: () => ({
    t: (_key: string, fallback?: string) => fallback ?? _key,
    i18n: { changeLanguage: vi.fn() },
  }),
}));

function setup(
  condition: MatchesAnyCondition,
  { preflightAvailable = true, preflightFixAvailable = true } = {},
) {
  const onChange = vi.fn();
  baseRender(
    <RoutingConditionEditor
      condition={condition}
      onChange={onChange}
      classificationAvailable={false}
      preflightAvailable={preflightAvailable}
      preflightFixAvailable={preflightFixAvailable}
    />,
    { wrapper: PortalTestProviders },
  );
  return onChange;
}

describe("RoutingConditionEditor", () => {
  it("switches to a check-code field and emits its report path", () => {
    const onChange = setup(documentFieldCondition("document.extension"));

    fireEvent.click(screen.getByRole("textbox", { name: "Match by" }));
    fireEvent.click(screen.getByText("Preflight error type"));

    expect(onChange).toHaveBeenCalledWith({
      input: { source: "document", field: "report.preflight.failingChecks" },
      operator: "matches-any",
      values: [],
    });
  });

  it("keeps the pre-fixup fields gated on a fix step, not a plain analysis", () => {
    setup(documentFieldCondition("document.extension"), {
      preflightAvailable: true,
      preflightFixAvailable: false,
    });

    fireEvent.click(screen.getByRole("textbox", { name: "Match by" }));

    expect(
      screen.getByText("Preflight error type").closest("[data-combobox-disabled]"),
    ).toBeNull();
    for (const option of [
      "Preflight error type before fixups",
      "Applied fixup",
    ]) {
      expect(
        screen.getByText(option).closest("[data-combobox-disabled]"),
      ).not.toBeNull();
    }
  });

  it("greys out the report fields when no preflight step feeds the route", () => {
    setup(documentFieldCondition("document.extension"), {
      preflightAvailable: false,
      preflightFixAvailable: false,
    });

    fireEvent.click(screen.getByRole("textbox", { name: "Match by" }));

    for (const option of [
      "Preflight error type",
      "Preflight warning type",
      "Preflight error type before fixups",
      "Applied fixup",
    ]) {
      expect(screen.getByText(option).closest("[data-combobox-disabled]")).not.toBeNull();
    }
  });

  it("lets a check-code condition pick finding ids from the known catalog", () => {
    const onChange = setup(
      documentFieldCondition("report.preflight.failingChecks"),
    );

    fireEvent.click(screen.getByRole("textbox", { name: "Values to match" }));
    fireEvent.click(screen.getByRole("option", { name: "BLEED_MISSING" }));

    expect(onChange).toHaveBeenCalledWith({
      input: {
        source: "document",
        field: "report.preflight.failingChecks",
      },
      operator: "matches-any",
      values: ["BLEED_MISSING"],
    });
  });
});
