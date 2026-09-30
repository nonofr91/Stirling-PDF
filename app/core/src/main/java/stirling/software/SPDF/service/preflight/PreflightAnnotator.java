package stirling.software.SPDF.service.preflight;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;

import stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.FindingArea;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Severity;

/**
 * Marks a document up with a preflight report — square annotations around each located issue plus a
 * note annotation per page for findings that carry no geometry — in the spirit of PitStop's
 * annotated report. Squares are framed, never filled: a fill over a large area (e.g. a whole-page
 * trim edge) washes out the artwork it is meant to expose.
 */
public final class PreflightAnnotator {

    private static final PDFont TAG_FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDColor RED =
            new PDColor(new float[] {0.88f, 0.1f, 0.1f}, PDDeviceRGB.INSTANCE);
    private static final PDColor ORANGE =
            new PDColor(new float[] {0.95f, 0.55f, 0.05f}, PDDeviceRGB.INSTANCE);
    private static final PDColor BLUE =
            new PDColor(new float[] {0.1f, 0.45f, 0.9f}, PDDeviceRGB.INSTANCE);
    private static final float NOTE_OFFSET_PT = 24f;
    private static final float BORDER_PT = 2.5f;
    private static final float PADDING_PT = 2f;

    private PreflightAnnotator() {}

    public static void annotate(PDDocument document, List<Finding> findings) throws IOException {
        if (document.getNumberOfPages() == 0) {
            return;
        }
        // Findings without geometry land as one sticky note per affected page.
        Map<Integer, List<String>> notesByPage = new LinkedHashMap<>();
        // One frame per physical spot: distinct findings can pin the same object (e.g. an image
        // that is both low-res and soft-masked), and stacked frames render as a blur.
        Map<String, Marker> markers = new LinkedHashMap<>();
        for (Finding finding : findings) {
            String contents = finding.getCode() + " — " + finding.getMessage();
            if (finding.getAreas().isEmpty()) {
                List<Integer> pages =
                        finding.getPages().isEmpty() ? List.of(1) : finding.getPages();
                for (int p : pages) {
                    notesByPage.computeIfAbsent(p, k -> new ArrayList<>()).add(contents);
                }
                continue;
            }
            for (FindingArea area : finding.getAreas()) {
                int index = area.getPage() - 1;
                if (index < 0 || index >= document.getNumberOfPages()) {
                    continue;
                }
                PDPage page = document.getPage(index);
                PDRectangle rect = clampToPage(page, area);
                if (rect == null) {
                    continue;
                }
                markers.computeIfAbsent(markerKey(index, rect), k -> new Marker(index, rect))
                        .add(finding.getSeverity(), contents, finding.getCode(), area.getLabel());
            }
        }

        // The same frames are also painted into the page content: annotations are interactive
        // metadata some viewers (and every printer without /F Print) skip, while a report is
        // meant to be opened anywhere and still show where each issue sits.
        paintMarkers(document, markers.values());

        List<PDAnnotation> added = new ArrayList<>();
        int placed = 0;
        for (Marker marker : markers.values()) {
            PDPage page = document.getPage(marker.pageIndex);
            PDAnnotationSquare square = new PDAnnotationSquare();
            square.setRectangle(marker.rect);
            square.setColor(colorOf(marker.severity));
            square.setContents(String.join("\n", marker.contents));
            square.setTitlePopup("Print preflight");
            square.setPrinted(true);
            PDBorderStyleDictionary border = new PDBorderStyleDictionary();
            border.setWidth(BORDER_PT);
            border.setStyle(PDBorderStyleDictionary.STYLE_SOLID);
            square.setBorderStyle(border);
            addAnnotation(page, square);
            added.add(square);
            placed++;
        }

        for (Map.Entry<Integer, List<String>> e : notesByPage.entrySet()) {
            int index = e.getKey() - 1;
            if (index < 0 || index >= document.getNumberOfPages()) {
                continue;
            }
            PDPage page = document.getPage(index);
            PDRectangle crop = page.getCropBox();
            int slot = 0;
            for (String contents : e.getValue()) {
                PDAnnotationText note = new PDAnnotationText();
                note.setName(PDAnnotationText.NAME_NOTE);
                note.setTitlePopup("Print preflight");
                note.setContents(contents);
                note.setRectangle(
                        new PDRectangle(
                                crop.getUpperRightX() - NOTE_OFFSET_PT,
                                crop.getUpperRightY() - NOTE_OFFSET_PT * (slot + 1),
                                NOTE_OFFSET_PT - 4,
                                NOTE_OFFSET_PT - 4));
                note.setColor(ORANGE);
                note.setPrinted(true);
                addAnnotation(page, note);
                added.add(note);
                slot++;
            }
        }

        if (findings.isEmpty() || (placed == 0 && notesByPage.isEmpty())) {
            PDPage page = document.getPage(0);
            PDRectangle crop = page.getCropBox();
            PDAnnotationText note = new PDAnnotationText();
            note.setName(PDAnnotationText.NAME_CHECK);
            note.setTitlePopup("Print preflight");
            note.setContents("No blocking print issues detected by preflight.");
            note.setRectangle(
                    new PDRectangle(
                            crop.getUpperRightX() - NOTE_OFFSET_PT,
                            crop.getUpperRightY() - NOTE_OFFSET_PT,
                            NOTE_OFFSET_PT - 4,
                            NOTE_OFFSET_PT - 4));
            note.setColor(BLUE);
            addAnnotation(page, note);
            added.add(note);
        }

        // Appearance streams so the marks render in viewers that don't generate them —
        // only for the annotations added here; rebuilding existing /AP entries could
        // alter their rendering.
        for (PDAnnotation annotation : added) {
            annotation.constructAppearances(document);
        }
    }

    private static void paintMarkers(PDDocument document, Iterable<Marker> markers)
            throws IOException {
        Map<Integer, List<Marker>> byPage = new LinkedHashMap<>();
        for (Marker marker : markers) {
            byPage.computeIfAbsent(marker.pageIndex, k -> new ArrayList<>()).add(marker);
        }
        for (Map.Entry<Integer, List<Marker>> e : byPage.entrySet()) {
            PDPage page = document.getPage(e.getKey());
            // Tag size scales with page height — a fixed point size is invisible on
            // large-format pages (banners) and oversized on labels.
            float tagHeight = Math.min(Math.max(page.getMediaBox().getHeight() * 0.012f, 13f), 56f);
            List<PDRectangle> placedTags = new ArrayList<>();
            try (PDPageContentStream cs =
                    new PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                for (Marker marker : e.getValue()) {
                    paintMarker(cs, marker, tagHeight, placedTags);
                }
            }
        }
    }

    private static boolean overlaps(
            float x, float y, float w, float h, List<PDRectangle> placedTags) {
        for (PDRectangle tag : placedTags) {
            if (x < tag.getUpperRightX()
                    && x + w > tag.getLowerLeftX()
                    && y < tag.getUpperRightY()
                    && y + h > tag.getLowerLeftY()) {
                return true;
            }
        }
        return false;
    }

    private static void paintMarker(
            PDPageContentStream cs, Marker marker, float tagHeight, List<PDRectangle> placedTags)
            throws IOException {
        PDRectangle r = marker.rect;
        float[] rgb = colorOf(marker.severity).getComponents();
        cs.saveGraphicsState();
        cs.setLineWidth(Math.min(Math.max(r.getHeight() * 0.004f, 2f), 6f));
        cs.setStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.addRect(r.getLowerLeftX(), r.getLowerLeftY(), r.getWidth(), r.getHeight());
        cs.stroke();

        String text = bestTagText(marker, r, tagHeight * 0.62f);
        if (!text.isEmpty()) {
            float fontSize = tagHeight * 0.62f;
            float padX = tagHeight * 0.3f;
            float tagWidth = TAG_FONT.getStringWidth(text) / 1000f * fontSize + 2 * padX;
            float x = r.getLowerLeftX();
            float gap = tagHeight * 0.08f;
            float y = r.getLowerLeftY() + r.getHeight() - tagHeight - gap;
            if (r.getHeight() < tagHeight * 1.3f) {
                y = r.getLowerLeftY() + r.getHeight() + gap;
            }
            if (overlaps(x, y, tagWidth, tagHeight, placedTags)) {
                y = r.getLowerLeftY() + gap;
            }
            if (overlaps(x, y, tagWidth, tagHeight, placedTags)) {
                y = r.getLowerLeftY() + r.getHeight() + gap;
            }
            placedTags.add(new PDRectangle(x, y, tagWidth, tagHeight));
            cs.setNonStrokingColor(1f, 1f, 1f);
            cs.addRect(x, y, tagWidth, tagHeight);
            cs.fill();
            cs.setLineWidth(Math.max(tagHeight * 0.06f, 0.8f));
            cs.addRect(x, y, tagWidth, tagHeight);
            cs.stroke();
            cs.beginText();
            cs.setFont(TAG_FONT, fontSize);
            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
            cs.newLineAtOffset(x + padX, y + tagHeight * 0.26f);
            cs.showText(text);
            cs.endText();
        }
        cs.restoreGraphicsState();
    }

    /**
     * Longest readable tag that still roughly fits the marked area — a tiny image on a wide banner
     * should not carry a label that spills over its neighbours.
     */
    private static String bestTagText(Marker marker, PDRectangle r, float fontSize)
            throws IOException {
        float limit = r.getWidth() * 1.5f;
        String full = tagText(marker);
        if (widthOf(full, fontSize) <= limit) {
            return full;
        }
        String codesOnly = String.join("+", marker.codes);
        if (widthOf(codesOnly, fontSize) <= limit) {
            return codesOnly;
        }
        String first = marker.codes.get(0);
        if (marker.codes.size() > 1) {
            first += "+" + (marker.codes.size() - 1);
        }
        return widthOf(first, fontSize) <= limit ? first : marker.codes.get(0);
    }

    private static float widthOf(String text, float fontSize) throws IOException {
        return TAG_FONT.getStringWidth(text) / 1000f * fontSize;
    }

    private static String tagText(Marker marker) {
        String text = String.join("+", marker.codes);
        if (!marker.labels.isEmpty()) {
            text += " (" + String.join(", ", marker.labels) + ")";
        }
        if (text.length() > 90) {
            text = text.substring(0, 87) + "...";
        }
        StringBuilder safe = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            safe.append(c <= 0xFF ? c : '-');
        }
        return safe.toString();
    }

    private static String markerKey(int pageIndex, PDRectangle rect) {
        return pageIndex
                + ":"
                + Math.round(rect.getLowerLeftX() * 2)
                + ","
                + Math.round(rect.getLowerLeftY() * 2)
                + ","
                + Math.round(rect.getWidth() * 2)
                + ","
                + Math.round(rect.getHeight() * 2);
    }

    private static final class Marker {
        final int pageIndex;
        final PDRectangle rect;
        Severity severity = Severity.INFO;
        final List<String> contents = new ArrayList<>();
        final List<String> codes = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        Marker(int pageIndex, PDRectangle rect) {
            this.pageIndex = pageIndex;
            this.rect = rect;
        }

        void add(Severity candidate, String contents, String code, String label) {
            if (candidate.ordinal() < severity.ordinal()) {
                severity = candidate;
            }
            String line = label != null ? contents + " (" + label + ")" : contents;
            if (!this.contents.contains(line)) {
                this.contents.add(line);
            }
            if (!codes.contains(code)) {
                codes.add(code);
            }
            if (label != null && !labels.contains(label)) {
                labels.add(label);
            }
        }
    }

    private static void addAnnotation(PDPage page, PDAnnotation annotation) throws IOException {
        List<PDAnnotation> annotations = new ArrayList<>(page.getAnnotations());
        annotations.add(annotation);
        page.setAnnotations(annotations);
    }

    /**
     * Area rect padded a touch, clipped to the MediaBox — content painted beyond it is invisible
     * anyway, and a frame hanging off the page edge reads as a rendering bug. Null when the area is
     * fully outside the page.
     */
    private static PDRectangle clampToPage(PDPage page, FindingArea area) {
        PDRectangle media = page.getMediaBox();
        float x0 = Math.max(area.getX() - PADDING_PT, media.getLowerLeftX());
        float y0 = Math.max(area.getY() - PADDING_PT, media.getLowerLeftY());
        float x1 = Math.min(area.getX() + area.getWidth() + PADDING_PT, media.getUpperRightX());
        float y1 = Math.min(area.getY() + area.getHeight() + PADDING_PT, media.getUpperRightY());
        if (x1 - x0 < 0.5f || y1 - y0 < 0.5f) {
            return null;
        }
        return new PDRectangle(x0, y0, x1 - x0, y1 - y0);
    }

    private static PDColor colorOf(Severity severity) {
        return switch (severity) {
            case ERROR -> RED;
            case WARNING -> ORANGE;
            case INFO -> BLUE;
        };
    }
}
