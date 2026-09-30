import { createContext, useContext, useState, useMemo, ReactNode } from "react";

export interface PageOverlayRect {
  /** Fractions (0–1) of the rendered page, CSS space (top-left origin). */
  x: number;
  y: number;
  width: number;
  height: number;
  color: string;
  dashed?: boolean;
  /** Thicker stroke — the rect the user is currently steering. */
  emphasized?: boolean;
  /** Pixels to pull each side inward — decorative nudge so boxes sharing the
      same geometry render as nested frames instead of one invisible edge. */
  insetPx?: number;
  /** Short text shown inside the rect's top-left corner (e.g. "TRIM"). */
  label?: string;
  /** "box" marks a page-box rect: the toolbar's page-boxes toggle hides these
      even when a tool publishes them. Unset = tool geometry (crop rect…). */
  kind?: "box";
}

export interface PageOverlayPath {
  /** Flattened x,y pairs as fractions (0–1) of the rendered page, CSS space
      (top-left origin). Each path is a closed ring. */
  points: number[];
  color: string;
  dashed?: boolean;
  /** Interior ring of a compound shape (e.g. the counter of an "o"). */
  hole?: boolean;
}

export interface PageOverlayDrawRequest {
  /** Stroke colour for the in-progress outline. */
  color: string;
  /** Called with the closed polygon as flat x,y page-fraction pairs (top-left
      origin) once the user finishes the rough trace on this page. */
  onComplete: (pageIndex: number, points: number[]) => void;
}

export interface PageOverlayState {
  /** getFormFillFileId() of the document the rects apply to: the overlay is
      drawn only while the viewer still shows those bytes. */
  documentKey: string;
  rects: PageOverlayRect[];
  /** Per-page rects, indexed by page index. When set, this is authoritative:
      a page beyond the array draws nothing — documents whose pages differ in
      size or box insets must not reuse the first page's geometry. */
  rectsPerPage?: PageOverlayRect[][];
  /** Closed-vector previews (cut contour) — same indexing rules as rects. */
  paths?: PageOverlayPath[];
  pathsPerPage?: PageOverlayPath[][];
  /** When set, each page layer captures a freehand polygon instead of staying
      click-through — used by tools that need a rough perimeter from the user. */
  drawRequest?: PageOverlayDrawRequest;
}

interface PageOverlayContextValue {
  overlay: PageOverlayState | null;
  setOverlay: (overlay: PageOverlayState | null) => void;
  pageBoxesVisible: boolean;
  setPageBoxesVisible: (value: boolean | ((prev: boolean) => boolean)) => void;
}

const PageOverlayContext = createContext<PageOverlayContextValue | null>(null);

/**
 * What the viewer draws on top of each rendered page. `overlay` is the active
 * tool's live preview (crop rect, page boxes) written by settings panels;
 * `pageBoxesVisible` is the persistent toggle from the viewer toolbar. Read by
 * PageOverlayLayer in the viewer tree — the two render in different subtrees,
 * hence the context.
 */
export function PageOverlayProvider({ children }: { children: ReactNode }) {
  const [overlay, setOverlay] = useState<PageOverlayState | null>(null);
  const [pageBoxesVisible, setPageBoxesVisible] = useState(false);
  const value = useMemo(
    () => ({ overlay, setOverlay, pageBoxesVisible, setPageBoxesVisible }),
    [overlay, pageBoxesVisible],
  );
  return (
    <PageOverlayContext.Provider value={value}>
      {children}
    </PageOverlayContext.Provider>
  );
}

const NOOP_SETTER: PageOverlayContextValue["setOverlay"] = () => {};

/** Write side for tool settings — a no-op outside a provider (e.g. stories and
 *  tests that mount the panel standalone). */
export function useSetPageOverlay(): PageOverlayContextValue["setOverlay"] {
  return useContext(PageOverlayContext)?.setOverlay ?? NOOP_SETTER;
}

/** Read side for viewer layers — null outside a provider instead of throwing. */
export function usePageOverlayState(): PageOverlayState | null {
  return useContext(PageOverlayContext)?.overlay ?? null;
}

const NOOP_TOGGLE = () => {};

/** The toolbar's persistent "show page boxes" toggle — off and inert outside a
 *  provider. */
export function usePageBoxesVisibility(): [
  boolean,
  PageOverlayContextValue["setPageBoxesVisible"],
] {
  const ctx = useContext(PageOverlayContext);
  return [
    ctx?.pageBoxesVisible ?? false,
    ctx?.setPageBoxesVisible ?? NOOP_TOGGLE,
  ];
}
