import { beforeEach, describe, expect, it, vi } from "vitest";

const get = vi.fn().mockResolvedValue({ data: {}, headers: {} });
const post = vi.fn().mockResolvedValue({ data: {} });

vi.mock("@app/services/apiClient", () => ({
  default: {
    get: (...args: unknown[]) => get(...args),
    post: (...args: unknown[]) => post(...args),
  },
}));

const {
  fetchNetworkSources,
  listNetworkEntries,
  downloadNetworkFile,
  uploadNetworkFile,
} = await import("@app/services/networkSourceBrowser");

const sourcesResponse = (sources: object[]) => ({
  data: { sources },
});

describe("fetchNetworkSources", () => {
  beforeEach(() => {
    get.mockClear();
  });

  it("keeps only enabled network protocol types", async () => {
    get.mockResolvedValueOnce(
      sourcesResponse([
        { id: "1", name: "ftp box", type: "ftp", status: "active" },
        { id: "2", name: "share", type: "network", status: "unused" },
        { id: "3", name: "off", type: "sftp", status: "disabled" },
        { id: "4", name: "local", type: "folder", status: "active" },
        { id: "5", name: "s3", type: "s3", status: "active" },
      ]),
    );

    const result = await fetchNetworkSources();

    expect(result.map((s) => s.id)).toEqual(["1", "2"]);
  });
});

describe("listNetworkEntries", () => {
  beforeEach(() => {
    get.mockClear();
  });

  it("sends dir only when non-empty so the root stays the source default", async () => {
    get.mockResolvedValueOnce({ data: [] });
    await listNetworkEntries("s1", "");
    expect(get.mock.calls.at(-1)?.[1]).toMatchObject({ params: undefined });

    get.mockResolvedValueOnce({ data: [] });
    await listNetworkEntries("s1", "sub/dir");
    expect(get.mock.calls.at(-1)?.[0]).toBe("/api/v1/sources/s1/network/list");
    expect(get.mock.calls.at(-1)?.[1]).toMatchObject({
      params: { dir: "sub/dir" },
    });
  });
});

describe("downloadNetworkFile", () => {
  beforeEach(() => {
    get.mockClear();
  });

  it("names the File from the remote path's last segment", async () => {
    get.mockResolvedValueOnce({
      data: new Blob(["%PDF"]),
      headers: { "content-type": "application/pdf" },
    });

    const file = await downloadNetworkFile("s1", "nested/report.pdf");

    expect(file.name).toBe("report.pdf");
    expect(file.type).toBe("application/pdf");
    expect(get.mock.calls.at(-1)?.[1]).toMatchObject({
      params: { path: "nested/report.pdf" },
      responseType: "blob",
    });
  });
});

describe("uploadNetworkFile", () => {
  beforeEach(() => {
    post.mockClear();
  });

  it("posts multipart under fileInput with the target dir as a param", async () => {
    await uploadNetworkFile("s1", "done", new File(["x"], "out.pdf"));

    const [url, form, config] = post.mock.calls.at(-1) ?? [];
    expect(url).toBe("/api/v1/sources/s1/network/file");
    expect((form as FormData).get("fileInput")).toBeInstanceOf(File);
    expect(config).toMatchObject({ params: { dir: "done" } });
  });
});
