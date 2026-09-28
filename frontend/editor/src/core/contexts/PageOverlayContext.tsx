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
}

export interface PageOverlayState {
  /** getFormFillFileId() of the document the rects apply to: the overlay is
      drawn only while the viewer still shows those bytes. */
  documentKey: string;
  rects: PageOverlayRect[];
}

interface PageOverlayContextValue {
  overlay: PageOverlayState | null;
  setOverlay: (overlay: PageOverlayState | null) => void;
}

const PageOverlayContext = createContext<PageOverlayContextValue | null>(null);

/**
 * What the active tool previews on the viewer's pages: crop rect, page boxes.
 * Written by tool settings panels (right pane), read by PageOverlayLayer in the
 * viewer tree — the two render in different subtrees, hence the context.
 */
export function PageOverlayProvider({ children }: { children: ReactNode }) {
  const [overlay, setOverlay] = useState<PageOverlayState | null>(null);
  const value = useMemo(() => ({ overlay, setOverlay }), [overlay]);
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
