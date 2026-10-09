import { useTranslation } from "react-i18next";
import { Input, MultiSelect, Select } from "@app/ui";
import { ClassificationConditionEditor } from "@app/components/conditions/ClassificationConditionEditor";
import {
  PREFLIGHT_CHECK_IDS,
  PREFLIGHT_FIXUP_IDS,
} from "@app/data/preflightChecks";
import {
  classificationCondition,
  documentFieldCondition,
  requiresClassification,
} from "@app/data/classificationConditions";
import type { MatchesAnyCondition } from "@app/conditions/types";

interface RoutingConditionEditorProps {
  condition: MatchesAnyCondition;
  onChange: (condition: MatchesAnyCondition) => void;
  classificationAvailable: boolean;
  /** Report fields only make sense when a preflight step feeds the route. */
  preflightAvailable?: boolean;
  /** The pre-fixup and fixupsApplied fields are only emitted by a fix step, not an analysis. */
  preflightFixAvailable?: boolean;
}

const DOCUMENT_FIELDS = [
  "classification.labels",
  "document.extension",
  "document.filename",
  "document.title",
  "document.author",
  "report.preflight.verdict",
  "report.preflight.errors",
  "report.preflight.warnings",
  "report.preflight.failingChecks",
  "report.preflight.warningChecks",
  "report.preflight.infoChecks",
  "report.preflight.preFailingChecks",
  "report.preflight.fixupsApplied",
] as const;

const PREFLIGHT_VERDICTS = ["pass", "warn", "fail"] as const;

const CHECK_LIST_FIELDS = new Set<string>([
  "report.preflight.failingChecks",
  "report.preflight.warningChecks",
  "report.preflight.infoChecks",
  "report.preflight.preFailingChecks",
]);

/** Edits either an AI classification match or a deterministic document-fact match. */
export function RoutingConditionEditor({
  condition,
  onChange,
  classificationAvailable,
  preflightAvailable = false,
  preflightFixAvailable = false,
}: RoutingConditionEditorProps) {
  const { t } = useTranslation();
  const field = condition.input.field;
  const classification = requiresClassification(condition);
  const options = [
    {
      value: DOCUMENT_FIELDS[0],
      label: classificationAvailable
        ? t(
            "portal.pipelines.builder.routing.matchDocumentType",
            "Document type (AI classification)",
          )
        : t(
            "portal.pipelines.builder.routing.matchDocumentTypeDisabled",
            "Document type (AI unavailable)",
          ),
      disabled: !classificationAvailable,
    },
    {
      value: DOCUMENT_FIELDS[1],
      label: t(
        "portal.pipelines.builder.routing.matchExtension",
        "File extension",
      ),
    },
    {
      value: DOCUMENT_FIELDS[2],
      label: t(
        "portal.pipelines.builder.routing.matchFilename",
        "Exact filename",
      ),
    },
    {
      value: DOCUMENT_FIELDS[3],
      label: t("portal.pipelines.builder.routing.matchTitle", "PDF title"),
    },
    {
      value: DOCUMENT_FIELDS[4],
      label: t("portal.pipelines.builder.routing.matchAuthor", "PDF author"),
    },
    {
      value: DOCUMENT_FIELDS[5],
      label: t(
        "portal.pipelines.builder.routing.matchPreflightVerdict",
        "Preflight verdict",
      ),
      disabled: !preflightAvailable,
    },
    {
      value: DOCUMENT_FIELDS[6],
      label: t(
        "portal.pipelines.builder.routing.matchPreflightErrors",
        "Preflight error count",
      ),
      disabled: !preflightAvailable,
    },
    {
      value: DOCUMENT_FIELDS[7],
      label: t(
        "portal.pipelines.builder.routing.matchPreflightWarnings",
        "Preflight warning count",
      ),
      disabled: !preflightAvailable,
    },
    {
      value: DOCUMENT_FIELDS[8],
      label: t(
        "portal.pipelines.builder.routing.matchPreflightFailingChecks",
        "Preflight error type",
      ),
      disabled: !preflightAvailable,
    },
    {
      value: DOCUMENT_FIELDS[9],
      label: t(
        "portal.pipelines.builder.routing.matchPreflightWarningChecks",
        "Preflight warning type",
      ),
      disabled: !preflightAvailable,
    },
    {
      value: DOCUMENT_FIELDS[10],
      label: t(
        "portal.pipelines.builder.routing.matchPreflightInfoChecks",
        "Preflight info type",
      ),
      disabled: !preflightAvailable,
    },
    {
      value: DOCUMENT_FIELDS[11],
      label: t(
        "portal.pipelines.builder.routing.matchPreflightPreFailingChecks",
        "Preflight error type before fixups",
      ),
      disabled: !preflightFixAvailable,
    },
    {
      value: DOCUMENT_FIELDS[12],
      label: t(
        "portal.pipelines.builder.routing.matchPreflightFixupsApplied",
        "Applied fixup",
      ),
      disabled: !preflightFixAvailable,
    },
  ];

  const listChoices = CHECK_LIST_FIELDS.has(field)
    ? PREFLIGHT_CHECK_IDS
    : field === "report.preflight.fixupsApplied"
      ? PREFLIGHT_FIXUP_IDS
      : null;

  function changeField(next: string | null) {
    if (!next) return;
    onChange(
      next === "classification.labels"
        ? classificationCondition()
        : documentFieldCondition(next),
    );
  }

  return (
    <div className="portal-routing__condition">
      <Select
        inputSize="sm"
        aria-label={t("portal.pipelines.builder.routing.matchBy", "Match by")}
        value={field}
        onChange={changeField}
        options={options}
        comboboxProps={{ withinPortal: true }}
      />
      {classification ? (
        <ClassificationConditionEditor
          condition={condition}
          onChange={onChange}
          disabled={!classificationAvailable}
        />
      ) : field === "report.preflight.verdict" ? (
        <Select
          inputSize="sm"
          aria-label={t(
            "portal.pipelines.builder.routing.matchValues",
            "Values to match",
          )}
          value={condition.values[0] ?? null}
          onChange={(value) =>
            onChange({ ...condition, values: value ? [value] : [] })
          }
          options={PREFLIGHT_VERDICTS.map((verdict) => ({
            value: verdict,
            label: t(`printPreflight.verdict.${verdict}`, verdict),
          }))}
          comboboxProps={{ withinPortal: true }}
        />
      ) : listChoices ? (
        <MultiSelect
          inputSize="sm"
          aria-label={t(
            "portal.pipelines.builder.routing.matchValues",
            "Values to match",
          )}
          placeholder={
            condition.values.length === 0
              ? t(
                  "portal.pipelines.builder.routing.codePlaceholder",
                  "Choose codes",
                )
              : undefined
          }
          data={[...listChoices]}
          value={condition.values}
          onChange={(values) => onChange({ ...condition, values })}
          invalid={condition.values.length === 0}
          searchable
          clearable
          maxDropdownHeight={280}
          comboboxProps={{ withinPortal: true }}
        />
      ) : (
        <Input
          inputSize="sm"
          aria-label={t(
            "portal.pipelines.builder.routing.matchValues",
            "Values to match",
          )}
          placeholder={
            field === "document.extension"
              ? t(
                  "portal.pipelines.builder.routing.extensionPlaceholder",
                  "pdf, docx, png",
                )
              : t(
                  "portal.pipelines.builder.routing.valuePlaceholder",
                  "Enter exact values, separated by commas",
                )
          }
          value={condition.values.join(", ")}
          invalid={condition.values.every((value) => value.trim() === "")}
          onChange={(event) =>
            onChange({
              ...condition,
              values: event.target.value
                .split(",")
                .map((value) => value.trim()),
            })
          }
        />
      )}
    </div>
  );
}
