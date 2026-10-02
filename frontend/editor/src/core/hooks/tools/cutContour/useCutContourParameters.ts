import { BaseParameters } from "@app/types/parameters";
import {
  useBaseParameters,
  BaseParametersHook,
} from "@app/hooks/tools/shared/useBaseParameters";

export type ExtractionMode = "AUTO" | "ALPHA" | "BACKGROUND" | "AI";

export const EXTRACTION_MODES: ExtractionMode[] = [
  "AUTO",
  "ALPHA",
  "BACKGROUND",
  "AI",
];

export interface CutContourParameters extends BaseParameters {
  extractionMode: ExtractionMode;
  /** Mask render resolution; large pages are clamped server-side. */
  dpi?: number;
  alphaThreshold?: number;
  backgroundTolerance?: number;
  minAreaMm2?: number;
  /** Gaps between elements narrower than this (mm) merge under one outer contour. */
  mergeGapMm?: number;
  /** Rough perimeter drawn on the viewer, flat x,y page-fraction pairs. */
  roiPoints: number[];
  /** 1-based page the roi belongs to; 0 = applies to every page. */
  roiPage: number;
  /** 0..100 — higher simplifies the traced path harder. */
  smoothness?: number;
  /** mm the cut line moves outward from the silhouette; negative insets. */
  offsetMm?: number;
  keepHoles: boolean;
  aiThreshold?: number;
  /** Catalog model id; empty string selects the backend default (u2net). */
  aiModelId: string;
  /** Spot colourant name; RIPs key on the case-sensitive "CutContour". */
  spotName: string;
  strokeWidthPt?: number;
  clipArtwork: boolean;
  bleedMm?: number;
  trimToContour: boolean;
  processingSteps: boolean;
  layerName: string;
}

export const defaultParameters: CutContourParameters = {
  extractionMode: "AUTO",
  dpi: 150,
  alphaThreshold: 16,
  backgroundTolerance: 24,
  minAreaMm2: 1,
  mergeGapMm: 8,
  roiPoints: [],
  roiPage: 0,
  smoothness: 20,
  offsetMm: 0,
  keepHoles: false,
  aiThreshold: 0.4,
  aiModelId: "",
  spotName: "CutContour",
  strokeWidthPt: 0.25,
  clipArtwork: false,
  bleedMm: 0,
  trimToContour: false,
  processingSteps: true,
  layerName: "",
};

export type CutContourParametersHook = BaseParametersHook<CutContourParameters>;

const inRange = (v: number | undefined, min: number, max: number): boolean =>
  v === undefined || (v >= min && v <= max);

/** Shared by the tool's settings hook and its operationConfig so the editor
 *  and the pipeline builder reject the same requests the backend would. */
export function validateCutContourParameters(
  params: CutContourParameters,
): boolean {
  if (params.spotName.trim() === "") return false;
  if (!inRange(params.dpi, 72, 600)) return false;
  if (!inRange(params.alphaThreshold, 0, 255)) return false;
  if (!inRange(params.backgroundTolerance, 0, 255)) return false;
  if (params.minAreaMm2 !== undefined && params.minAreaMm2 < 0) return false;
  if (params.mergeGapMm !== undefined && params.mergeGapMm < 0) return false;
  if (params.roiPoints.length % 2 !== 0) return false;
  if (params.roiPoints.length > 0 && params.roiPoints.length < 6) return false;
  if (params.roiPoints.some((v) => v < -0.05 || v > 1.05)) return false;
  if (params.roiPage < 0) return false;
  if (!inRange(params.smoothness, 0, 100)) return false;
  if (!inRange(params.offsetMm, -50, 50)) return false;
  if (!inRange(params.aiThreshold, 0, 1)) return false;
  if (
    params.strokeWidthPt !== undefined &&
    !(params.strokeWidthPt > 0 && Number.isFinite(params.strokeWidthPt))
  ) {
    return false;
  }
  if (!inRange(params.bleedMm, 0, 50)) return false;
  return true;
}

export const useCutContourParameters = (): CutContourParametersHook => {
  return useBaseParameters({
    defaultParameters,
    endpointName: "cut-contour",
    validateFn: validateCutContourParameters,
  });
};
