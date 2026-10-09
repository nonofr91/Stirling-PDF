import { memo, useMemo, useRef, useState } from "react";
import { useDocumentState } from "@embedpdf/core/react";
import {
  usePageOverlayState,
  usePageBoxesVisibility,
  PageOverlayRect,
  PageOverlayPath,
} from "@app/contexts/PageOverlayContext";
import {
  pdfRectToPageFractions,
  snapshotFromEmbedPdfPage,
} from "@app/utils/pageBoxReader";
import { PAGE_BOXES, PAGE_BOX_COLORS } from "@app/constants/pageBoxConstants";
import { Z_INDEX_SIGNATURE_OVERLAY } from "@app/styles/zIndex";

export interface PageOverlayLayerProps {
  /** EmbedPDF document this page layer renders — its document state already
      carries the page boxes, so no second parse of the file is needed. */
  documentId: string;
  pageIndex: number;
  pageWidth: number;
  pageHeight: number;
  /** getFormFillFileId() of the rendered document. */
  documentKey: string | null;
}

/**
 * Draws geometry on top of the rendered page: the toolbar's persistent page
 * boxes plus the active tool's live preview. Rects are fractions of the page,
 * so the layer tracks zoom for free, and it sits inside the page's rotation
 * transform, so it tracks rotation for free as well. Purely visual: pointer
 * events pass through.
 */
export const PageOverlayLayer = memo(function PageOverlayLayer({
  documentId,
  pageIndex,
  pageWidth,
  pageHeight,
  documentKey,
}: PageOverlayLayerProps) {
  const overlay = usePageOverlayState();
  const [pageBoxesVisible] = usePageBoxesVisibility();
  const documentState = useDocumentState(documentId);
  const page = documentState?.document?.pages?.[pageIndex];
  const snapshot = useMemo(
    () => snapshotFromEmbedPdfPage(page?.boxes, page?.rotation),
    [page],
  );

  const paths = useMemo<PageOverlayPath[]>(() => {
    if (!overlay || !documentKey || overlay.documentKey !== documentKey) {
      return [];
    }
    return overlay.pathsPerPage
      ? (overlay.pathsPerPage[pageIndex] ?? [])
      : (overlay.paths ?? []);
  }, [overlay, documentKey, pageIndex]);

  const drawRequest =
    overlay && documentKey && overlay.documentKey === documentKey
      ? overlay.drawRequest
      : undefined;
  const [draft, setDraft] = useState<number[] | null>(null);
  const capturingRef = useRef(false);

  const rects = useMemo<PageOverlayRect[]>(() => {
    const out: PageOverlayRect[] = [];
    const toolRects =
      overlay && documentKey && overlay.documentKey === documentKey
        ? overlay.rectsPerPage
          ? (overlay.rectsPerPage[pageIndex] ?? [])
          : overlay.rects
        : [];
    const toolPublishesBoxes = toolRects.some((r) => r.kind === "box");

    // The toolbar toggle is the master switch for page-box drawing.
    // A tool publishing box rects wins over the persisted file's own boxes —
    // its preview is the data being edited. Geometry previews (crop rect) are
    // unaffected by the toggle.
    if (pageBoxesVisible && snapshot && !toolPublishesBoxes) {
      for (const name of PAGE_BOXES) {
        out.push({
          ...pdfRectToPageFractions(
            snapshot.boxes[name],
            snapshot.boxes.CROP_BOX,
          ),
          color: PAGE_BOX_COLORS[name],
          dashed: !snapshot.explicit.has(name),
          label: name.replace("_BOX", ""),
          kind: "box",
        });
      }
    }
    out.push(...toolRects.filter((r) => pageBoxesVisible || r.kind !== "box"));

    // On a plain PDF every box falls back to the MediaBox, so all five
    // borders hug the page edge and the toggle looks dead. Coincident box
    // rects get a per-rank inward nudge so they render as nested frames.
    const coincidentRank = new Map<string, number>();
    for (const r of out) {
      if (r.kind !== "box") continue;
      const sig = [r.x, r.y, r.width, r.height]
        .map((n) => n.toFixed(4))
        .join(",");
      const rank = coincidentRank.get(sig) ?? 0;
      coincidentRank.set(sig, rank + 1);
      r.insetPx = rank * 4;
    }
    return out;
  }, [pageBoxesVisible, snapshot, overlay, documentKey, pageIndex]);

  if (!drawRequest && rects.length === 0 && paths.length === 0) {
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
      {/* icon-lint-allow: runtime-generated-svg -- overlay polygons are computed from page geometry */}
      {(paths.length > 0 || drawRequest) && (
        <svg
          viewBox="0 0 1 1"
          preserveAspectRatio="none"
          style={{
            position: "absolute",
            inset: 0,
            width: "100%",
            height: "100%",
          }}
        >
          {paths.map((path, i) => (
            <polygon
              key={i}
              points={path.points.join(" ")}
              fill="none"
              stroke={path.color}
              strokeWidth={1.5}
              strokeDasharray={path.dashed || path.hole ? "4 3" : undefined}
              vectorEffect="non-scaling-stroke"
            />
          ))}
          {draft && draft.length >= 4 && (
            <polyline
              points={draft.join(" ")}
              fill="none"
              stroke={drawRequest?.color}
              strokeWidth={1.5}
              strokeDasharray="5 4"
              vectorEffect="non-scaling-stroke"
            />
          )}
        </svg>
      )}
      {drawRequest && (
        <div
          style={{
            position: "absolute",
            inset: 0,
            // The overlay container is pointer-events:none so it stays
            // click-through; the capture layer opts back in while armed.
            pointerEvents: "auto",
            cursor: "crosshair",
            touchAction: "none",
          }}
          onPointerDown={(e) => {
            // keep the gesture off the page pan/scroll handlers
            e.stopPropagation();
            const r = e.currentTarget.getBoundingClientRect();
            capturingRef.current = true;
            e.currentTarget.setPointerCapture(e.pointerId);
            setDraft([
              (e.clientX - r.left) / r.width,
              (e.clientY - r.top) / r.height,
            ]);
          }}
          onPointerMove={(e) => {
            if (!capturingRef.current) return;
            e.stopPropagation();
            const r = e.currentTarget.getBoundingClientRect();
            const x = (e.clientX - r.left) / r.width;
            const y = (e.clientY - r.top) / r.height;
            setDraft((d) => {
              if (!d) return [x, y];
              const lx = d[d.length - 2];
              const ly = d[d.length - 1];
              // pixel-space distance so the simplification tracks zoom
              if (Math.hypot(x - lx, y - ly) * r.width < 6) return d;
              return [...d, x, y];
            });
          }}
          onPointerUp={(e) => {
            e.stopPropagation();
            capturingRef.current = false;
            setDraft((d) => {
              if (d && d.length >= 6) {
                drawRequest.onComplete(pageIndex, d);
              }
              return null;
            });
          }}
          onPointerCancel={() => {
            capturingRef.current = false;
            setDraft(null);
          }}
        />
      )}
      {rects.map((rect, i) => {
        const inset = rect.insetPx ?? 0;
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              left: rect.x * pageWidth + inset,
              top: rect.y * pageHeight + inset,
              width: Math.max(1, rect.width * pageWidth - 2 * inset),
              height: Math.max(1, rect.height * pageHeight - 2 * inset),
              border: `${rect.emphasized ? 2.5 : 1.5}px ${rect.dashed ? "dashed" : "solid"} ${rect.color}`,
              boxSizing: "border-box",
              backgroundColor: rect.emphasized
                ? "rgba(59, 130, 246, 0.08)"
                : "transparent",
            }}
          >
            {rect.label && (
              <span
                style={{
                  position: "absolute",
                  top: 1,
                  left: 3,
                  fontSize: 10,
                  fontWeight: 600,
                  lineHeight: 1.2,
                  color: rect.color,
                  background: "var(--c-surface)",
                  padding: "0 2px",
                  borderRadius: 2,
                  whiteSpace: "nowrap",
                }}
              >
                {rect.label}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
});
