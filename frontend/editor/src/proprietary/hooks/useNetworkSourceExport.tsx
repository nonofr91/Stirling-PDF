import { useCallback, useEffect, useState } from "react";
import { NetworkSourceBrowserModal } from "@app/components/shared/NetworkSourceBrowserModal";
import { fetchNetworkSources } from "@app/services/networkSourceBrowser";
import type { PolicySource } from "@app/services/policySources";
import type { NetworkSourceExportHandle } from "@core/hooks/useNetworkSourceExport";

export type { NetworkSourceExportHandle };

/** Uploads operation results into a directory of a stored network source. */
export function useNetworkSourceExport(): NetworkSourceExportHandle {
  const [sources, setSources] = useState<PolicySource[]>([]);
  const [pending, setPending] = useState<File[] | null>(null);

  useEffect(() => {
    void fetchNetworkSources()
      .then(setSources)
      .catch(() => setSources([]));
  }, []);

  const open = useCallback((files: File[]) => setPending(files), []);

  return {
    enabled: sources.length > 0,
    open,
    modal: (
      <NetworkSourceBrowserModal
        open={pending !== null}
        onClose={() => setPending(null)}
        mode="export"
        sources={sources}
        exportFiles={pending ?? undefined}
      />
    ),
  };
}
