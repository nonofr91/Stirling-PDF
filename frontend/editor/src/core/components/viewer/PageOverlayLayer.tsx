import { memo } from "react";
import { usePageOverlayState } from "@app/contexts/PageOverlayContext";
import { Z_INDEX_SIGNATURE_OVERLAY } from "@app/styles/zIndex";

export interface PageOverlayLayerProps {
  pageIndex: number;
  pageWidth: number;
  pageHeight: number;
  /** getFormFillFileId() of the rendered document. */
  documentKey: string | null;
}

/**
 * Live preview of the active tool's geometry (crop rect, page boxes) on every
 * rendered page. Rects are fractions of the page, so the layer tracks zoom for
 * free, and it sits inside the page's rotation transform, so it tracks
 * rotation for free as well. Purely visual: pointer events pass through.
 */
export const PageOverlayLayer = memo(function PageOverlayLayer({
  pageWidth,
  pageHeight,
  documentKey,
}: PageOverlayLayerProps) {
  const overlay = usePageOverlayState();

  if (!overlay || !documentKey || overlay.documentKey !== documentKey) {
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
      {overlay.rects.map((rect, i) => (
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
