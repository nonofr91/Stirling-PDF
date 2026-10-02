import { useCallback, useEffect, useMemo, useState } from "react";
import { Loader, Select, TextInput } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { Button, Modal } from "@app/ui";
import { Icon } from "@app/ui/Icon";
import { Z_INDEX_OVER_FILE_MANAGER_MODAL } from "@app/styles/zIndex";
import { alert } from "@app/components/toast";
import {
  downloadNetworkFile,
  listNetworkEntries,
  uploadNetworkFile,
  type NetworkEntry,
} from "@app/services/networkSourceBrowser";
import type { PolicySource } from "@app/services/policySources";

interface NetworkSourceBrowserModalProps {
  open: boolean;
  onClose: () => void;
  /** import: pick remote files to ingest; export: pick a directory to upload into. */
  mode: "import" | "export";
  sources: PolicySource[];
  onImport?: (files: File[]) => void | Promise<void>;
  exportFiles?: File[];
}

/** Breadcrumbs descend into subdirs; `path`/`dir` params stay relative to the source root. */
export function NetworkSourceBrowserModal({
  open,
  onClose,
  mode,
  sources,
  onImport,
  exportFiles,
}: NetworkSourceBrowserModalProps) {
  const { t } = useTranslation();
  const [sourceId, setSourceId] = useState<string | null>(null);
  const [dir, setDir] = useState("");
  const [entries, setEntries] = useState<NetworkEntry[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [filename, setFilename] = useState("");
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setSourceId(sources.length === 1 ? sources[0].id : null);
    setDir("");
    setEntries([]);
    setSelected(new Set());
    setFilename(exportFiles?.length === 1 ? exportFiles[0].name : "");
  }, [open, sources, exportFiles]);

  const refresh = useCallback(
    async (id: string, target: string) => {
      setLoading(true);
      try {
        setEntries(await listNetworkEntries(id, target));
        setDir(target);
        setSelected(new Set());
      } catch (e) {
        alert({
          alertType: "error",
          title: t("networkSource.browseError", "Cannot list this directory"),
          body: e instanceof Error ? e.message : String(e),
          expandable: false,
        });
      } finally {
        setLoading(false);
      }
    },
    [t],
  );

  useEffect(() => {
    if (open && sourceId) void refresh(sourceId, "");
  }, [open, sourceId, refresh]);

  const crumbs = useMemo(() => dir.split("/").filter(Boolean), [dir]);
  const sorted = useMemo(
    () =>
      [...entries].sort(
        (a, b) =>
          Number(b.directory) - Number(a.directory) ||
          a.name.localeCompare(b.name),
      ),
    [entries],
  );

  const toggle = (entry: NetworkEntry) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(entry.path)) next.delete(entry.path);
      else next.add(entry.path);
      return next;
    });
  };

  const runImport = async () => {
    if (!sourceId || !onImport) return;
    setBusy(true);
    try {
      const files: File[] = [];
      for (const path of selected) {
        files.push(await downloadNetworkFile(sourceId, path));
      }
      await onImport(files);
      onClose();
    } catch (e) {
      alert({
        alertType: "error",
        title: t("networkSource.importError", "Import failed"),
        body: e instanceof Error ? e.message : String(e),
        expandable: false,
      });
    } finally {
      setBusy(false);
    }
  };

  const runExport = async () => {
    if (!sourceId || !exportFiles?.length) return;
    setBusy(true);
    try {
      for (const file of exportFiles) {
        const out =
          exportFiles.length === 1 && filename.trim()
            ? new File([file], filename.trim(), { type: file.type })
            : file;
        await uploadNetworkFile(sourceId, dir, out);
      }
      onClose();
      alert({
        alertType: "success",
        title: t("networkSource.exportDone", "Sent to the network source"),
        expandable: false,
      });
    } catch (e) {
      alert({
        alertType: "error",
        title: t("networkSource.exportError", "Send failed"),
        body: e instanceof Error ? e.message : String(e),
        expandable: false,
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      width="lg"
      // Launched from inside the file-manager modal, which sits at 1200.
      zIndex={Z_INDEX_OVER_FILE_MANAGER_MODAL}
      title={t(
        mode === "import"
          ? "networkSource.importTitle"
          : "networkSource.exportTitle",
        mode === "import" ? "Open from a network server" : "Send to network",
      )}
      footer={
        <>
          <Button variant="secondary" accent="neutral" onClick={onClose}>
            {t("cancel", "Cancel")}
          </Button>
          {mode === "import" ? (
            <Button
              onClick={() => void runImport()}
              disabled={selected.size === 0 || busy}
              loading={busy}
            >
              {t("networkSource.import", "Import")}
              {selected.size > 0 ? ` (${selected.size})` : ""}
            </Button>
          ) : (
            <Button
              onClick={() => void runExport()}
              disabled={!sourceId || busy}
              loading={busy}
            >
              {t("networkSource.sendHere", "Send here")}
            </Button>
          )}
        </>
      }
    >
      {sources.length > 1 && (
        <Select
          label={t("networkSource.source", "Source")}
          data={sources.map((s) => ({ value: s.id, label: s.name }))}
          value={sourceId}
          onChange={(v) => setSourceId(v)}
          mb="sm"
        />
      )}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: "0.25rem",
          marginBottom: "0.5rem",
          flexWrap: "wrap",
        }}
      >
        <Button
          variant="tertiary"
          accent="neutral"
          size="sm"
          onClick={() => sourceId && void refresh(sourceId, "")}
          disabled={!sourceId || loading}
        >
          {t("networkSource.root", "Root")}
        </Button>
        {crumbs.map((segment, i) => (
          <span key={i} style={{ display: "inline-flex", gap: "0.25rem" }}>
            <span aria-hidden>/</span>
            <Button
              variant="tertiary"
              accent="neutral"
              size="sm"
              onClick={() =>
                sourceId &&
                void refresh(sourceId, crumbs.slice(0, i + 1).join("/"))
              }
              disabled={loading}
            >
              {segment}
            </Button>
          </span>
        ))}
      </div>
      <div
        role="listbox"
        aria-label={t("networkSource.entries", "Remote files")}
        style={{
          minHeight: "12rem",
          maxHeight: "20rem",
          overflowY: "auto",
          border: "1px solid var(--mantine-color-gray-3)",
          borderRadius: "0.5rem",
        }}
      >
        {loading ? (
          <div style={{ padding: "2rem", textAlign: "center" }}>
            <Loader size="sm" />
          </div>
        ) : !sourceId ? (
          <div style={{ padding: "1rem" }}>
            {t("networkSource.pickSource", "Choose a source to browse.")}
          </div>
        ) : sorted.length === 0 ? (
          <div style={{ padding: "1rem" }}>
            {t("networkSource.emptyDir", "This directory is empty.")}
          </div>
        ) : (
          sorted.map((entry) =>
            entry.directory ? (
              <button
                key={entry.path}
                type="button"
                onClick={() => sourceId && void refresh(sourceId, entry.path)}
                style={rowStyle}
              >
                <Icon name="folder" size={16} />
                <span style={{ flex: 1, textAlign: "left" }}>{entry.name}</span>
                <Icon name="chevron-right" size={14} />
              </button>
            ) : (
              <button
                key={entry.path}
                type="button"
                role="option"
                aria-selected={selected.has(entry.path)}
                onClick={() => mode === "import" && toggle(entry)}
                style={{
                  ...rowStyle,
                  background: selected.has(entry.path)
                    ? "var(--mantine-color-blue-0)"
                    : undefined,
                  cursor: mode === "import" ? "pointer" : "default",
                }}
              >
                <Icon name="file" size={16} />
                <span style={{ flex: 1, textAlign: "left" }}>{entry.name}</span>
                {mode === "import" && selected.has(entry.path) && (
                  <Icon name="check" size={14} />
                )}
              </button>
            ),
          )
        )}
      </div>
      {mode === "export" && exportFiles?.length === 1 && (
        <TextInput
          label={t("networkSource.filename", "Remote filename")}
          value={filename}
          onChange={(e) => setFilename(e.currentTarget.value)}
          mt="sm"
        />
      )}
    </Modal>
  );
}

const rowStyle: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  gap: "0.5rem",
  width: "100%",
  padding: "0.5rem 0.75rem",
  border: "none",
  background: "none",
  font: "inherit",
};
