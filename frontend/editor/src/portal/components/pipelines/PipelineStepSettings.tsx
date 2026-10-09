import { Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Icon } from "@app/ui/Icon";
import { Banner, Chip, ToggleSwitch } from "@app/ui";
import { PreferencesProvider } from "@app/contexts/PreferencesContext";
import { SidebarProvider } from "@app/contexts/SidebarContext";
import { type ToolRegistry } from "@app/data/toolsTaxonomy";
import { type ErasedToolParams } from "@app/hooks/tools/shared/toolOperationTypes";
import {
  activeFileFields,
  assetRefIds,
  extractStepFiles,
  type WorkingToolStep,
} from "@app/hooks/tools/shared/toolAutomation";

import { RoutingConditionEditor } from "@app/components/conditions/RoutingConditionEditor";
import { documentFieldCondition } from "@app/data/classificationConditions";
import type { MatchesAnyCondition } from "@app/conditions/types";
import { PolicyExternalApiConfig } from "@portal/components/policies/PolicyExternalApiConfig";
import { isIntegrationStep } from "@portal/components/pipelines/integrationStep";
import { isIngestStep } from "@portal/components/pipelines/docparseStep";
import { IngestStepConfig } from "@portal/components/pipelines/IngestStepConfig";
import type { ExternalApiStepParams } from "@portal/components/policies/stepOperations";
import "@portal/components/pipelines/PipelineStepSettings.css";

/**
 * A params update: the next params outright, or a merge from the latest params. Settings UIs fire
 * several single-field changes synchronously (e.g. convert's source-format change also resets the
 * target and options); the merge form lets them accumulate against current state instead of each
 * rebuilding from the `step` snapshot captured at render, which would clobber the earlier fields.
 */
export type ParamsUpdate =
  | ErasedToolParams
  | ((prev: ErasedToolParams) => ErasedToolParams);

interface PipelineStepSettingsProps {
  step: WorkingToolStep;
  editorInput?: boolean;
  registry: Partial<ToolRegistry>;
  onChange: (update: ParamsUpdate) => void;
  /** Stored asset id -> file name, for labelling the supporting-file chips on a reopened pipeline. */
  assetNames: Record<string, string>;
  /** Drop a field's stored supporting-file binding (the user re-picks a file if the step still needs one). */
  onClearBinding: (field: string) => void;
  /** The step's per-document gate, or undefined when it always runs. */
  when?: MatchesAnyCondition;
  onWhenChange: (when: MatchesAnyCondition | undefined) => void;
  /** `classification.*` facts exist only if a classify step ran before this one. */
  classificationAvailable?: boolean;
  /** `report.preflight.*` facts exist only if a preflight step ran before this one. */
  preflightAvailable?: boolean;
  /** `report.preflight` pre-fixup and fixupsApplied facts exist only if a fix step ran before. */
  preflightFixAvailable?: boolean;
}

/** One reopened supporting file shown as a chip: the field it binds and the stored file name(s). */
interface StoredFileChip {
  field: string;
  label: string;
}

/**
 * The supporting files this step is reusing from a previous save: an active binding whose field has
 * no fresh pick (a fresh pick shows in the tool's own file picker instead). Labelled by the resolved
 * asset name so the user sees "using cert.pfx" rather than an empty picker.
 */
function storedFileChips(
  step: WorkingToolStep,
  registry: Partial<ToolRegistry>,
  assetNames: Record<string, string>,
): StoredFileChip[] {
  const bindings = step.fileParameters;
  if (!bindings) return [];
  // A null active set means the tool couldn't be probed; show every stored binding rather than hide
  // the user's files (mirrors the save path, which keeps them too).
  const active = activeFileFields(step, registry);
  const activeSet = active === null ? null : new Set(active);
  const fresh = extractStepFiles(step, registry);
  return Object.entries(bindings)
    .filter(
      ([field]) =>
        (activeSet === null || activeSet.has(field)) && !fresh[field],
    )
    .map(([field, binding]) => ({
      field,
      label:
        assetRefIds(binding)
          .map((id) => assetNames[id] ?? id)
          .join(", ") || binding,
    }));
}

/**
 * Renders the parameter editor for one pipeline step, chosen by the tool's capability:
 * the tool's own settings UI when editable, an explanatory note when it has no parameters,
 * or a "not supported yet" fallback for tools not yet migrated to the mapper seam. Reopened
 * supporting files appear as removable chips above the tool's own settings.
 */
export function PipelineStepSettings({
  editorInput = false,
  step,
  registry,
  onChange,
  assetNames,
  onClearBinding,
  when,
  onWhenChange,
  classificationAvailable = false,
  preflightAvailable = false,
  preflightFixAvailable = false,
}: PipelineStepSettingsProps) {
  // Hooks first: selecting a different step re-renders this same instance, so an early return
  // above useTranslation would change the hook count between renders and crash.
  const { t } = useTranslation();

  const gate = (
    <div className="portal-step-settings__gate">
      <ToggleSwitch
        size="sm"
        checked={when !== undefined}
        onChange={(enabled) =>
          onWhenChange(
            enabled ? documentFieldCondition("document.extension") : undefined,
          )
        }
        label={t(
          "portal.pipelines.builder.when.toggle",
          "Run only when a document matches",
        )}
        description={t(
          "portal.pipelines.builder.when.description",
          "Matching files run through this step; the others bypass it unchanged.",
        )}
        data-testid="step-gate-toggle"
      />
      {when !== undefined && (
        <RoutingConditionEditor
          condition={when}
          onChange={onWhenChange}
          classificationAvailable={classificationAvailable}
          preflightAvailable={preflightAvailable}
          preflightFixAvailable={preflightFixAvailable}
        />
      )}
    </div>
  );

  // Same reasoning as the integration branch below: a DocParse step has no registry entry, so
  // its settings come from its own component rather than a tool's.
  if (isIngestStep(step)) {
    return (
      <>
        <IngestStepConfig
          editorInput={editorInput}
          parameters={step.params}
          onChange={(params) => onChange(params as never)}
        />
        {gate}
      </>
    );
  }

  // An integration step is configured by the operations catalogue, not by a tool's settings UI:
  // it has no registry entry to look one up from.
  if (isIntegrationStep(step)) {
    return (
      <>
        <PolicyExternalApiConfig
          parameters={step.params as unknown as ExternalApiStepParams}
          onChange={(params) => onChange(params as never)}
        />
        {gate}
      </>
    );
  }

  const chips = storedFileChips(step, registry, assetNames);

  function toolBody() {
    if (step.support === "noSettings") {
      return (
        <Banner
          tone="info"
          description={t("portal.pipelines.composer.noToolSettings")}
        />
      );
    }
    const entry = step.toolId ? registry[step.toolId] : undefined;
    const Settings =
      step.support === "editable" ? entry?.automationSettings : null;
    if (!Settings) {
      return (
        <Banner
          tone="warning"
          description={t("portal.pipelines.composer.editingUnsupported")}
        />
      );
    }
    return (
      <PreferencesProvider>
        <SidebarProvider>
          <Suspense fallback={null}>
            <Settings
              parameters={step.params}
              onParameterChange={(key, value) =>
                onChange((prev) => ({ ...prev, [key]: value }))
              }
              disabled={false}
            />
          </Suspense>
        </SidebarProvider>
      </PreferencesProvider>
    );
  }

  return (
    <>
      {chips.length > 0 && (
        <div className="portal-step-settings__files">
          <span className="portal-step-settings__files-label">
            {t("portal.pipelines.builder.supportingFiles")}
          </span>
          <div className="portal-step-settings__files-chips">
            {chips.map((chip) => (
              <Chip
                key={chip.field}
                leadingIcon={<Icon name="file" size={"0.875rem"} />}
                onRemove={() => onClearBinding(chip.field)}
              >
                {chip.label}
              </Chip>
            ))}
          </div>
        </div>
      )}
      {toolBody()}
      {gate}
    </>
  );
}
