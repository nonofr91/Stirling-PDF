import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import i18n from "i18next";
import apiClient from "@app/services/apiClient";
import { downloadFile } from "@app/services/downloadService";
import {
  defineCustomTool,
  CustomProcessorResult,
  ToolOperationHook,
} from "@app/hooks/tools/shared/useToolOperation";
import type { ToolEndpoint } from "@app/types/toolApiTypes";
import type { StirlingFile } from "@app/types/fileContext";
import { extractErrorMessage } from "@app/utils/toolErrorHandler";
import {
  PreflightFixAudit,
  PrintPreflightReport,
  PREFLIGHT_JSON_FILENAME,
} from "@app/types/printPreflight";
import {
  defaultParameters,
  validatePrintPreflightParameters,
  type PrintPreflightParameters,
} from "@app/hooks/tools/printPreflight/usePrintPreflightParameters";

export interface PrintPreflightResultEntry {
  fileId: string;
  fileName: string;
  report: PrintPreflightReport | null;
  error: string | null;
}

export interface PrintPreflightOperationHook extends ToolOperationHook<PrintPreflightParameters> {
  results: PrintPreflightResultEntry[];
  /** Fetch the annotated copy for one analyzed file and trigger a download. */
  downloadAnnotated: (fileId: string) => Promise<void>;
  /** Fetch the standalone summary report PDF for one analyzed file. */
  downloadReport: (fileId: string) => Promise<void>;
  /** Fetch the auto-fixed copy for one analyzed file. */
  downloadFixed: (fileId: string) => Promise<void>;
  /**
   * Dry-run the configured fixups for one analyzed file: applies them
   * in-memory server-side and stores the before/after audit for display —
   * nothing is downloaded and the source document is untouched.
   */
  previewFixes: (fileId: string) => Promise<void>;
  /** Fixup audit per file, populated by previewFixes. */
  fixAudits: Record<string, PreflightFixAudit>;
  annotatedLoading: string | null;
  reportLoading: string | null;
  fixedLoading: string | null;
  previewLoading: string | null;
}

const PREFLIGHT_ENDPOINT =
  "/api/v1/security/print-preflight" satisfies ToolEndpoint;
const PREFLIGHT_ANNOTATED_ENDPOINT =
  "/api/v1/security/print-preflight-annotated" satisfies ToolEndpoint;
const PREFLIGHT_REPORT_ENDPOINT =
  "/api/v1/security/print-preflight-report" satisfies ToolEndpoint;
const PREFLIGHT_FIX_ENDPOINT =
  "/api/v1/security/print-preflight-fix" satisfies ToolEndpoint;
const PREFLIGHT_FIX_PREVIEW_ENDPOINT =
  "/api/v1/security/print-preflight-fix-preview" satisfies ToolEndpoint;

const NUMERIC_FIELDS = [
  "requiredBleedMm",
  "minImageDpi",
  "hairlineThresholdPt",
  "minFontSizePt",
  "safetyMarginMm",
  "maxInkCoveragePercent",
  "minImage1BitDpi",
  "maxImageDpi",
  "maxSpotCount",
] as const satisfies ReadonlyArray<keyof PrintPreflightParameters>;

const buildFormData = (
  file: File,
  params: PrintPreflightParameters,
): FormData => {
  const formData = new FormData();
  formData.append("fileInput", file);
  for (const key of NUMERIC_FIELDS) {
    const value = params[key];
    if (value !== undefined) {
      formData.append(key, String(value));
    }
  }
  formData.append("checkBleedCoverage", String(params.checkBleedCoverage));
  formData.append("includeSummaryPage", String(params.includeSummaryPage));
  if (i18n.language) {
    formData.append("reportLanguage", i18n.language);
  }
  if (params.disabledChecks && params.disabledChecks.length > 0) {
    formData.append("disabledChecks", params.disabledChecks.join(","));
  }
  if (params.fixups && params.fixups.length > 0) {
    formData.append("fixups", params.fixups.join(","));
  }
  return formData;
};

/**
 * Automation processor: each input yields one artifact — the annotated PDF copy
 * (still a PDF, so the pipeline keeps flowing) or the JSON report when
 * reportFormat is "json" (a terminal step: the next tool would get JSON).
 */
const printPreflightProcessor = async (
  params: PrintPreflightParameters,
  files: File[],
): Promise<CustomProcessorResult> => {
  const processedFiles: File[] = [];

  for (const file of files) {
    const base = file.name.replace(/\.pdf$/i, "");
    if (params.reportFormat === "fixAuditJson") {
      const response = await apiClient.post(
        PREFLIGHT_FIX_PREVIEW_ENDPOINT,
        buildFormData(file, params),
      );
      const json = JSON.stringify(response.data ?? null, null, 2);
      processedFiles.push(
        new File([json], `${base}-preflight-fix-audit.json`, {
          type: "application/json",
        }),
      );
    } else if (params.reportFormat === "json") {
      const response = await apiClient.post(
        PREFLIGHT_ENDPOINT,
        buildFormData(file, params),
      );
      const json = JSON.stringify(response.data ?? null, null, 2);
      processedFiles.push(
        new File([json], `${base}-preflight-report.json`, {
          type: "application/json",
        }),
      );
    } else {
      const endpoint =
        params.reportFormat === "reportPdf"
          ? PREFLIGHT_REPORT_ENDPOINT
          : params.reportFormat === "fixedPdf"
            ? PREFLIGHT_FIX_ENDPOINT
            : PREFLIGHT_ANNOTATED_ENDPOINT;
      const response = await apiClient.post(
        endpoint,
        buildFormData(file, params),
        { responseType: "blob" },
      );
      const blob =
        response.data instanceof Blob
          ? response.data
          : new Blob([response.data], { type: "application/pdf" });
      processedFiles.push(
        new File([blob], `${base}_preflight.pdf`, { type: "application/pdf" }),
      );
    }
  }

  return { files: processedFiles };
};

export const printPreflightOperationConfig = defineCustomTool({
  operationType: "printPreflight",
  validateParams: validatePrintPreflightParameters,
  endpoint: (params: PrintPreflightParameters) =>
    params.reportFormat === "json"
      ? PREFLIGHT_ENDPOINT
      : params.reportFormat === "fixAuditJson"
        ? PREFLIGHT_FIX_PREVIEW_ENDPOINT
        : params.reportFormat === "reportPdf"
          ? PREFLIGHT_REPORT_ENDPOINT
          : params.reportFormat === "fixedPdf"
            ? PREFLIGHT_FIX_ENDPOINT
            : PREFLIGHT_ANNOTATED_ENDPOINT,
  endpoints: [
    PREFLIGHT_ENDPOINT,
    PREFLIGHT_ANNOTATED_ENDPOINT,
    PREFLIGHT_REPORT_ENDPOINT,
    PREFLIGHT_FIX_ENDPOINT,
    PREFLIGHT_FIX_PREVIEW_ENDPOINT,
  ],
  customProcessor: printPreflightProcessor,
  defaultParameters,
});

export const usePrintPreflightOperation = (): PrintPreflightOperationHook => {
  const { t } = useTranslation();
  const [isLoading, setIsLoading] = useState(false);
  const [status, setStatus] = useState("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [downloadUrl, setDownloadUrl] = useState<string | null>(null);
  const [downloadFilename, setDownloadFilename] = useState("");
  const [results, setResults] = useState<PrintPreflightResultEntry[]>([]);

  const cancelRequested = useRef(false);
  const previousUrl = useRef<string | null>(null);
  // Inputs of the current run, kept so the annotated copy can be re-requested
  // without re-running the analysis client-side.
  const lastRun = useRef<
    Map<string, { file: StirlingFile; params: PrintPreflightParameters }>
  >(new Map());
  const [annotatedLoading, setAnnotatedLoading] = useState<string | null>(null);
  const [reportLoading, setReportLoading] = useState<string | null>(null);
  const [fixedLoading, setFixedLoading] = useState<string | null>(null);
  const [previewLoading, setPreviewLoading] = useState<string | null>(null);
  const [fixAudits, setFixAudits] = useState<Record<string, PreflightFixAudit>>(
    {},
  );

  const cleanupDownloadUrl = useCallback(() => {
    if (previousUrl.current) {
      URL.revokeObjectURL(previousUrl.current);
      previousUrl.current = null;
    }
  }, []);

  const resetResults = useCallback(() => {
    cancelRequested.current = false;
    setResults([]);
    setFiles([]);
    setFixAudits({});
    lastRun.current.clear();
    cleanupDownloadUrl();
    setDownloadUrl(null);
    setDownloadFilename("");
    setStatus("");
    setErrorMessage(null);
  }, [cleanupDownloadUrl]);

  const clearError = useCallback(() => {
    setErrorMessage(null);
  }, []);

  const executeOperation = useCallback(
    async (params: PrintPreflightParameters, selectedFiles: StirlingFile[]) => {
      if (selectedFiles.length === 0) {
        setErrorMessage(t("noFileSelected", "No file loaded"));
        return;
      }

      cancelRequested.current = false;
      setIsLoading(true);
      setStatus(t("printPreflight.processing", "Running preflight checks..."));
      setErrorMessage(null);
      setResults([]);
      setFiles([]);
      setFixAudits({});
      cleanupDownloadUrl();
      setDownloadUrl(null);
      setDownloadFilename("");
      lastRun.current.clear();

      try {
        const aggregated: PrintPreflightResultEntry[] = [];

        for (const file of selectedFiles) {
          if (cancelRequested.current) break;
          lastRun.current.set(file.fileId, { file, params });

          try {
            const response = await apiClient.post(
              PREFLIGHT_ENDPOINT,
              buildFormData(file, params),
            );
            aggregated.push({
              fileId: file.fileId,
              fileName: file.name,
              report: (response.data ?? null) as PrintPreflightReport | null,
              error: null,
            });
          } catch (error) {
            aggregated.push({
              fileId: file.fileId,
              fileName: file.name,
              report: null,
              error: extractErrorMessage(error),
            });
          }
        }

        if (!cancelRequested.current) {
          setResults(aggregated);
          const payloads = aggregated
            .filter((e) => !e.error && e.report)
            .map((e) => e.report);
          if (payloads.length > 0) {
            const content = payloads.length === 1 ? payloads[0] : payloads;
            const json = JSON.stringify(content, null, 2);
            const resultFile = new File([json], PREFLIGHT_JSON_FILENAME, {
              type: "application/json",
            });
            setFiles([resultFile]);
            const url = URL.createObjectURL(resultFile);
            setDownloadUrl(url);
            setDownloadFilename(PREFLIGHT_JSON_FILENAME);
            previousUrl.current = url;
          }

          if (aggregated.some((item) => item.error)) {
            setErrorMessage(
              t(
                "printPreflight.error.partial",
                "Some files could not be analyzed.",
              ),
            );
          }
          setStatus(t("printPreflight.status.complete", "Preflight complete"));
        }
      } catch (e) {
        console.error("[printPreflight] unexpected failure", e);
        setErrorMessage(
          t(
            "printPreflight.error.unexpected",
            "Unexpected error during preflight.",
          ),
        );
      } finally {
        setIsLoading(false);
      }
    },
    [cleanupDownloadUrl, t],
  );

  const downloadPdf = useCallback(
    async (
      fileId: string,
      endpoint: ToolEndpoint,
      fileSuffix: string,
      setBusy: (id: string | null) => void,
    ) => {
      const run = lastRun.current.get(fileId);
      if (
        !run ||
        annotatedLoading ||
        reportLoading ||
        fixedLoading ||
        previewLoading
      ) {
        return;
      }
      setBusy(fileId);
      try {
        const response = await apiClient.post(
          endpoint,
          buildFormData(run.file, run.params),
          { responseType: "blob" },
        );
        const blob =
          response.data instanceof Blob
            ? response.data
            : new Blob([response.data], { type: "application/pdf" });
        const base = run.file.name.replace(/\.pdf$/i, "");
        await downloadFile({
          data: new File([blob], `${base}${fileSuffix}.pdf`, {
            type: "application/pdf",
          }),
          filename: `${base}${fileSuffix}.pdf`,
        });
      } catch (error) {
        setErrorMessage(extractErrorMessage(error));
      } finally {
        setBusy(null);
      }
    },
    [annotatedLoading, reportLoading, fixedLoading, previewLoading],
  );

  const downloadAnnotated = useCallback(
    (fileId: string) =>
      downloadPdf(
        fileId,
        PREFLIGHT_ANNOTATED_ENDPOINT,
        "_preflight",
        setAnnotatedLoading,
      ),
    [downloadPdf],
  );

  const downloadReport = useCallback(
    (fileId: string) =>
      downloadPdf(
        fileId,
        PREFLIGHT_REPORT_ENDPOINT,
        "_preflight-report",
        setReportLoading,
      ),
    [downloadPdf],
  );

  const downloadFixed = useCallback(
    (fileId: string) =>
      downloadPdf(
        fileId,
        PREFLIGHT_FIX_ENDPOINT,
        "_preflight-fixed",
        setFixedLoading,
      ),
    [downloadPdf],
  );

  const previewFixes = useCallback(
    async (fileId: string) => {
      const run = lastRun.current.get(fileId);
      if (
        !run ||
        annotatedLoading ||
        reportLoading ||
        fixedLoading ||
        previewLoading
      ) {
        return;
      }
      setPreviewLoading(fileId);
      try {
        const response = await apiClient.post(
          PREFLIGHT_FIX_PREVIEW_ENDPOINT,
          buildFormData(run.file, run.params),
        );
        setFixAudits((current) => ({
          ...current,
          [fileId]: (response.data ?? null) as PreflightFixAudit,
        }));
      } catch (error) {
        setErrorMessage(extractErrorMessage(error));
      } finally {
        setPreviewLoading(null);
      }
    },
    [annotatedLoading, reportLoading, fixedLoading, previewLoading],
  );

  const cancelOperation = useCallback(() => {
    if (isLoading) {
      cancelRequested.current = true;
      setIsLoading(false);
      setStatus(t("operationCancelled", "Operation cancelled"));
    }
  }, [isLoading, t]);

  const undoOperation = useCallback(async () => {
    resetResults();
  }, [resetResults]);

  useEffect(() => {
    return () => {
      cleanupDownloadUrl();
    };
  }, [cleanupDownloadUrl]);

  return useMemo<PrintPreflightOperationHook>(
    () => ({
      files,
      thumbnails: [],
      isGeneratingThumbnails: false,
      downloadUrl,
      downloadFilename,
      isLoading,
      status,
      errorMessage,
      progress: null,
      executeOperation,
      resetResults,
      clearError,
      cancelOperation,
      undoOperation,
      results,
      downloadAnnotated,
      downloadReport,
      downloadFixed,
      previewFixes,
      fixAudits,
      annotatedLoading,
      reportLoading,
      fixedLoading,
      previewLoading,
    }),
    [
      annotatedLoading,
      downloadFixed,
      fixAudits,
      fixedLoading,
      previewFixes,
      previewLoading,
      reportLoading,
      cancelOperation,
      clearError,
      downloadAnnotated,
      downloadReport,
      downloadFilename,
      downloadUrl,
      errorMessage,
      executeOperation,
      files,
      isLoading,
      resetResults,
      results,
      status,
    ],
  );
};
