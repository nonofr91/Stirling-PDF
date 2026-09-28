import { memo, useEffect, useMemo, useState } from "react";
import {
  usePageOverlayState,
  usePageBoxesVisibility,
  PageOverlayRect,
} from "@app/contexts/PageOverlayContext";
import {
  PageBoxSnapshot,
  pdfRectToPageFractions,
  readPageBoxSnapshot,
} from "@app/utils/pageBoxReader";
import { PAGE_BOXES, PAGE_BOX_COLORS } from "@app/constants/pageBoxConstants";
import { Z_INDEX_SIGNATURE_OVERLAY } from "@app/styles/zIndex";

export interface PageOverlayLayerProps {
  pageIndex: number;
  pageWidth: number;
  pageHeight: number;
  /** getFormFillFileId() of the rendered document. */
  documentKey: string | null;
  /** The bytes the viewer is rendering — source of the persistent box overlay. */
  file?: File | Blob | null;
}

/**
 * Draws geometry on top of the rendered page: the toolbar's persistent page
 * boxes (read per page from `file`) plus the active tool's live preview. Rects
 * are fractions of the page, so the layer tracks zoom for free, and it sits
 * inside the page's rotation transform, so it tracks rotation for free as
 * well. Purely visual: pointer events pass through.
 */
export const PageOverlayLayer = memo(function PageOverlayLayer({
  pageIndex,
  pageWidth,
  pageHeight,
  documentKey,
  file,
}: PageOverlayLayerProps) {
  const overlay = usePageOverlayState();
  const [pageBoxesVisible] = usePageBoxesVisibility();
  const [snapshot, setSnapshot] = useState<PageBoxSnapshot | null>(null);

  useEffect(() => {
    let cancelled = false;
    if (!pageBoxesVisible || !file) {
      setSnapshot(null);
      return;
    }
    readPageBoxSnapshot(file, pageIndex).then((s) => {
      if (!cancelled) setSnapshot(s);
    });
    return () => {
      cancelled = true;
    };
  }, [file, pageIndex, pageBoxesVisible]);

  const rects = useMemo<PageOverlayRect[]>(() => {
    const out: PageOverlayRect[] = [];
    if (pageBoxesVisible && snapshot) {
      for (const name of PAGE_BOXES) {
        out.push({
          ...pdfRectToPageFractions(
            snapshot.boxes[name],
            snapshot.boxes.CROP_BOX,
          ),
          color: PAGE_BOX_COLORS[name],
          dashed: !snapshot.explicit.has(name),
        });
      }
    }
    if (overlay && documentKey && overlay.documentKey === documentKey) {
      out.push(...overlay.rects);
    }
    return out;
  }, [pageBoxesVisible, snapshot, overlay, documentKey]);

  if (rects.length === 0) {
    return null;
  }

  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        pointerEvents: "none",
        zIndex: Z_INDEX_SIGNATURE_OVERLAY,
      }}
    >
      {rects.map((rect, i) => (
        <div
          key={i}
          style={{
            position: "absolute",
            left: rect.x * pageWidth,
            top: rect.y * pageHeight,
            width: rect.width * pageWidth,
            height: rect.height * pageHeight,
            border: `${rect.emphasized ? 2.5 : 1.5}px ${rect.dashed ? "dashed" : "solid"} ${rect.color}`,
            boxSizing: "border-box",
            backgroundColor: rect.emphasized
              ? "rgba(59, 130, 246, 0.08)"
              : "transparent",
          }}
        />
      ))}
    </div>
  );
});
