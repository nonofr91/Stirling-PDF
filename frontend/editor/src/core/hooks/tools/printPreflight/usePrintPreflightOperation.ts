import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
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
  annotatedLoading: string | null;
}

const PREFLIGHT_ENDPOINT =
  "/api/v1/security/print-preflight" satisfies ToolEndpoint;
const PREFLIGHT_ANNOTATED_ENDPOINT =
  "/api/v1/security/print-preflight-annotated" satisfies ToolEndpoint;

const buildFormData = (
  file: File,
  params: PrintPreflightParameters,
): FormData => {
  const formData = new FormData();
  formData.append("fileInput", file);
  if (params.requiredBleedMm !== undefined) {
    formData.append("requiredBleedMm", String(params.requiredBleedMm));
  }
  if (params.minImageDpi !== undefined) {
    formData.append("minImageDpi", String(params.minImageDpi));
  }
  if (params.hairlineThresholdPt !== undefined) {
    formData.append("hairlineThresholdPt", String(params.hairlineThresholdPt));
  }
  formData.append("checkBleedCoverage", String(params.checkBleedCoverage));
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
    if (params.reportFormat === "json") {
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
      const response = await apiClient.post(
        PREFLIGHT_ANNOTATED_ENDPOINT,
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
      : PREFLIGHT_ANNOTATED_ENDPOINT,
  endpoints: [PREFLIGHT_ENDPOINT, PREFLIGHT_ANNOTATED_ENDPOINT],
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

  const downloadAnnotated = useCallback(
    async (fileId: string) => {
      const run = lastRun.current.get(fileId);
      if (!run || annotatedLoading) {
        return;
      }
      setAnnotatedLoading(fileId);
      try {
        const response = await apiClient.post(
          PREFLIGHT_ANNOTATED_ENDPOINT,
          buildFormData(run.file, run.params),
          { responseType: "blob" },
        );
        const blob =
          response.data instanceof Blob
            ? response.data
            : new Blob([response.data], { type: "application/pdf" });
        const base = run.file.name.replace(/\.pdf$/i, "");
        await downloadFile({
          data: new File([blob], `${base}_preflight.pdf`, {
            type: "application/pdf",
          }),
          filename: `${base}_preflight.pdf`,
        });
      } catch (error) {
        setErrorMessage(extractErrorMessage(error));
      } finally {
        setAnnotatedLoading(null);
      }
    },
    [annotatedLoading],
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
      annotatedLoading,
    }),
    [
      annotatedLoading,
      cancelOperation,
      clearError,
      downloadAnnotated,
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
