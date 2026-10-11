import { useCallback, useEffect, useState } from "react";
import type { TFunction } from "i18next";
import apiClient from "@app/services/apiClient";
import { PrintPreflightProfile } from "@app/types/printPreflight";
import {
  defaultParameters,
  PrintPreflightParameters,
} from "@app/hooks/tools/printPreflight/usePrintPreflightParameters";

const ENDPOINT = "/api/v1/security/print-preflight-profiles";

export type PreflightProfileError = "load" | "save" | "delete";

export interface PreflightProfilesHook {
  profiles: PrintPreflightProfile[];
  loading: boolean;
  error: PreflightProfileError | null;
  refresh: () => Promise<PrintPreflightProfile[]>;
  saveProfile: (
    name: string,
    description: string,
    parameters: PrintPreflightParameters,
  ) => Promise<boolean>;
  /** Fresh profile list after deletion, or null on failure. */
  deleteProfile: (name: string) => Promise<PrintPreflightProfile[] | null>;
}

/**
 * Turns the current tool parameters into the JSON body the profile endpoint
 * expects — undefined becomes absence so the backend stores only what was set.
 */
export function parametersToProfile(
  name: string,
  description: string,
  parameters: PrintPreflightParameters,
): Omit<PrintPreflightProfile, "builtin" | "overridden"> {
  return {
    name,
    description: description || null,
    requiredBleedMm: parameters.requiredBleedMm ?? null,
    minImageDpi: parameters.minImageDpi ?? null,
    hairlineThresholdPt: parameters.hairlineThresholdPt ?? null,
    checkBleedCoverage: parameters.checkBleedCoverage,
    minFontSizePt: parameters.minFontSizePt ?? null,
    safetyMarginMm: parameters.safetyMarginMm ?? null,
    maxInkCoveragePercent: parameters.maxInkCoveragePercent ?? null,
    renderedInkCoverage: parameters.renderedInkCoverage ?? null,
    minImage1BitDpi: parameters.minImage1BitDpi ?? null,
    maxImageDpi: parameters.maxImageDpi ?? null,
    maxSpotCount: parameters.maxSpotCount ?? null,
    includeSummaryPage: parameters.includeSummaryPage,
    disabledChecks: parameters.disabledChecks ?? null,
    fixups: parameters.fixups ?? null,
    fixupParams:
      parameters.fixupParams && Object.keys(parameters.fixupParams).length > 0
        ? parameters.fixupParams
        : null,
  };
}

/**
 * A profile is authoritative: fields it leaves unset fall back to the tool
 * defaults, not to whatever the user happened to have typed before. Output
 * routing (reportFormat) is not part of the profile — it stays as configured.
 */
export function profileToParameters(
  profile: PrintPreflightProfile,
  current?: PrintPreflightParameters,
): PrintPreflightParameters {
  return {
    ...defaultParameters,
    reportFormat: current?.reportFormat ?? defaultParameters.reportFormat,
    requiredBleedMm: profile.requiredBleedMm ?? undefined,
    minImageDpi: profile.minImageDpi ?? undefined,
    hairlineThresholdPt: profile.hairlineThresholdPt ?? undefined,
    checkBleedCoverage: profile.checkBleedCoverage ?? true,
    minFontSizePt: profile.minFontSizePt ?? undefined,
    safetyMarginMm: profile.safetyMarginMm ?? undefined,
    maxInkCoveragePercent: profile.maxInkCoveragePercent ?? undefined,
    renderedInkCoverage: profile.renderedInkCoverage ?? false,
    minImage1BitDpi: profile.minImage1BitDpi ?? undefined,
    maxImageDpi: profile.maxImageDpi ?? undefined,
    maxSpotCount: profile.maxSpotCount ?? undefined,
    includeSummaryPage: profile.includeSummaryPage ?? true,
    disabledChecks: profile.disabledChecks ?? undefined,
    fixups: profile.fixups ?? undefined,
    fixupParams: profile.fixupParams ?? undefined,
  };
}

/**
 * Display name for the profile pickers: shipped built-ins get their translated
 * name, a custom overriding one keeps it plus a "modified" marker so the
 * override is visible wherever the profile is referenced.
 */
export function profileLabel(
  profile: PrintPreflightProfile,
  t: TFunction,
): string {
  const base =
    profile.builtin || profile.overridden
      ? t(`printPreflight.profiles.builtin.${profile.name}`, profile.name)
      : profile.name;
  return profile.overridden
    ? `${base} ${t("printPreflight.profiles.modified", "(modified)")}`
    : base;
}

export function usePreflightProfiles(): PreflightProfilesHook {
  const [profiles, setProfiles] = useState<PrintPreflightProfile[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<PreflightProfileError | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const response = await apiClient.get<PrintPreflightProfile[]>(ENDPOINT);
      setProfiles(response.data);
      setError(null);
      return response.data;
    } catch {
      setError("load");
      return [];
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const saveProfile = useCallback(
    async (
      name: string,
      description: string,
      parameters: PrintPreflightParameters,
    ): Promise<boolean> => {
      try {
        await apiClient.post(
          ENDPOINT,
          parametersToProfile(name, description, parameters),
        );
        await refresh();
        return true;
      } catch {
        setError("save");
        return false;
      }
    },
    [refresh],
  );

  const deleteProfile = useCallback(
    async (name: string): Promise<PrintPreflightProfile[] | null> => {
      try {
        await apiClient.delete(`${ENDPOINT}/${encodeURIComponent(name)}`);
        return await refresh();
      } catch {
        setError("delete");
        return null;
      }
    },
    [refresh],
  );

  return { profiles, loading, error, refresh, saveProfile, deleteProfile };
}
