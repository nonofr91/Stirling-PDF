package stirling.software.SPDF.service.preflight;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.FindingArea;
import stirling.software.common.util.ProcessExecutor;
import stirling.software.common.util.ProcessExecutor.ProcessExecutorResult;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;

/**
 * Rendered total-ink-coverage pass, the pdfToolbox-style counterpart of the painted-area
 * approximation. Ghostscript rasterises the document to raw CMYK ({@code pamcmyk32}), then every
 * pixel's four channels are summed — the only measurement that sees ink stacking across objects,
 * images and overprints, and that ignores paint hidden under later knockouts.
 *
 * <p>Pure measurement, no fixup: returns {@code null} whenever Ghostscript is unavailable or the
 * render fails, leaving the caller free to fall back to the painted-area estimate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RenderedInkCoverage {

    /** Render resolution; TAC is measured on solid areas, so coarse pixels suffice. */
    private static final int DPI = 72;

    /** Side of a report grid cell in pixels (0.5in at {@link #DPI}). */
    private static final int CELL_PX = 36;

    /** Over-threshold cells reported per page; the highest cells win. */
    private static final int MAX_AREAS_PER_PAGE = 24;

    private final TempFileManager tempFileManager;
    private final EndpointConfiguration endpointConfiguration;

    /** Measured result: peak TAC across the document plus over-threshold cells per page. */
    public record Result(float peakPercent, Map<Integer, List<FindingArea>> areasByPage) {}

    /**
     * Renders {@code document} and measures total ink coverage; {@code null} when Ghostscript is
     * disabled, missing, or the output cannot be parsed — never throws for those cases.
     */
    public Result measure(PDDocument document, float thresholdPercent) throws IOException {
        if (!endpointConfiguration.isGroupEnabled("Ghostscript")) {
            log.warn("Rendered ink coverage requested but the Ghostscript group is disabled");
            return null;
        }
        try (TempFile input = tempFileManager.createManagedTempFile(".pdf");
                TempFile raster = tempFileManager.createManagedTempFile(".pam")) {
            document.save(input.getFile());
            if (!render(input, raster)) {
                return null;
            }
            try (InputStream in =
                    new BufferedInputStream(Files.newInputStream(raster.getFile().toPath()))) {
                return parse(in, document, thresholdPercent);
            } catch (IOException e) {
                log.warn("Could not parse pamcmyk32 output: {}", e.getMessage());
                return null;
            }
        }
    }

    private boolean render(TempFile input, TempFile raster) throws IOException {
        List<String> command =
                List.of(
                        "gs",
                        "-sDEVICE=pamcmyk32",
                        "-r" + DPI,
                        "-dNOPAUSE",
                        "-dQUIET",
                        "-dBATCH",
                        "-o",
                        raster.getAbsolutePath(),
                        input.getAbsolutePath());
        try {
            ProcessExecutorResult result =
                    ProcessExecutor.getInstance(ProcessExecutor.Processes.GHOSTSCRIPT)
                            .runCommandWithOutputHandling(command);
            if (result.getRc() != 0) {
                log.warn(
                        "Ghostscript rendered-TAC pass failed (rc={}): {}",
                        result.getRc(),
                        result.getMessages());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Walks the concatenated PAM stream (one header + W×H×4 byte frame per page), tracking the
     * per-cell TAC maximum. Pages are clipped to their CropBox in the render, so pixel cells map
     * straight back to page points.
     */
    Result parse(InputStream in, PDDocument document, float thresholdPercent) throws IOException {
        Map<Integer, List<FindingArea>> areasByPage = new LinkedHashMap<>();
        float peak = 0;
        int pageNum = 0;
        while (true) {
            PamHeader header = readHeader(in);
            if (header == null) {
                break;
            }
            pageNum++;
            if (pageNum > document.getNumberOfPages()) {
                log.warn("pamcmyk32 emitted more frames than document pages — stopping");
                break;
            }
            if (header.depth != 4 || header.maxval != 255) {
                log.warn(
                        "Unexpected pamcmyk32 frame (depth={} maxval={})",
                        header.depth,
                        header.maxval);
                return null;
            }
            int cellsX = (header.width + CELL_PX - 1) / CELL_PX;
            float[] cellMax = new float[cellsX * ((header.height + CELL_PX - 1) / CELL_PX)];
            byte[] row = new byte[header.width * 4];
            for (int y = 0; y < header.height; y++) {
                readFully(in, row);
                for (int x = 0; x < header.width; x++) {
                    int p = x * 4;
                    float tac =
                            ((row[p] & 0xff)
                                            + (row[p + 1] & 0xff)
                                            + (row[p + 2] & 0xff)
                                            + (row[p + 3] & 0xff))
                                    * 100f
                                    / 255f;
                    int cell = (y / CELL_PX) * cellsX + (x / CELL_PX);
                    if (tac > cellMax[cell]) {
                        cellMax[cell] = tac;
                    }
                    if (tac > peak) {
                        peak = tac;
                    }
                }
            }
            PDPage page = document.getPage(pageNum - 1);
            PDRectangle crop = page.getCropBox();
            float ptsPerPx = 72f / DPI;
            List<FindingArea> areas = new ArrayList<>();
            for (int cy = 0; cy * cellsX < cellMax.length; cy++) {
                for (int cx = 0; cx < cellsX; cx++) {
                    float tac = cellMax[cy * cellsX + cx];
                    if (tac <= thresholdPercent) {
                        continue;
                    }
                    float w = Math.min(CELL_PX, header.width - cx * CELL_PX) * ptsPerPx;
                    float h = Math.min(CELL_PX, header.height - cy * CELL_PX) * ptsPerPx;
                    // PAM rows run top-down; the PDF y-axis runs bottom-up.
                    float x0 = crop.getLowerLeftX() + cx * CELL_PX * ptsPerPx;
                    float y0 = crop.getUpperRightY() - (cy + 1) * CELL_PX * ptsPerPx;
                    areas.add(new FindingArea(pageNum, x0, y0, w, h, Math.round(tac) + "%"));
                    if (areas.size() >= MAX_AREAS_PER_PAGE) {
                        break;
                    }
                }
                if (areas.size() >= MAX_AREAS_PER_PAGE) {
                    break;
                }
            }
            if (!areas.isEmpty()) {
                areasByPage.put(pageNum, areas);
            }
        }
        if (pageNum == 0) {
            return null;
        }
        return new Result(peak, areasByPage);
    }

    private record PamHeader(int width, int height, int depth, int maxval) {}

    private PamHeader readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) {
                return null;
            }
            buf.write(c);
            if (c == '\n' && buf.size() >= 7) {
                byte[] b = buf.toByteArray();
                if (new String(b, b.length - 7, 7, StandardCharsets.US_ASCII).equals("ENDHDR\n")) {
                    break;
                }
            }
        }
        String text = buf.toString(StandardCharsets.US_ASCII);
        if (!text.startsWith("P7")) {
            return null;
        }
        int width = -1, height = -1, depth = -1, maxval = -1;
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2) {
                switch (parts[0]) {
                    case "WIDTH" -> width = Integer.parseInt(parts[1]);
                    case "HEIGHT" -> height = Integer.parseInt(parts[1]);
                    case "DEPTH" -> depth = Integer.parseInt(parts[1]);
                    case "MAXVAL" -> maxval = Integer.parseInt(parts[1]);
                    default -> {}
                }
            }
        }
        if (width <= 0 || height <= 0 || depth <= 0 || maxval <= 0) {
            return null;
        }
        return new PamHeader(width, height, depth, maxval);
    }

    private void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("PAM frame truncated");
            }
            off += n;
        }
    }
}
