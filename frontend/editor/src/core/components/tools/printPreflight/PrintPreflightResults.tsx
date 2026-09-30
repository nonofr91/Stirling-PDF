import { useCallback, useMemo } from "react";
import {
  Accordion,
  Alert,
  Badge,
  Group,
  Loader,
  Stack,
  Text,
} from "@mantine/core";
import { Button } from "@app/ui/Button";
import { Icon } from "@app/ui/Icon";
import { useTranslation } from "react-i18next";
import type { PrintPreflightOperationHook } from "@app/hooks/tools/printPreflight/usePrintPreflightOperation";
import type {
  PreflightFinding,
  PreflightSeverity,
} from "@app/types/printPreflight";
import { downloadFile } from "@app/services/downloadService";

interface PrintPreflightResultsProps {
  operation: PrintPreflightOperationHook;
  isLoading: boolean;
  errorMessage: string | null;
}

const severityColor = (severity: PreflightSeverity): string => {
  switch (severity) {
    case "ERROR":
      return "red";
    case "WARNING":
      return "yellow";
    case "INFO":
      return "blue";
  }
};

const severityRank = (severity: PreflightSeverity): number =>
  severity === "ERROR" ? 0 : severity === "WARNING" ? 1 : 2;

const FindingRow = ({ finding }: { finding: PreflightFinding }) => {
  const { t } = useTranslation();
  return (
    <Group gap="sm" align="flex-start" wrap="nowrap">
      <Badge color={severityColor(finding.severity)} variant="light" mt={2}>
        {t(`printPreflight.severity.${finding.severity}`, finding.severity)}
      </Badge>
      <Stack gap={2} style={{ flex: 1 }}>
        <Text size="sm">{finding.message}</Text>
        {finding.pages && finding.pages.length > 0 && (
          <Text size="xs" c="dimmed">
            {t("printPreflight.pages", "Pages")}:{" "}
            {finding.pages.slice(0, 20).join(", ")}
            {finding.pages.length > 20 && "…"}
          </Text>
        )}
      </Stack>
      <Text size="xs" c="dimmed" ff="monospace">
        {t(`printPreflight.category.${finding.category}`, finding.category)}
      </Text>
    </Group>
  );
};

const PrintPreflightResults = ({
  operation,
  isLoading,
  errorMessage,
}: PrintPreflightResultsProps) => {
  const { t } = useTranslation();

  const jsonFile = useMemo(
    () =>
      operation.files.find((file) =>
        file.name.toLowerCase().endsWith(".json"),
      ) ?? null,
    [operation.files],
  );

  const handleDownload = useCallback((file: File) => {
    void downloadFile({ data: file, filename: file.name });
  }, []);

  if (isLoading && operation.results.length === 0) {
    return (
      <Group justify="center" gap="sm" py="md">
        <Loader size="sm" />
        <Text>
          {t("printPreflight.processing", "Running preflight checks...")}
        </Text>
      </Group>
    );
  }

  if (!isLoading && operation.results.length === 0) {
    return (
      <Alert
        color="gray"
        variant="light"
        title={t("printPreflight.results", "Results")}
      >
        <Text size="sm">
          {t(
            "printPreflight.noResults",
            "Run the tool to generate a preflight report.",
          )}
        </Text>
      </Alert>
    );
  }

  return (
    <Stack gap="md">
      {errorMessage && (
        <Alert color="yellow" variant="light">
          <Text size="sm">{errorMessage}</Text>
        </Alert>
      )}

      {operation.results.map((entry) => {
        if (entry.error || !entry.report) {
          return (
            <Alert
              key={entry.fileId}
              color="red"
              variant="light"
              title={entry.fileName}
            >
              <Text size="sm">
                {entry.error ??
                  t("printPreflight.error.noReport", "No report returned.")}
              </Text>
            </Alert>
          );
        }
        const report = entry.report;
        const verdict =
          report.counts.errors > 0
            ? "red"
            : report.counts.warnings > 0
              ? "yellow"
              : "green";
        const verdictText =
          report.counts.errors > 0
            ? t(
                "printPreflight.verdict.errors",
                "{{errors}} blocking issue(s) found",
                { errors: report.counts.errors },
              )
            : report.counts.warnings > 0
              ? t(
                  "printPreflight.verdict.warnings",
                  "Printable with {{warnings}} warning(s)",
                  { warnings: report.counts.warnings },
                )
              : t("printPreflight.verdict.clean", "Ready for print");
        const sorted = [...report.findings].sort(
          (a, b) => severityRank(a.severity) - severityRank(b.severity),
        );
        return (
          <Stack key={entry.fileId} gap="md">
            <Alert
              color={verdict}
              variant="light"
              title={verdictText}
              icon={<Icon name="printer" size={20} />}
            >
              <Text size="sm">
                {t(
                  "printPreflight.counts",
                  "{{errors}} errors · {{warnings}} warnings · {{infos}} info",
                  {
                    errors: report.counts.errors,
                    warnings: report.counts.warnings,
                    infos: report.counts.infos,
                  },
                )}
              </Text>
            </Alert>

            {sorted.length > 0 && (
              <Stack gap="sm">
                {sorted.map((finding, idx) => (
                  <FindingRow key={finding.code + idx} finding={finding} />
                ))}
              </Stack>
            )}

            <Accordion variant="separated">
              <Accordion.Item value="facts">
                <Accordion.Control>
                  {t("printPreflight.facts.title", "Document facts")}
                </Accordion.Control>
                <Accordion.Panel>
                  <Stack gap="xs">
                    <Text size="sm">
                      <strong>
                        {t("printPreflight.facts.pageCount", "Pages")}:
                      </strong>{" "}
                      {report.pageCount} · <strong>PDF</strong>{" "}
                      {report.pdfVersion} ·{" "}
                      {(report.fileSizeBytes / 1024).toFixed(0)} KB
                    </Text>
                    {report.facts.fonts.length > 0 && (
                      <Stack gap={2}>
                        <Text size="sm" fw={600}>
                          {t("printPreflight.facts.fonts", "Fonts")}
                        </Text>
                        {report.facts.fonts.map((font) => (
                          <Group key={font.name + font.subType} gap="xs">
                            <Icon
                              name={font.embedded ? "badge-check" : "x"}
                              size={14}
                            />
                            <Text size="xs">
                              {font.name} ({font.subType})
                              {font.type3 &&
                                " · " +
                                  t("printPreflight.facts.type3", "Type 3")}
                            </Text>
                          </Group>
                        ))}
                      </Stack>
                    )}
                    {report.facts.colorSpaces.length > 0 && (
                      <Stack gap={2}>
                        <Text size="sm" fw={600}>
                          {t("printPreflight.facts.colors", "Color spaces")}
                        </Text>
                        <Group gap="xs">
                          {report.facts.colorSpaces.map((cs) => (
                            <Badge key={cs} variant="outline" size="sm">
                              {cs}
                            </Badge>
                          ))}
                        </Group>
                      </Stack>
                    )}
                    <Text size="sm">
                      <strong>
                        {t("printPreflight.facts.images", "Images")}:
                      </strong>{" "}
                      {report.facts.imageCount}
                      {report.facts.lowResImageCount > 0 &&
                        " (" +
                          t(
                            "printPreflight.facts.lowRes",
                            "{{count}} below threshold",
                            { count: report.facts.lowResImageCount },
                          ) +
                          ")"}
                    </Text>
                    <Text size="sm">
                      <strong>
                        {t("printPreflight.facts.boxes", "Page boxes")}:
                      </strong>{" "}
                      {report.facts.hasTrimBox ? "TrimBox" : "—"} ·{" "}
                      {report.facts.hasBleedBox ? "BleedBox" : "—"}
                    </Text>
                  </Stack>
                </Accordion.Panel>
              </Accordion.Item>
            </Accordion>
          </Stack>
        );
      })}

      {jsonFile && (
        <Button onClick={() => handleDownload(jsonFile)} fullWidth>
          {t("printPreflight.downloadJson", "Download report (JSON)")}
        </Button>
      )}
    </Stack>
  );
};

export default PrintPreflightResults;
