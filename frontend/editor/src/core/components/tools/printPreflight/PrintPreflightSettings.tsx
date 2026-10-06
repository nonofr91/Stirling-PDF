import { useState } from "react";
import {
  Stack,
  Text,
  Checkbox,
  NumberInput,
  TextInput,
  Collapse,
  Divider,
  MultiSelect,
  Select,
  Group,
  Modal,
} from "@mantine/core";
import { Button } from "@app/ui/Button";
import { useTranslation } from "react-i18next";
import {
  PrintPreflightParameters,
  FIXUP_CODES,
} from "@app/hooks/tools/printPreflight/usePrintPreflightParameters";
import {
  usePreflightProfiles,
  profileToParameters,
} from "@app/hooks/tools/printPreflight/usePreflightProfiles";

interface PrintPreflightSettingsProps {
  parameters: PrintPreflightParameters;
  onParameterChange: <K extends keyof PrintPreflightParameters>(
    key: K,
    value: PrintPreflightParameters[K],
  ) => void;
  /** Atomic profile application; falls back to per-key updates when absent. */
  onApplyParameters?: (parameters: PrintPreflightParameters) => void;
  disabled?: boolean;
}

const PrintPreflightSettings = ({
  parameters,
  onParameterChange,
  onApplyParameters,
  disabled = false,
}: PrintPreflightSettingsProps) => {
  const { t } = useTranslation();
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [fixesOpen, setFixesOpen] = useState(false);
  const { profiles, loading, error, saveProfile, deleteProfile } =
    usePreflightProfiles();
  const [activeProfile, setActiveProfile] = useState<string | null>(null);
  const [saveModalOpen, setSaveModalOpen] = useState(false);
  const [saveName, setSaveName] = useState("");
  const [saveDescription, setSaveDescription] = useState("");
  const [saving, setSaving] = useState(false);

  const selectedProfile = profiles.find((p) => p.name === activeProfile);

  const optionalNumber = (
    value: string | number | undefined,
  ): number | undefined =>
    value === "" || value === undefined ? undefined : Number(value);

  const applyProfile = (name: string | null) => {
    setActiveProfile(name);
    const profile = profiles.find((p) => p.name === name);
    if (!profile) {
      return;
    }
    const next = profileToParameters(profile, parameters);
    if (onApplyParameters) {
      onApplyParameters(next);
    } else {
      (Object.keys(next) as (keyof PrintPreflightParameters)[]).forEach(
        (key) => {
          onParameterChange(key, next[key]);
        },
      );
    }
  };

  const handleSave = async () => {
    setSaving(true);
    const ok = await saveProfile(saveName.trim(), saveDescription, parameters);
    setSaving(false);
    if (ok) {
      setActiveProfile(saveName.trim());
      setSaveModalOpen(false);
      setSaveName("");
      setSaveDescription("");
    }
  };

  const handleDelete = async () => {
    if (activeProfile && (await deleteProfile(activeProfile))) {
      setActiveProfile(null);
    }
  };

  return (
    <Stack gap="md">
      <Select
        label={t("printPreflight.profiles.label", "Preflight profile")}
        description={t(
          "printPreflight.profiles.help",
          "A saved set of thresholds and fixes. Selecting one replaces every value below.",
        )}
        placeholder={t("printPreflight.profiles.custom", "Custom settings")}
        data={profiles.map((p) => ({
          value: p.name,
          label: p.builtin
            ? t(`printPreflight.profiles.builtin.${p.name}`, p.name)
            : p.name,
        }))}
        value={activeProfile}
        onChange={applyProfile}
        clearable
        searchable
        disabled={disabled || loading}
        comboboxProps={{ withinPortal: true }}
      />

      {selectedProfile && (
        <Text size="xs" c="dimmed">
          {selectedProfile.builtin
            ? t(
                `printPreflight.profiles.builtinDesc.${selectedProfile.name}`,
                selectedProfile.description ?? "",
              )
            : selectedProfile.description}
        </Text>
      )}

      <Group gap="xs">
        <Button
          variant="tertiary"
          size="sm"
          onClick={() => {
            setSaveName(activeProfile ?? "");
            setSaveDescription(selectedProfile?.description ?? "");
            setSaveModalOpen(true);
          }}
          disabled={disabled}
        >
          {t("printPreflight.profiles.saveAs", "Save as profile…")}
        </Button>
        {selectedProfile && !selectedProfile.builtin && (
          <Button
            variant="tertiary"
            size="sm"
            accent="danger"
            onClick={handleDelete}
            disabled={disabled}
          >
            {t("printPreflight.profiles.delete", "Delete profile")}
          </Button>
        )}
      </Group>

      {error && (
        <Text size="xs" c="red">
          {t(`printPreflight.${error}`, error)}
        </Text>
      )}

      <Modal
        opened={saveModalOpen}
        onClose={() => setSaveModalOpen(false)}
        title={t("printPreflight.profiles.saveModalTitle", "Save profile")}
        centered
      >
        <Stack gap="md">
          <TextInput
            label={t("printPreflight.profiles.nameLabel", "Profile name")}
            value={saveName}
            onChange={(event) => setSaveName(event.currentTarget.value)}
            required
            maxLength={100}
            data-autofocus
          />
          <TextInput
            label={t(
              "printPreflight.profiles.descLabel",
              "Description (optional)",
            )}
            value={saveDescription}
            onChange={(event) => setSaveDescription(event.currentTarget.value)}
          />
          <Group justify="flex-end">
            <Button
              variant="tertiary"
              onClick={() => setSaveModalOpen(false)}
              disabled={saving}
            >
              {t("cancel", "Cancel")}
            </Button>
            <Button
              onClick={handleSave}
              loading={saving}
              disabled={saveName.trim() === ""}
            >
              {t("save", "Save")}
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Divider />

      <NumberInput
        label={t("printPreflight.requiredBleedMm.label", "Required bleed (mm)")}
        description={t(
          "printPreflight.requiredBleedMm.help",
          "Bleed width expected on every side past the trim. 3 mm is standard in Europe.",
        )}
        placeholder="3"
        min={0}
        decimalScale={1}
        value={parameters.requiredBleedMm ?? undefined}
        onChange={(value) =>
          onParameterChange(
            "requiredBleedMm",
            value === "" || value === undefined ? undefined : Number(value),
          )
        }
        disabled={disabled}
      />

      <NumberInput
        label={t(
          "printPreflight.minImageDpi.label",
          "Minimum image resolution (dpi)",
        )}
        description={t(
          "printPreflight.minImageDpi.help",
          "Images placed below this effective resolution are flagged. 300 is ideal for offset; 150 is a common minimum.",
        )}
        placeholder="150"
        min={1}
        value={parameters.minImageDpi ?? undefined}
        onChange={(value) =>
          onParameterChange(
            "minImageDpi",
            value === "" || value === undefined ? undefined : Number(value),
          )
        }
        disabled={disabled}
      />

      <NumberInput
        label={t(
          "printPreflight.hairlineThresholdPt.label",
          "Hairline threshold (pt)",
        )}
        description={t(
          "printPreflight.hairlineThresholdPt.help",
          "Strokes thinner than this are flagged — they may drop out on press.",
        )}
        placeholder="0.25"
        min={0}
        decimalScale={2}
        value={parameters.hairlineThresholdPt ?? undefined}
        onChange={(value) =>
          onParameterChange(
            "hairlineThresholdPt",
            value === "" || value === undefined ? undefined : Number(value),
          )
        }
        disabled={disabled}
      />

      <Checkbox
        checked={parameters.checkBleedCoverage}
        onChange={(event) =>
          onParameterChange("checkBleedCoverage", event.currentTarget.checked)
        }
        disabled={disabled}
        label={
          <div>
            <Text size="sm">
              {t(
                "printPreflight.checkBleedCoverage.label",
                "Check bleed is painted",
              )}
            </Text>
            <Text size="xs" c="dimmed">
              {t(
                "printPreflight.checkBleedCoverage.desc",
                "Render each page to verify the bleed area is actually covered by content, not just declared.",
              )}
            </Text>
          </div>
        }
      />

      <Checkbox
        checked={parameters.includeSummaryPage}
        onChange={(event) =>
          onParameterChange("includeSummaryPage", event.currentTarget.checked)
        }
        disabled={disabled}
        label={
          <div>
            <Text size="sm">
              {t(
                "printPreflight.includeSummaryPage.label",
                "Summary page in annotated PDF",
              )}
            </Text>
            <Text size="xs" c="dimmed">
              {t(
                "printPreflight.includeSummaryPage.desc",
                "Prepend the report summary — verdict, facts, fonts, colours — to the annotated PDF copy.",
              )}
            </Text>
          </div>
        }
      />

      <Divider />

      <Stack gap="sm">
        <Button
          variant="tertiary"
          onClick={() => setAdvancedOpen(!advancedOpen)}
          disabled={disabled}
        >
          {t("printPreflight.advanced.toggle", "Advanced thresholds")}{" "}
          {advancedOpen ? "▲" : "▼"}
        </Button>

        <Collapse in={advancedOpen}>
          <Stack gap="md" mt="md">
            <NumberInput
              label={t(
                "printPreflight.safetyMarginMm.label",
                "Safety margin inside trim (mm)",
              )}
              description={t(
                "printPreflight.safetyMarginMm.help",
                "Content closer than this to the trim edge is reported — it may be cut off on the guillotine.",
              )}
              placeholder="3"
              min={0}
              decimalScale={1}
              value={parameters.safetyMarginMm ?? undefined}
              onChange={(value) =>
                onParameterChange("safetyMarginMm", optionalNumber(value))
              }
              disabled={disabled}
            />

            <NumberInput
              label={t(
                "printPreflight.minFontSizePt.label",
                "Minimum font size (pt)",
              )}
              description={t(
                "printPreflight.minFontSizePt.help",
                "Text smaller than this is reported as too small to print reliably.",
              )}
              placeholder="5"
              min={0}
              decimalScale={1}
              value={parameters.minFontSizePt ?? undefined}
              onChange={(value) =>
                onParameterChange("minFontSizePt", optionalNumber(value))
              }
              disabled={disabled}
            />

            <NumberInput
              label={t(
                "printPreflight.maxInkCoveragePercent.label",
                "Max ink coverage (%)",
              )}
              description={t(
                "printPreflight.maxInkCoveragePercent.help",
                "Painted colours above this total area coverage are reported — ink drying and registration issues. ~320% is a common offset limit.",
              )}
              placeholder="320"
              min={0}
              value={parameters.maxInkCoveragePercent ?? undefined}
              onChange={(value) =>
                onParameterChange(
                  "maxInkCoveragePercent",
                  optionalNumber(value),
                )
              }
              disabled={disabled}
            />

            <NumberInput
              label={t(
                "printPreflight.minImage1BitDpi.label",
                "Minimum 1-bit image resolution (dpi)",
              )}
              description={t(
                "printPreflight.minImage1BitDpi.help",
                "Line art / bitmap images need far more resolution than photos — 1200 dpi is the usual floor.",
              )}
              placeholder="1200"
              min={1}
              value={parameters.minImage1BitDpi ?? undefined}
              onChange={(value) =>
                onParameterChange("minImage1BitDpi", optionalNumber(value))
              }
              disabled={disabled}
            />

            <NumberInput
              label={t(
                "printPreflight.maxImageDpi.label",
                "Maximum image resolution (dpi)",
              )}
              description={t(
                "printPreflight.maxImageDpi.help",
                "Images rendered above this effective resolution are flagged as oversampled — dead weight the press cannot use.",
              )}
              placeholder="600"
              min={1}
              value={parameters.maxImageDpi ?? undefined}
              onChange={(value) =>
                onParameterChange("maxImageDpi", optionalNumber(value))
              }
              disabled={disabled}
            />

            <NumberInput
              label={t(
                "printPreflight.maxSpotCount.label",
                "Maximum spot colours",
              )}
              description={t(
                "printPreflight.maxSpotCount.help",
                "Warn when more spot separations than this are used — each plate costs makeready. 0 disables the limit.",
              )}
              placeholder="0"
              min={0}
              value={parameters.maxSpotCount ?? undefined}
              onChange={(value) =>
                onParameterChange("maxSpotCount", optionalNumber(value))
              }
              disabled={disabled}
            />

            <TextInput
              label={t(
                "printPreflight.disabledChecks.label",
                "Disabled checks (codes, comma-separated)",
              )}
              description={t(
                "printPreflight.disabledChecks.help",
                "Skip named checks entirely, e.g. SAFETY_MARGIN, INK_COVERAGE_HIGH. Leave empty to run everything.",
              )}
              placeholder="SAFETY_MARGIN, INK_COVERAGE_HIGH"
              value={(parameters.disabledChecks ?? []).join(", ")}
              onChange={(event) => {
                const raw = event.currentTarget.value;
                onParameterChange(
                  "disabledChecks",
                  raw.trim() === ""
                    ? undefined
                    : raw
                        .split(",")
                        .map((s) => s.trim())
                        .filter(Boolean),
                );
              }}
              disabled={disabled}
            />
          </Stack>
        </Collapse>
      </Stack>

      <Divider />

      <Stack gap="sm">
        <Button
          variant="tertiary"
          onClick={() => setFixesOpen(!fixesOpen)}
          disabled={disabled}
        >
          {t("printPreflight.fixes.toggle", "Automatic fixes")}{" "}
          {fixesOpen ? "▲" : "▼"}
        </Button>

        <Collapse in={fixesOpen}>
          <Stack gap="md" mt="md">
            <Text size="xs" c="dimmed">
              {t(
                "printPreflight.fixes.help",
                "Corrections applied when producing a Fixed PDF. The annotated and JSON reports are always analysis-only.",
              )}
            </Text>
            <MultiSelect
              label={t("printPreflight.fixups.label", "Fixups to apply")}
              description={t(
                "printPreflight.fixups.help",
                "Leave empty to apply every fixup that has something to correct.",
              )}
              placeholder={t(
                "printPreflight.fixups.placeholder",
                "All applicable fixups",
              )}
              data={[
                {
                  value: "NONE",
                  label: t(
                    "printPreflight.fixups.codes.NONE",
                    "None — disable all fixups",
                  ),
                },
                ...FIXUP_CODES.map((code) => ({
                  value: code,
                  label: t(`printPreflight.fixups.codes.${code}`, code),
                })),
              ]}
              value={parameters.fixups ?? []}
              onChange={(value) =>
                onParameterChange(
                  "fixups",
                  value.length === 0 ? undefined : value,
                )
              }
              searchable
              disabled={disabled}
              comboboxProps={{ withinPortal: true }}
            />
          </Stack>
        </Collapse>
      </Stack>
    </Stack>
  );
};

export default PrintPreflightSettings;
