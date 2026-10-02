import type { ReactNode } from "react";

export interface NetworkSourceExportHandle {
  /** False until at least one enabled network source exists. */
  enabled: boolean;
  /** Opens the directory picker; `files` are uploaded on confirm. */
  open: (files: File[]) => void;
  /** The browser modal element; render once near the trigger. */
  modal: ReactNode;
}

/** Core stub: network sources are a proprietary feature. */
export function useNetworkSourceExport(): NetworkSourceExportHandle {
  return { enabled: false, open: () => {}, modal: null };
}
