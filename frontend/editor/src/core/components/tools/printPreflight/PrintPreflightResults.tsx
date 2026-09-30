import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Accordion,
  Alert,
  Badge,
  Group,
  Loader,
  Stack,
  Text,
  Tooltip,
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
import { useViewScopedFiles } from "@app/hooks/tools/shared/useViewScopedFiles";
import { useViewer } from "@app/contexts/ViewerContext";
import {
  useSetPageOverlay,
  type PageOverlayRect,
} from "@app/contexts/PageOverlayContext";
import { getFormFillFileId } from "@app/types/fileContext";
import {
  pdfRectToPageFractions,
  readPageBoxSnapshots,
  type PageBoxSnapshot,
} from "@app/utils/pageBoxReader";

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

/** Border + label colour of a finding's overlay rect, keyed by severity. */
const severityStroke = (severity: PreflightSeverity): string => {
  switch (severity) {
    case "ERROR":
      return "var(--mantine-color-red-7)";
    case "WARNING":
      return "var(--mantine-color-orange-7)";
    case "INFO":
      return "var(--mantine-color-blue-6)";
  }
};

const severityRank = (severity: PreflightSeverity): number =>
  severity === "ERROR" ? 0 : severity === "WARNING" ? 1 : 2;

const severityAccent = (
  severity: PreflightSeverity,
): "danger" | "warning" | "default" => {
  switch (severity) {
    case "ERROR":
      return "danger";
    case "WARNING":
      return "warning";
    case "INFO":
      return "default";
  }
};

const FindingRow = ({
  finding,
  located,
  onLocate,
}: {
  finding: PreflightFinding;
  located: boolean;
  onLocate?: () => void;
}) => {
  const { t } = useTranslation();
  const hasAreas = (finding.areas?.length ?? 0) > 0;
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
        {finding.areasTruncated && (
          <Text size="xs" c="dimmed">
            {t(
              "printPreflight.areasTruncated",
              "Additional locations omitted from the preview",
            )}
          </Text>
        )}
      </Stack>
      {hasAreas && onLocate && (
        <Tooltip
          label={t("printPreflight.locate", "Show on document")}
          withArrow
        >
          <Button
            variant={located ? "primary" : "tertiary"}
            accent={severityAccent(finding.severity)}
            size="sm"
            aria-label={t("printPreflight.locate", "Show on document")}
            leftSection={<Icon name="locate-fixed" size={14} />}
            onClick={onLocate}
          />
        </Tooltip>
      )}
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
  const [selectedFile = null] = useViewScopedFiles();
  const { scrollActions } = useViewer();
  const setOverlay = useSetPageOverlay();
  const [snapshots, setSnapshots] = useState<(PageBoxSnapshot | null)[] | null>(
    null,
  );
  // Index into the sorted findings of the report currently on screen; null =
  // every located finding is drawn, an index emphasizes just that one.
  const [located, setLocated] = useState<number | null>(null);

  const jsonFile = useMemo(
    () =>
      operation.files.find((file) =>
        file.name.toLowerCase().endsWith(".json"),
      ) ?? null,
    [operation.files],
  );

  // The overlay belongs to the viewed file's report — painting another file's
  // findings on it would mislead (results outlive a file switch by one change).
  const overlayReport =
    operation.results.find(
      (e) => !e.error && e.report && e.fileId === selectedFile?.fileId,
    )?.report ?? null;

  useEffect(() => {
    let cancelled = false;
    setLocated(null);
    if (
      !selectedFile ||
      !overlayReport?.findings.some((f) => f.areas?.length)
    ) {
      setSnapshots(null);
      return;
    }
    readPageBoxSnapshots(selectedFile).then((s) => {
      if (!cancelled) setSnapshots(s);
    });
    return () => {
      cancelled = true;
    };
  }, [selectedFile, overlayReport]);

  // Paint the findings' areas over the viewer pages — one rect per area,
  // colored by severity; the located finding's rects get the emphasis style.
  useEffect(() => {
    const documentKey = selectedFile ? getFormFillFileId(selectedFile) : null;
    if (!documentKey || !snapshots || !overlayReport) {
      setOverlay(null);
      return;
    }
    const sorted = [...overlayReport.findings].sort(
      (a, b) => severityRank(a.severity) - severityRank(b.severity),
    );
    setOverlay({
      documentKey,
      rects: [],
      rectsPerPage: snapshots.map((pageSnapshot, pageIndex) => {
        if (!pageSnapshot) return [];
        const out: PageOverlayRect[] = [];
        for (let i = 0; i < sorted.length; i++) {
          const finding = sorted[i];
          for (const area of finding.areas ?? []) {
            if (area.page !== pageIndex + 1) continue;
            out.push({
              ...pdfRectToPageFractions(area, pageSnapshot.boxes.CROP_BOX),
              color: severityStroke(finding.severity),
              dashed: finding.severity === "INFO",
              emphasized: located === i,
              label: area.label ?? finding.code,
            });
          }
        }
        return out;
      }),
    });
    return () => setOverlay(null);
  }, [selectedFile, snapshots, overlayReport, located, setOverlay]);

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
                {report === overlayReport &&
                  snapshots &&
                  sorted.some((f) => (f.areas?.length ?? 0) > 0) && (
                    <Text size="xs" c="dimmed">
                      {t(
                        "printPreflight.locatedHint",
                        "Located issues are framed on the document — use the target button to isolate one.",
                      )}
                    </Text>
                  )}
                {sorted.map((finding, idx) => (
                  <FindingRow
                    key={finding.code + idx}
                    finding={finding}
                    located={report === overlayReport && located === idx}
                    onLocate={
                      report === overlayReport
                        ? () => {
                            const next = located === idx ? null : idx;
                            setLocated(next);
                            const page = finding.areas?.[0]?.page;
                            if (next !== null && page) {
                              scrollActions.scrollToPage(page);
                            }
                          }
                        : undefined
                    }
                  />
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

      <Group grow wrap="nowrap">
        {operation.results
          .filter((entry) => !entry.error && entry.report)
          .map((entry) => (
            <Button
              key={entry.fileId}
              variant="secondary"
              loading={operation.annotatedLoading === entry.fileId}
              disabled={
                operation.annotatedLoading != null &&
                operation.annotatedLoading !== entry.fileId
              }
              onClick={() => void operation.downloadAnnotated(entry.fileId)}
            >
              {t("printPreflight.downloadAnnotated", "Annotated PDF")}
            </Button>
          ))}
        {jsonFile && (
          <Button onClick={() => handleDownload(jsonFile)}>
            {t("printPreflight.downloadJson", "Download report (JSON)")}
          </Button>
        )}
      </Group>
    </Stack>
  );
};

export default PrintPreflightResults;
