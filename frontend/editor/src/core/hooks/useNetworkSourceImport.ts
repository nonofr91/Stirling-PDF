import type { ReactNode } from "react";

export interface NetworkSourceImportHandle {
  /** False until at least one enabled network source exists. */
  enabled: boolean;
  open: () => void;
  /** True while the browser modal is up; host modals must release focus/outside-click while set. */
  isOpen: boolean;
  /** The browser modal element; render once near the trigger. */
  modal: ReactNode;
}

/** Core stub: network sources are a proprietary feature. */
export function useNetworkSourceImport(
  _onPicked: (files: File[]) => void | Promise<void>,
): NetworkSourceImportHandle {
  return { enabled: false, open: () => {}, isOpen: false, modal: null };
}
