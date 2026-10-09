import { Select, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { Banner } from "@app/ui";
import PrintPreflightSettings from "@app/components/tools/printPreflight/PrintPreflightSettings";
import type { PrintPreflightStepParameters } from "@app/hooks/tools/printPreflight/preflightStep";
import { usePreflightProfiles } from "@app/hooks/tools/printPreflight/usePreflightProfiles";

interface PrintPreflightStepSettingsProps {
  parameters: PrintPreflightStepParameters;
  onParameterChange: <K extends keyof PrintPreflightStepParameters>(
    key: K,
    value: PrintPreflightStepParameters[K],
  ) => void;
  disabled?: boolean;
  /** fix = step produces the corrected PDF; check = annotated copy, analysis only. */
  variant: "fix" | "check";
}

/**
 * Step-level preflight settings: a named server-side profile (the step keeps the
 * reference — editing the profile updates every pipeline that uses it), or the
 * full inline editor when none is set. While a profile is named the inline
 * values are not even sent: the backend merges profiles field-by-field, so a
 * leftover value would silently apply where the profile is silent.
 */
const PrintPreflightStepSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
  variant,
}: PrintPreflightStepSettingsProps) => {
  const { t } = useTranslation();
  const { profiles, loading, error } = usePreflightProfiles();
  const profileName = parameters.profileName ?? "";

  return (
    <Stack gap="md">
      <Select
        label={t("printPreflight.profiles.label", "Preflight profile")}
        description={t(
          "printPreflight.profiles.stepHelp",
          "A named profile supplies the thresholds, fixups and disabled checks — clear it to tune this step inline.",
        )}
        placeholder={t("printPreflight.profiles.custom", "Custom settings")}
        data={profiles.map((profile) => ({
          value: profile.name,
          label: profile.builtin
            ? t(`printPreflight.profiles.builtin.${profile.name}`, profile.name)
            : profile.name,
        }))}
        value={profileName === "" ? null : profileName}
        onChange={(value) => onParameterChange("profileName", value ?? "")}
        clearable
        searchable
        disabled={disabled || loading}
        comboboxProps={{ withinPortal: true }}
      />
      {error === "load" && (
        <Text size="xs" c="red">
          {t(
            "printPreflight.profiles.loadFailed",
            "Profiles could not be loaded",
          )}
        </Text>
      )}
      {profileName !== "" ? (
        <Banner
          tone="info"
          description={t(
            "printPreflight.profiles.suppliesStep",
            "The named profile supplies every preflight setting for this step.",
          )}
        />
      ) : (
        <PrintPreflightSettings
          parameters={parameters}
          onParameterChange={onParameterChange}
          disabled={disabled}
          hideProfiles
          hideFixups={variant === "check"}
          hideSummaryPage={variant === "fix"}
        />
      )}
    </Stack>
  );
};

export default PrintPreflightStepSettings;
