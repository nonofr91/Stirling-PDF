import apiClient from "@app/services/apiClient";
import {
  fetchPolicySources,
  type PolicySource,
} from "@app/services/policySources";

/** Portal source types backed by a network protocol (see NetworkProtocol#sourceType). */
const NETWORK_SOURCE_TYPES = new Set(["ftp", "sftp", "network"]);

/** One listing entry from GET /api/v1/sources/{id}/network/list; path is relative to the source root. */
export interface NetworkEntry {
  path: string;
  name: string;
  directory: boolean;
  size: number;
  lastModifiedMs: number;
}

/** Enabled FTP/SFTP/SMB sources the current user can browse. */
export async function fetchNetworkSources(): Promise<PolicySource[]> {
  const sources = await fetchPolicySources();
  return sources.filter(
    (source) =>
      source.status !== "disabled" && NETWORK_SOURCE_TYPES.has(source.type),
  );
}

export async function listNetworkEntries(
  sourceId: string,
  dir: string,
): Promise<NetworkEntry[]> {
  const { data } = await apiClient.get<NetworkEntry[]>(
    `/api/v1/sources/${encodeURIComponent(sourceId)}/network/list`,
    { params: dir ? { dir } : undefined },
  );
  return data;
}

export async function downloadNetworkFile(
  sourceId: string,
  path: string,
): Promise<File> {
  const { data, headers } = await apiClient.get<Blob>(
    `/api/v1/sources/${encodeURIComponent(sourceId)}/network/file`,
    { params: { path }, responseType: "blob" },
  );
  const name = path.split("/").pop() ?? "download";
  const contentType = headers?.["content-type"];
  return new File([data], name, {
    type:
      typeof contentType === "string"
        ? contentType
        : "application/octet-stream",
  });
}

/** Uploads into `dir` (relative to the source root; "" = root). The server owns the filename check. */
export async function uploadNetworkFile(
  sourceId: string,
  dir: string,
  file: File,
): Promise<void> {
  const form = new FormData();
  form.append("fileInput", file);
  await apiClient.post(
    `/api/v1/sources/${encodeURIComponent(sourceId)}/network/file`,
    form,
    { params: dir ? { dir } : undefined },
  );
}
