import { useTranslation } from "react-i18next";
import { Input, MultiSelect, Select } from "@app/ui";
import { ClassificationConditionEditor } from "@app/components/conditions/ClassificationConditionEditor";
import {
  PREFLIGHT_CHECK_IDS,
  PREFLIGHT_FIXUP_IDS,
} from "@app/data/preflightCatalog";
import {
  REPORT_PRODUCERS,
  reportFieldByPath,
  reportFieldServed,
  type ReportAvailability,
} from "@app/data/reportCatalog";
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
  /**
   * Which report namespaces upstream steps emit — fields of absent producers
   * (or of an analysis-only producer when a fix variant is required) stay
   * disabled. Keyed by producer namespace, per the report catalog.
   */
  reportAvailability?: ReportAvailability;
}

const CLASSIFICATION_FIELD = "classification.labels";
const DOCUMENT_FIELDS = [
  "document.extension",
  "document.filename",
  "document.title",
  "document.author",
] as const;

/** Edits either an AI classification match or a deterministic document-fact match. */
export function RoutingConditionEditor({
  condition,
  onChange,
  classificationAvailable,
  reportAvailability = {},
}: RoutingConditionEditorProps) {
  const { t } = useTranslation();
  const field = condition.input.field;
  const classification = requiresClassification(condition);
  const options = [
    {
      value: CLASSIFICATION_FIELD,
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
      value: DOCUMENT_FIELDS[0],
      label: t(
        "portal.pipelines.builder.routing.matchExtension",
        "File extension",
      ),
    },
    {
      value: DOCUMENT_FIELDS[1],
      label: t(
        "portal.pipelines.builder.routing.matchFilename",
        "Exact filename",
      ),
    },
    {
      value: DOCUMENT_FIELDS[2],
      label: t("portal.pipelines.builder.routing.matchTitle", "PDF title"),
    },
    {
      value: DOCUMENT_FIELDS[3],
      label: t("portal.pipelines.builder.routing.matchAuthor", "PDF author"),
    },
    // Report fields come from the catalog: each producer's declared fields,
    // enabled only when the matching step variant can emit them upstream.
    ...REPORT_PRODUCERS.flatMap((producer) =>
      producer.fields.map((reportField) => ({
        value: reportField.path,
        label: t(reportField.labelKey, reportField.labelDefault),
        disabled: !reportFieldServed(reportField, reportAvailability),
      })),
    ),
  ];

  const descriptor = reportFieldByPath(field);
  const listChoices =
    descriptor?.vocabulary === "checks"
      ? PREFLIGHT_CHECK_IDS
      : descriptor?.vocabulary === "fixups"
        ? PREFLIGHT_FIXUP_IDS
        : null;

  function changeField(next: string | null) {
    if (!next) return;
    onChange(
      next === CLASSIFICATION_FIELD
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
      ) : descriptor?.kind === "enum" ? (
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
          options={(descriptor.values ?? []).map((value) => ({
            value,
            label: t(
              `${descriptor.valueLabelPrefix ?? descriptor.path}.${value}`,
              value,
            ),
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
