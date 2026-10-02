import { useCallback, useEffect, useState } from "react";
import { NetworkSourceBrowserModal } from "@app/components/shared/NetworkSourceBrowserModal";
import { fetchNetworkSources } from "@app/services/networkSourceBrowser";
import type { PolicySource } from "@app/services/policySources";
import type { NetworkSourceImportHandle } from "@core/hooks/useNetworkSourceImport";

export type { NetworkSourceImportHandle };

/** Browsable network sources gate the picker; the probe is silent since absence is normal. */
export function useNetworkSourceImport(
  onPicked: (files: File[]) => void | Promise<void>,
): NetworkSourceImportHandle {
  const [sources, setSources] = useState<PolicySource[]>([]);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    void fetchNetworkSources()
      .then(setSources)
      .catch(() => setSources([]));
  }, []);

  const openPicker = useCallback(() => setOpen(true), []);

  return {
    enabled: sources.length > 0,
    open: openPicker,
    isOpen: open,
    modal: (
      <NetworkSourceBrowserModal
        open={open}
        onClose={() => setOpen(false)}
        mode="import"
        sources={sources}
        onImport={onPicked}
      />
    ),
  };
}
