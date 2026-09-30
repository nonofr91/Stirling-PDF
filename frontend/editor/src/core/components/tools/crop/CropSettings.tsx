import { useState, useEffect } from "react";
import { Stack, Text, Box, Group, Center, Checkbox } from "@mantine/core";
import { ActionIcon } from "@app/ui/ActionIcon";
import { useTranslation } from "react-i18next";
import { Icon } from "@app/ui/Icon";
import { CropParametersHook } from "@app/hooks/tools/crop/useCropParameters";
import {
  useViewScopedFiles,
  useViewScopedFileStubs,
} from "@app/hooks/tools/shared/useViewScopedFiles";
import CropAreaSelector from "@app/components/tools/crop/CropAreaSelector";
import CropCoordinateInputs from "@app/components/tools/crop/CropCoordinateInputs";
import PageBoxSelect from "@app/components/tools/shared/PageBoxSelect";
import PageBoxDiagram from "@app/components/tools/shared/PageBoxDiagram";
import { PageBox, PAGE_BOXES } from "@app/constants/pageBoxConstants";
import {
  readPageBoxSnapshots,
  pdfRectToPageFractions,
  PageBoxSnapshot,
} from "@app/utils/pageBoxReader";
import { useSetPageOverlay } from "@app/contexts/PageOverlayContext";
import { PAGE_BOX_COLORS } from "@app/constants/pageBoxConstants";
import { getFormFillFileId } from "@app/types/fileContext";
import { DEFAULT_CROP_AREA } from "@app/constants/cropConstants";
import { PAGE_SIZES } from "@app/constants/pageSizeConstants";
import {
  calculatePDFBounds,
  PDFBounds,
  Rectangle,
} from "@app/utils/cropCoordinates";
import { pdfWorkerManager } from "@app/services/pdfWorkerManager";
import DocumentThumbnail from "@app/components/shared/filePreview/DocumentThumbnail";

interface CropSettingsProps {
  parameters: CropParametersHook;
  disabled?: boolean;
}

const CONTAINER_SIZE = 250; // Fit within actual pane width

const CropSettings = ({ parameters, disabled = false }: CropSettingsProps) => {
  const { t } = useTranslation();
  // Preview and measure the document the crop will actually apply to, so both
  // follow the viewer when the user switches files with the tool open.
  const [selectedStub = null] = useViewScopedFileStubs();
  const [selectedFile = null] = useViewScopedFiles();

  const [pdfBounds, setPdfBounds] = useState<PDFBounds | null>(null);
  const [pageRotation, setPageRotation] = useState(0);
  const [boxSnapshots, setBoxSnapshots] = useState<
    (PageBoxSnapshot | null)[] | null
  >(null);
  const setOverlay = useSetPageOverlay();
  // The diagram and placeholders show the first page; the published overlay
  // uses every page's own boxes.
  const boxSnapshot = boxSnapshots?.[0] ?? null;

  // Named-box cropping needs every page's effective boxes — pages of a
  // heterogeneous document do not share geometry.
  useEffect(() => {
    let cancelled = false;
    if (!selectedFile || !parameters.parameters.cropToBox) {
      setBoxSnapshots(null);
      return;
    }
    readPageBoxSnapshots(selectedFile).then((s) => {
      if (!cancelled) setBoxSnapshots(s);
    });
    return () => {
      cancelled = true;
    };
  }, [selectedFile, parameters.parameters.cropToBox]);

  useEffect(() => {
    const loadPDFDimensions = async () => {
      if (!selectedStub || !selectedFile) {
        setPdfBounds(null);
        return;
      }

      try {
        // Get PDF dimensions from the actual file
        const arrayBuffer = await selectedFile.arrayBuffer();

        // Load PDF to get actual dimensions
        const pdf = await pdfWorkerManager.createDocument(arrayBuffer, {
          disableAutoFetch: true,
          disableStream: true,
          stopAtErrors: false,
        });

        const firstPage = await pdf.getPage(1);
        const viewport = firstPage.getViewport({ scale: 1 });
        setPageRotation(firstPage.rotate ?? 0);

        const pdfWidth = viewport.width;
        const pdfHeight = viewport.height;

        const bounds = calculatePDFBounds(
          pdfWidth,
          pdfHeight,
          CONTAINER_SIZE,
          CONTAINER_SIZE,
        );
        setPdfBounds(bounds);

        // Initialize crop area to full PDF if parameters are still default
        if (parameters.parameters.cropArea === DEFAULT_CROP_AREA) {
          parameters.resetToFullPDF(bounds);
        }

        // Cleanup PDF
        pdfWorkerManager.destroyDocument(pdf);
      } catch (error) {
        console.error("Failed to load PDF dimensions:", error);
        // Fallback to A4 dimensions if PDF loading fails
        const bounds = calculatePDFBounds(
          PAGE_SIZES.A4.width,
          PAGE_SIZES.A4.height,
          CONTAINER_SIZE,
          CONTAINER_SIZE,
        );
        setPdfBounds(bounds);

        if (
          parameters.parameters.cropArea.width === PAGE_SIZES.A4.width &&
          parameters.parameters.cropArea.height === PAGE_SIZES.A4.height
        ) {
          parameters.resetToFullPDF(bounds);
        }
      }
    };

    loadPDFDimensions();
  }, [selectedStub, selectedFile, parameters]);

  // Listen for tour events to set crop area
  useEffect(() => {
    const handleSetCropArea = (event: Event) => {
      const customEvent = event as CustomEvent<Rectangle>;
      if (customEvent.detail && pdfBounds) {
        parameters.setCropArea(customEvent.detail, pdfBounds);
      }
    };

    window.addEventListener("tour:setCropArea", handleSetCropArea);
    return () =>
      window.removeEventListener("tour:setCropArea", handleSetCropArea);
  }, [parameters, pdfBounds]);

  // Current crop area
  const cropArea = parameters.getCropArea();
  const { cropToBox, autoCrop, pageBox } = parameters.parameters;

  // Mirror the tool's geometry on the viewer's pages. Manual cropArea lives in
  // the pdf.js viewport space (page rotation applied), while the overlay layer
  // sits inside the page's rotation transform (unrotated space) — on a rotated
  // document the two frames differ, so the manual rect is only pushed when the
  // page carries no rotation. Named boxes are in unrotated user space and stay
  // aligned under any rotation.
  useEffect(() => {
    const documentKey = selectedFile ? getFormFillFileId(selectedFile) : null;
    if (!documentKey) {
      setOverlay(null);
      return;
    }
    if (cropToBox) {
      if (!boxSnapshots) {
        setOverlay(null);
        return;
      }
      setOverlay({
        documentKey,
        rects: [],
        rectsPerPage: boxSnapshots.map((pageSnapshot) =>
          pageSnapshot
            ? PAGE_BOXES.map((name) => ({
                ...pdfRectToPageFractions(
                  pageSnapshot.boxes[name],
                  pageSnapshot.boxes.CROP_BOX,
                ),
                color: PAGE_BOX_COLORS[name],
                dashed: !pageSnapshot.explicit.has(name),
                emphasized: name === pageBox,
                label: name.replace("_BOX", ""),
                kind: "box",
              }))
            : [],
        ),
      });
    } else if (
      !autoCrop &&
      pdfBounds &&
      pageRotation % 360 === 0 &&
      cropArea.width > 0 &&
      cropArea.height > 0
    ) {
      const viewport = {
        x: 0,
        y: 0,
        width: pdfBounds.actualWidth,
        height: pdfBounds.actualHeight,
      };
      setOverlay({
        documentKey,
        rects: [
          {
            ...pdfRectToPageFractions(cropArea, viewport),
            color: "var(--color-primary-500)",
            emphasized: true,
          },
        ],
      });
    } else {
      setOverlay(null);
    }
  }, [
    selectedFile,
    cropToBox,
    autoCrop,
    pageBox,
    boxSnapshots,
    pdfBounds,
    pageRotation,
    cropArea,
    setOverlay,
  ]);

  useEffect(() => () => setOverlay(null), [setOverlay]);

  // Handle crop area changes from the selector
  const handleCropAreaChange = (newCropArea: Rectangle) => {
    if (pdfBounds) {
      parameters.setCropArea(newCropArea, pdfBounds);
    }
  };

  // Handle manual coordinate input changes
  const handleCoordinateChange = (
    field: keyof Rectangle,
    value: number | string,
  ) => {
    const numValue = typeof value === "string" ? parseFloat(value) : value;
    if (isNaN(numValue)) return;

    const newCropArea = { ...cropArea, [field]: numValue };
    if (pdfBounds) {
      parameters.setCropArea(newCropArea, pdfBounds);
    }
  };

  // Reset to full PDF
  const handleReset = () => {
    if (pdfBounds) {
      parameters.resetToFullPDF(pdfBounds);
    }
  };

  if (!selectedStub || !pdfBounds) {
    return (
      <Center style={{ height: "200px" }}>
        <Text color="dimmed">
          {t("crop.noFileSelected", "Select a PDF file to begin cropping")}
        </Text>
      </Center>
    );
  }

  const isFullCrop = parameters.isFullPDFCrop(pdfBounds);

  return (
    <Stack gap="md" data-tour="crop-settings">
      {/* Auto-Crop Checkbox */}
      <Checkbox
        label={t("crop.autoCrop", "Auto-crop whitespace")}
        checked={parameters.parameters.autoCrop}
        onChange={(e) => {
          parameters.updateParameter("autoCrop", e.currentTarget.checked);
          if (e.currentTarget.checked) {
            parameters.updateParameter("cropToBox", false);
          }
        }}
        disabled={disabled}
      />

      {/* Crop to Page Box Checkbox + box selection */}
      <Checkbox
        label={t("crop.cropToBox", "Crop to a named page box")}
        checked={parameters.parameters.cropToBox}
        onChange={(e) => {
          parameters.updateParameter("cropToBox", e.currentTarget.checked);
          if (e.currentTarget.checked) {
            parameters.updateParameter("autoCrop", false);
          }
        }}
        disabled={disabled}
      />

      {parameters.parameters.cropToBox && (
        <PageBoxSelect
          value={parameters.parameters.pageBox}
          onChange={(v: PageBox) => parameters.updateParameter("pageBox", v)}
          disabled={disabled}
        />
      )}

      {parameters.parameters.cropToBox && boxSnapshot && (
        <PageBoxDiagram
          mediaBox={boxSnapshot.boxes.MEDIA_BOX}
          highlight={parameters.parameters.pageBox}
          background={
            selectedStub?.thumbnailUrl && boxSnapshot.rotation % 360 === 0
              ? {
                  src: selectedStub.thumbnailUrl,
                  rect: boxSnapshot.boxes.CROP_BOX,
                }
              : undefined
          }
          boxes={PAGE_BOXES.map((name) => ({
            name,
            rect: boxSnapshot.boxes[name],
            inherited: !boxSnapshot.explicit.has(name),
          }))}
        />
      )}

      {/* PDF Preview with Crop Selector - Only show for manual rectangle mode */}
      {!parameters.parameters.autoCrop && !parameters.parameters.cropToBox && (
        <Stack gap="xs">
          <Group justify="space-between" align="center">
            <Text size="sm" fw={500}>
              {t("crop.preview.title", "Crop Area Selection")}
            </Text>
            <ActionIcon
              variant="secondary"
              onClick={handleReset}
              disabled={disabled || isFullCrop}
              title={t("crop.reset", "Reset to full PDF")}
              aria-label={t("crop.reset", "Reset to full PDF")}
            >
              <Icon name="rotate-ccw" size={"1rem"} />
            </ActionIcon>
          </Group>

          <Center>
            <Box
              style={{
                width: CONTAINER_SIZE,
                height: CONTAINER_SIZE,
                border: "1px solid var(--mantine-color-gray-3)",
                borderRadius: "8px",
                backgroundColor: "var(--mantine-color-gray-0)",
                overflow: "hidden",
                position: "relative",
              }}
            >
              <CropAreaSelector
                pdfBounds={pdfBounds}
                cropArea={cropArea}
                onCropAreaChange={handleCropAreaChange}
                disabled={disabled}
              >
                <DocumentThumbnail
                  file={selectedStub}
                  thumbnail={selectedStub?.thumbnailUrl ?? null}
                  style={{
                    width: pdfBounds.thumbnailWidth,
                    height: pdfBounds.thumbnailHeight,
                    position: "absolute",
                    left: pdfBounds.offsetX,
                    top: pdfBounds.offsetY,
                  }}
                />
              </CropAreaSelector>
            </Box>
          </Center>
        </Stack>
      )}

      {/* Manual Coordinate Input - Only show for manual rectangle mode */}
      {!parameters.parameters.autoCrop && !parameters.parameters.cropToBox && (
        <CropCoordinateInputs
          cropArea={cropArea}
          onCoordinateChange={handleCoordinateChange}
          disabled={disabled}
          pdfBounds={pdfBounds}
          showAutomationInfo={false}
        />
      )}
    </Stack>
  );
};

export default CropSettings;
