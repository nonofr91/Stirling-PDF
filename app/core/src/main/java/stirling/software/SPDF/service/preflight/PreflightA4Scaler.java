package stirling.software.SPDF.service.preflight;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.util.Matrix;

/**
 * Fits every page of a marked-up document onto A4 — portrait for tall sources, landscape for wide
 * ones — so the annotated report prints on office paper without viewer-side "fit to page". The
 * existing content is wrapped in a scale+translate inside the page's user space, which carries the
 * painted markers along with the artwork; annotations get their rectangles remapped by the same
 * matrix and their appearances rebuilt at the new size.
 */
public final class PreflightA4Scaler {

    private static final float NOTE_OFFSET_PT = 24f;
    private static final float ALREADY_A4_TOLERANCE_PT = 0.5f;

    private PreflightA4Scaler() {}

    /**
     * Fits the document pages to A4. {@code marks} is the set of annotations added by the preflight
     * annotator — only those text notes are re-parked in the corner; annotations the source
     * document already carried keep their relative position.
     */
    public static void fitToA4(PDDocument document, Collection<? extends PDAnnotation> marks)
            throws IOException {
        // page.getAnnotations() re-wraps the same COS dictionaries in fresh PDAnnotation
        // objects, so identity has to be compared on the underlying dictionary.
        Set<COSDictionary> markSet = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDAnnotation mark : marks) {
            markSet.add(mark.getCOSObject());
        }
        for (PDPage page : document.getPages()) {
            fitPage(document, page, markSet);
        }
    }

    private static void fitPage(PDDocument document, PDPage page, Set<COSDictionary> marks)
            throws IOException {
        PDRectangle media = page.getMediaBox();
        if (media == null || media.getWidth() <= 0 || media.getHeight() <= 0) {
            return;
        }
        float unit = page.getCOSObject().getFloat(COSName.USER_UNIT, 1f);
        if (unit <= 0) {
            unit = 1f;
        }
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        // user space → displayed physical points: /Rotate, then the /UserUnit multiplier.
        AffineTransform phys = new AffineTransform();
        phys.scale(unit, unit);
        phys.concatenate(displayTransform(rotation, media));

        // Viewers display and print the effective CropBox — getCropBox() resolves
        // inherited entries and falls back to the mediaBox — so that is what gets
        // fitted to A4, not the mediaBox itself.
        Rectangle2D displayed = transformBounds(page.getCropBox(), phys);

        PDRectangle a4 =
                displayed.getHeight() >= displayed.getWidth()
                        ? PDRectangle.A4
                        : new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        if (alreadyFitted(rotation, displayed, a4)) {
            return;
        }

        double scale =
                Math.min(
                        a4.getWidth() / displayed.getWidth(),
                        a4.getHeight() / displayed.getHeight());
        AffineTransform fit = new AffineTransform();
        fit.translate(
                a4.getLowerLeftX() + (a4.getWidth() - displayed.getWidth() * scale) / 2,
                a4.getLowerLeftY() + (a4.getHeight() - displayed.getHeight() * scale) / 2);
        fit.scale(scale, scale);
        fit.translate(-displayed.getMinX(), -displayed.getMinY());

        AffineTransform physInv;
        try {
            physInv = phys.createInverse();
        } catch (NoninvertibleTransformException e) {
            return;
        }
        // The viewer applies phys (rotation + user unit) after the content, so the
        // in-stream transform must land the page on A4 through it: phys^-1 · fit · phys.
        AffineTransform m = new AffineTransform(physInv);
        m.concatenate(fit);
        m.concatenate(phys);

        // Read the explicit boxes before the mediaBox changes: their getters clip
        // to the current mediaBox, so reading them afterwards returns a shrunken
        // rectangle that would remap wrong.
        PDRectangle trim = explicitBox(page, COSName.TRIM_BOX) ? page.getTrimBox() : null;
        PDRectangle bleed = explicitBox(page, COSName.BLEED_BOX) ? page.getBleedBox() : null;

        wrapContent(document, page, m);

        // The new page's user space is the A4 seen through phys: content, boxes and
        // annotations are mapped there by m, but the page extent itself is always
        // exactly phys^-1(A4). The fitted box is the visible one, so the cropBox is
        // pinned to the full new page — anything it used to exclude stays clipped.
        PDRectangle newMedia = toRect(transformBounds(a4, physInv));
        page.setMediaBox(newMedia);
        page.setCropBox(newMedia);
        if (trim != null) {
            page.setTrimBox(toRect(transformBounds(trim, m)));
        }
        if (bleed != null) {
            page.setBleedBox(toRect(transformBounds(bleed, m)));
        }

        remapAnnotations(document, page, m, newMedia, marks);
    }

    /** Wraps the existing page content in {@code q <m> cm … Q} so everything scales together. */
    private static void wrapContent(PDDocument document, PDPage page, AffineTransform m)
            throws IOException {
        try (PDPageContentStream cs =
                new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.PREPEND, true, true)) {
            cs.saveGraphicsState();
            cs.transform(new Matrix(m));
        }
        try (PDPageContentStream cs =
                new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            cs.restoreGraphicsState();
        }
    }

    /** Whether the page dictionary carries an explicit box entry, vs. a getter fallback. */
    private static boolean explicitBox(PDPage page, COSName boxName) {
        return page.getCOSObject().getItem(boxName) != null;
    }

    /**
     * Remaps every annotation rectangle into the new user space. Preflight text notes are re-parked
     * in the top-right corner instead of scaled: their icon is rendered at a fixed size, so a rect
     * shrunk by a large-format downscale would pin it invisibly mid-page. Annotations the source
     * already carried only move with the artwork — their position is user-chosen and their
     * appearance stream is left alone. Preflight square appearances are rebuilt — the appearance
     * stream is painted in annotation space and would keep the old size otherwise.
     */
    private static void remapAnnotations(
            PDDocument document,
            PDPage page,
            AffineTransform m,
            PDRectangle newMedia,
            Set<COSDictionary> marks)
            throws IOException {
        List<PDAnnotation> annotations = page.getAnnotations();
        if (annotations == null || annotations.isEmpty()) {
            return;
        }
        int noteSlot = 0;
        for (PDAnnotation annotation : annotations) {
            PDRectangle rect = annotation.getRectangle();
            if (rect == null) {
                continue;
            }
            boolean own = marks.contains(annotation.getCOSObject());
            if (own && annotation instanceof PDAnnotationText) {
                annotation.setRectangle(
                        new PDRectangle(
                                newMedia.getUpperRightX() - NOTE_OFFSET_PT,
                                newMedia.getUpperRightY() - NOTE_OFFSET_PT * (noteSlot + 1),
                                NOTE_OFFSET_PT - 4,
                                NOTE_OFFSET_PT - 4));
                noteSlot++;
                continue;
            }
            annotation.setRectangle(toRect(transformBounds(rect, m)));
            if (own) {
                annotation.constructAppearances(document);
            }
        }
    }

    /** The user-to-display transform a viewer applies for a given {@code /Rotate} value. */
    private static AffineTransform displayTransform(int rotation, PDRectangle media) {
        float w = media.getWidth();
        float h = media.getHeight();
        return switch (rotation) {
            case 90 -> new AffineTransform(0, -1, 1, 0, 0, w);
            case 180 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 270 -> new AffineTransform(0, 1, -1, 0, h, 0);
            default -> new AffineTransform();
        };
    }

    private static boolean alreadyFitted(int rotation, Rectangle2D displayed, PDRectangle a4) {
        return rotation == 0
                && Math.abs(displayed.getWidth() - a4.getWidth()) < ALREADY_A4_TOLERANCE_PT
                && Math.abs(displayed.getHeight() - a4.getHeight()) < ALREADY_A4_TOLERANCE_PT
                && Math.abs(displayed.getMinX()) < ALREADY_A4_TOLERANCE_PT
                && Math.abs(displayed.getMinY()) < ALREADY_A4_TOLERANCE_PT;
    }

    private static Rectangle2D transformBounds(PDRectangle rect, AffineTransform m) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double[][] corners = {
            {rect.getLowerLeftX(), rect.getLowerLeftY()},
            {rect.getLowerLeftX(), rect.getUpperRightY()},
            {rect.getUpperRightX(), rect.getLowerLeftY()},
            {rect.getUpperRightX(), rect.getUpperRightY()}
        };
        for (double[] corner : corners) {
            Point2D p = m.transform(new Point2D.Double(corner[0], corner[1]), null);
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
        }
        return new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
    }

    private static PDRectangle toRect(Rectangle2D r) {
        return new PDRectangle(
                (float) r.getX(), (float) r.getY(), (float) r.getWidth(), (float) r.getHeight());
    }
}
