import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import apiClient from "@app/services/apiClient";
import { downloadFile } from "@app/services/downloadService";
import { ToolOperationHook } from "@app/hooks/tools/shared/useToolOperation";
import type { StirlingFile } from "@app/types/fileContext";
import { extractErrorMessage } from "@app/utils/toolErrorHandler";
import {
  PrintPreflightReport,
  PREFLIGHT_JSON_FILENAME,
} from "@app/types/printPreflight";
import type { PrintPreflightParameters } from "@app/hooks/tools/printPreflight/usePrintPreflightParameters";

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

const buildFormData = (
  file: StirlingFile,
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
              "/api/v1/security/print-preflight",
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
          "/api/v1/security/print-preflight-annotated",
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
