import { Select, Stack } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { PrintPreflightParameters } from "@app/hooks/tools/printPreflight/usePrintPreflightParameters";
import PrintPreflightSettings from "@app/components/tools/printPreflight/PrintPreflightSettings";

interface PrintPreflightAutomationSettingsProps {
  parameters: PrintPreflightParameters;
  onParameterChange: <K extends keyof PrintPreflightParameters>(
    key: K,
    value: PrintPreflightParameters[K],
  ) => void;
  disabled?: boolean;
}

/**
 * Automation variant: adds the report output picker. "annotatedPdf" emits a PDF
 * so the pipeline keeps flowing; "json" emits the machine-readable report and
 * ends the chain.
 */
const PrintPreflightAutomationSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
}: PrintPreflightAutomationSettingsProps) => {
  const { t } = useTranslation();

  return (
    <Stack gap="md">
      <Select
        label={t("printPreflight.reportFormat.label", "Report format")}
        description={t(
          "printPreflight.reportFormat.help",
          "PDF outputs keep the pipeline flowing; JSON report ends the chain with the findings.",
        )}
        value={parameters.reportFormat}
        onChange={(value) => {
          if (
            value !== "annotatedPdf" &&
            value !== "reportPdf" &&
            value !== "fixedPdf" &&
            value !== "json" &&
            value !== "fixAuditJson"
          ) {
            return;
          }
          onParameterChange("reportFormat", value);
        }}
        data={[
          {
            value: "annotatedPdf",
            label: t(
              "printPreflight.reportFormat.annotatedPdf",
              "Annotated PDF",
            ),
          },
          {
            value: "reportPdf",
            label: t(
              "printPreflight.reportFormat.reportPdf",
              "Summary report PDF",
            ),
          },
          {
            value: "fixedPdf",
            label: t(
              "printPreflight.reportFormat.fixedPdf",
              "Fixed PDF (apply selected fixups)",
            ),
          },
          {
            value: "json",
            label: t("printPreflight.reportFormat.json", "JSON report"),
          },
          {
            value: "fixAuditJson",
            label: t(
              "printPreflight.reportFormat.fixAuditJson",
              "Fixup audit JSON (dry run)",
            ),
          },
        ]}
        allowDeselect={false}
        disabled={disabled}
        comboboxProps={{ withinPortal: true }}
      />
      <PrintPreflightSettings
        parameters={parameters}
        onParameterChange={onParameterChange}
        disabled={disabled}
      />
    </Stack>
  );
};

export default PrintPreflightAutomationSettings;
