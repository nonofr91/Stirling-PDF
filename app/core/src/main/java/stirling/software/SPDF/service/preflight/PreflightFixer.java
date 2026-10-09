package stirling.software.SPDF.service.preflight;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceN;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.security.PrintPreflightReport;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;
import stirling.software.SPDF.service.preflight.PreflightGraphicsEngine.ImageUse;
import stirling.software.common.util.PageBleedGenerator;
import stirling.software.common.util.PageBleedGenerator.BleedEdges;
import stirling.software.common.util.PageBleedGenerator.BleedMethod;

/**
 * Automatic corrections (PitStop-style fixups) for findings the preflight can report. Each fixup is
 * opt-in via {@code request.fixups}; an empty list applies every fixup that finds work to do.
 * Fixups mutate the document — this endpoint returns a new copy, never the source bytes.
 *
 * <p>Deliberately not implemented (they need more than token rewriting): transparency flattening is
 * delegated to Ghostscript, and embedding licensed substitute fonts stays pending.
 */
@Slf4j
public final class PreflightFixer {

    private static final float MM_TO_POINTS = 72f / 25.4f;
    private static final float JPEG_QUALITY = 0.9f;

    /** Decoded input plus converted output sample bytes one image remap may hold at once. */
    private static final long MAX_REMAP_BYTES = 256L * 1024 * 1024;

    private static final int MAX_TUPLE_CACHE_ENTRIES = 1 << 16;

    public enum Code {
        REMOVE_JAVASCRIPT,
        REMOVE_ATTACHMENTS,
        FLATTEN_FORM,
        NORMALIZE_USER_UNIT,
        SET_OUTPUT_INTENT,
        REMOVE_ANNOTATIONS_IN_TRIM,
        MERGE_SPOT_ALIASES,
        DOWNSAMPLE_IMAGES,
        EXTEND_BLEED,
        SET_MISSING_BOXES,
        REMOVE_EMPTY_PAGES,
        DISCARD_CROPBOX,
        CLIP_TO_CROPBOX,
        ENABLE_LAYER_PRINTING,
        REMOVE_INVISIBLE_TEXT,
        REGISTRATION_TO_BLACK,
        OVERPRINT_BLACK_TEXT,
        KNOCKOUT_WHITE,
        PURE_BLACK_TEXT,
        SPOT_TO_CMYK,
        REDUCE_INK_COVERAGE,
        RGB_TO_CMYK,
        FLATTEN_TRANSPARENCY,
        TEXT_TO_OUTLINES
    }

    /** Fixups implemented by {@link PreflightStreamFixer} — they share one token pass. */
    private static final Set<Code> STREAM_FIXUPS =
            Set.of(
                    Code.REMOVE_INVISIBLE_TEXT,
                    Code.REGISTRATION_TO_BLACK,
                    Code.OVERPRINT_BLACK_TEXT,
                    Code.KNOCKOUT_WHITE,
                    Code.PURE_BLACK_TEXT,
                    Code.SPOT_TO_CMYK,
                    Code.REDUCE_INK_COVERAGE);

    /**
     * Fixups delegated to a Ghostscript pass on the saved bytes — they need a real colour engine,
     * not token rewriting. The map value is the finding that makes the fixup applicable when the
     * request's {@code fixups} list is empty; an explicit list runs them unconditionally.
     */
    static final Map<Code, String> GS_FIXUP_FINDINGS =
            Map.of(
                    Code.RGB_TO_CMYK, "COLOR_RGB_USED",
                    Code.FLATTEN_TRANSPARENCY, "TRANSPARENCY",
                    Code.TEXT_TO_OUTLINES, "FONT_NOT_EMBEDDED");

    private PreflightFixer() {}

    /**
     * Applies the requested fixups to {@code document}. Returns the codes that changed something —
     * a fixup whose target is absent is silently skipped, so the caller learns what it actually did
     * from the returned list.
     */
    public static List<String> apply(
            PDDocument document, PrintPreflightRequest request, PrintPreflightReport report) {
        Set<Code> wanted = resolveWanted(request.getFixups());
        List<String> applied = new ArrayList<>();

        if (wanted.contains(Code.REMOVE_JAVASCRIPT) && removeJavascript(document)) {
            applied.add(Code.REMOVE_JAVASCRIPT.name());
        }
        if (wanted.contains(Code.REMOVE_ATTACHMENTS) && removeAttachments(document)) {
            applied.add(Code.REMOVE_ATTACHMENTS.name());
        }
        if (wanted.contains(Code.FLATTEN_FORM) && flattenForm(document)) {
            applied.add(Code.FLATTEN_FORM.name());
        }
        if (wanted.contains(Code.NORMALIZE_USER_UNIT) && normalizeUserUnit(document)) {
            applied.add(Code.NORMALIZE_USER_UNIT.name());
        }
        if (wanted.contains(Code.SET_OUTPUT_INTENT)
                && setOutputIntent(document, request.getIccProfile())) {
            applied.add(Code.SET_OUTPUT_INTENT.name());
        }
        if (wanted.contains(Code.REMOVE_ANNOTATIONS_IN_TRIM) && removeAnnotationsInTrim(document)) {
            applied.add(Code.REMOVE_ANNOTATIONS_IN_TRIM.name());
        }
        if (wanted.contains(Code.MERGE_SPOT_ALIASES) && mergeSpotAliases(document, report)) {
            applied.add(Code.MERGE_SPOT_ALIASES.name());
        }
        if (wanted.contains(Code.SET_MISSING_BOXES) && setMissingBoxes(document)) {
            applied.add(Code.SET_MISSING_BOXES.name());
        }
        if (wanted.contains(Code.DISCARD_CROPBOX) && discardCropBox(document)) {
            applied.add(Code.DISCARD_CROPBOX.name());
        }
        if (wanted.contains(Code.EXTEND_BLEED) && extendBleed(document, request)) {
            applied.add(Code.EXTEND_BLEED.name());
        }
        if (wanted.contains(Code.DOWNSAMPLE_IMAGES) && downsampleImages(document, request)) {
            applied.add(Code.DOWNSAMPLE_IMAGES.name());
        }
        if (wanted.contains(Code.ENABLE_LAYER_PRINTING) && enableLayerPrinting(document)) {
            applied.add(Code.ENABLE_LAYER_PRINTING.name());
        }
        // One token pass applies every wanted stream-level fixup.
        Set<Code> streamWanted = new LinkedHashSet<>(wanted);
        streamWanted.retainAll(STREAM_FIXUPS);
        if (!streamWanted.isEmpty()
                && PreflightStreamFixer.apply(
                        document, streamWanted, request.getMaxInkCoveragePercent())) {
            streamWanted.forEach(code -> applied.add(code.name()));
        }
        // Spot image XObjects follow the paint-op rewrite: their pixels live behind a `Do`, out
        // of the token pass's reach, and get remapped through the document's own tint transform.
        if (wanted.contains(Code.SPOT_TO_CMYK)
                && convertSpotImages(document)
                && !applied.contains(Code.SPOT_TO_CMYK.name())) {
            applied.add(Code.SPOT_TO_CMYK.name());
        }
        // CMYK image pixels get the same UCR reduction as painted fills — photos are where press
        // TAC actually blows past the limit, and no token rewrite can reach them.
        if (wanted.contains(Code.REDUCE_INK_COVERAGE)
                && reduceImageInkCoverage(document, request.getMaxInkCoveragePercent())
                && !applied.contains(Code.REDUCE_INK_COVERAGE.name())) {
            applied.add(Code.REDUCE_INK_COVERAGE.name());
        }
        // Clip runs after every geometry fixup has settled (boxes, bleed), and only when the
        // analysis saw paint out there — a clean page keeps its original content stream untouched.
        if (wanted.contains(Code.CLIP_TO_CROPBOX)
                && hasFinding(report, "OBJECT_OUTSIDE_PAGE")
                && clipToCropBox(document)) {
            applied.add(Code.CLIP_TO_CROPBOX.name());
        }
        // Destructive page removal runs last so every other fixup sees stable page indexes.
        if (wanted.contains(Code.REMOVE_EMPTY_PAGES) && removeEmptyPages(document, report)) {
            applied.add(Code.REMOVE_EMPTY_PAGES.name());
        }
        return applied;
    }

    /**
     * The Ghostscript-level fixups the request asks for. An explicit {@code fixups} list runs them
     * unconditionally; the empty-list "everything applicable" mode only keeps the ones whose
     * finding fired in the report, so a clean document pays no Ghostscript round-trip.
     */
    public static Set<Code> ghostscriptWanted(
            PrintPreflightRequest request, PrintPreflightReport report) {
        Set<Code> wanted = new LinkedHashSet<>(resolveWanted(request.getFixups()));
        wanted.retainAll(GS_FIXUP_FINDINGS.keySet());
        if (request.getFixups() != null && !request.getFixups().isEmpty()) {
            return wanted;
        }
        Set<String> findings = new LinkedHashSet<>();
        for (PrintPreflightReport.Finding f : report.getFindings()) {
            findings.add(f.getCode());
        }
        wanted.removeIf(code -> !findings.contains(GS_FIXUP_FINDINGS.get(code)));
        return wanted;
    }

    /**
     * Spot-coloured image XObjects (Separation/DeviceN colour space with a CMYK-family alternate)
     * are decoded to raw samples, remapped through the document's tint transform and re-encoded as
     * CMYK-family images — the picture the paint-op rewrite cannot reach. Explicit masks and
     * matte-free soft masks follow the image; colour-key masks, matte-bearing soft masks, non-8-bit
     * samples, stencils and images over {@link #MAX_REMAP_BYTES} stay untouched.
     */
    private static boolean convertSpotImages(PDDocument document) {
        Map<COSBase, PDImageXObject> images = new LinkedHashMap<>();
        Set<COSBase> visited = new LinkedHashSet<>();
        for (PDPage page : document.getPages()) {
            try {
                collectImages(
                        page.getResources(), visited, images, PreflightFixer::isSpotColorSpace);
            } catch (IOException e) {
                log.debug("Spot image scan skipped a page: {}", e.getMessage());
            }
        }
        boolean changed = false;
        for (Map.Entry<COSBase, PDImageXObject> entry : images.entrySet()) {
            try {
                PDImageXObject converted = convertSpotImage(document, entry.getValue());
                if (converted != null) {
                    changed |= replaceImageReferences(document, entry.getKey(), converted);
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Spot image conversion skipped an image: {}", e.getMessage());
            }
        }
        return changed;
    }

    private static void collectImages(
            PDResources resources,
            Set<COSBase> visited,
            Map<COSBase, PDImageXObject> out,
            java.util.function.Predicate<PDImageXObject> keep)
            throws IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xo = resources.getXObject(name);
            if (xo instanceof PDImageXObject image && keep.test(image)) {
                out.putIfAbsent(image.getCOSObject(), image);
            } else if (xo instanceof PDFormXObject form) {
                collectImages(form.getResources(), visited, out, keep);
            }
        }
        for (COSName name : resources.getPatternNames()) {
            if (resources.getPattern(name) instanceof PDTilingPattern tiling) {
                collectImages(tiling.getResources(), visited, out, keep);
            }
        }
    }

    private static boolean isSpotColorSpace(PDImageXObject image) {
        try {
            PDColorSpace cs = image.getColorSpace();
            return cs instanceof PDSeparation || cs instanceof PDDeviceN;
        } catch (IOException e) {
            return false;
        }
    }

    private static PDImageXObject convertSpotImage(PDDocument document, PDImageXObject image)
            throws IOException {
        if (image.getBitsPerComponent() != 8 || image.isStencil()) {
            return null;
        }
        PDColorSpace cs = image.getColorSpace();
        PDFunction tint = PreflightStreamFixer.tintTransformOf(cs);
        PDColorSpace alternate = PreflightStreamFixer.alternateOf(cs);
        if (tint == null || !PreflightStreamFixer.isCmykSpace(alternate)) {
            return null;
        }
        COSDictionary srcDict =
                dereference(image.getCOSObject()) instanceof COSDictionary dict ? dict : null;
        if (srcDict != null && maskOrMatteBlocksConversion(srcDict)) {
            return null;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int comps = cs.getNumberOfComponents();
        int outComps = alternate.getNumberOfComponents();
        long pixels = (long) w * h;
        // Bounded before decoding: otherwise input array, output array and tuple memo of a huge
        // photo compound into a heap-exhausting allocation the per-image catch cannot contain.
        if (pixels <= 0 || pixels > MAX_REMAP_BYTES / (comps + outComps)) {
            return null;
        }
        int expected = (int) (pixels * comps);
        byte[] raw;
        try (InputStream in = image.getCOSObject().createInputStream()) {
            raw = in.readNBytes(expected);
        }
        if (raw.length < expected) {
            return null;
        }
        byte[] out = remapSpotSamples(image, raw, comps, tint, outComps);
        if (out == null) {
            return null;
        }
        return rebuildImage(document, image, out, w, h, alternate);
    }

    /**
     * What the sample remap cannot carry over: a colour-key {@code /Mask} array's ranges index
     * samples of the old colour space, and a {@code /Matte} — declared on the image or on its soft
     * mask — is expressed in the parent spot space, which the non-linear tint transform cannot
     * re-map. Both keep the image unconverted rather than corrupt its transparency.
     */
    private static boolean maskOrMatteBlocksConversion(COSDictionary imageDict) {
        COSBase mask = dereference(imageDict.getItem(COSName.MASK));
        if (mask != null && !(mask instanceof COSStream)) {
            return true;
        }
        if (imageDict.getItem(COSName.MATTE) != null) {
            return true;
        }
        return dereference(imageDict.getItem(COSName.SMASK)) instanceof COSDictionary smask
                && smask.getItem(COSName.MATTE) != null;
    }

    /**
     * Per-pixel tint-transform remap. {@code /Decode} ranges are applied first so inverted or
     * narrowed sample scales stay honest. Separations go through a 256-entry LUT; DeviceN caches
     * per input tuple, capped at {@link #MAX_TUPLE_CACHE_ENTRIES} — typical spot art has few
     * distinct tuples, so neither pays a function eval per pixel.
     */
    private static byte[] remapSpotSamples(
            PDImageXObject image, byte[] raw, int comps, PDFunction tint, int outComps)
            throws IOException {
        COSArray decode = image.getDecode();
        float[] dMin = new float[comps];
        float[] dMax = new float[comps];
        java.util.Arrays.fill(dMax, 1f);
        if (decode != null) {
            float[] range = decode.toFloatArray();
            for (int i = 0; i < comps && 2 * i + 1 < range.length; i++) {
                dMin[i] = range[2 * i];
                dMax[i] = range[2 * i + 1];
            }
        }
        int pixels = raw.length / comps;
        byte[] out = new byte[pixels * outComps];
        if (comps == 1) {
            byte[][] lut = new byte[256][];
            for (int s = 0; s < 256; s++) {
                lut[s] =
                        quantize(
                                tint.eval(new float[] {dMin[0] + (s / 255f) * (dMax[0] - dMin[0])}),
                                outComps);
            }
            for (int p = 0; p < pixels; p++) {
                System.arraycopy(lut[raw[p] & 0xFF], 0, out, p * outComps, outComps);
            }
            return out;
        }
        Map<Long, byte[]> cache = new java.util.HashMap<>();
        float[] input = new float[comps];
        for (int p = 0; p < pixels; p++) {
            int base = p * comps;
            long key = 0;
            for (int i = 0; i < comps; i++) {
                key = (key << 8) | (raw[base + i] & 0xFF);
                input[i] = dMin[i] + (raw[base + i] & 0xFF) / 255f * (dMax[i] - dMin[i]);
            }
            byte[] conv = comps <= 8 ? cache.get(key) : null;
            if (conv == null) {
                conv = quantize(tint.eval(input), outComps);
                // A photo mints a fresh tuple per pixel: past the cap the input degrades to
                // per-pixel evals instead of growing the memo with the image area.
                if (comps <= 8 && cache.size() < MAX_TUPLE_CACHE_ENTRIES) {
                    cache.put(key, conv);
                }
            }
            System.arraycopy(conv, 0, out, p * outComps, outComps);
        }
        return out;
    }

    private static byte[] quantize(float[] comps, int outComps) {
        byte[] q = new byte[outComps];
        for (int i = 0; i < outComps; i++) {
            float v = i < comps.length ? comps[i] : 0f;
            q[i] = (byte) Math.round(Math.max(0, Math.min(1, v)) * 255);
        }
        return q;
    }

    /**
     * Pixel-wise TAC reduction on DeviceCMYK image XObjects, the image counterpart of the painted
     * fill rewrite: samples decode through {@code /Decode}, pass through the same {@link
     * PreflightStreamFixer#reduceTac} the stream fixer uses, and re-encode at 8-bit with a default
     * decode. Images under the limit keep their original bytes. Colour-key masks, matte entries,
     * stencils, non-8-bit samples and non-CMYK spaces stay untouched; soft masks and explicit bit
     * masks follow the rebuilt image.
     */
    private static boolean reduceImageInkCoverage(PDDocument document, int maxInkCoveragePercent) {
        Map<COSBase, PDImageXObject> images = new LinkedHashMap<>();
        Set<COSBase> visited = new LinkedHashSet<>();
        for (PDPage page : document.getPages()) {
            try {
                collectImages(
                        page.getResources(), visited, images, PreflightFixer::isDeviceCmykImage);
            } catch (IOException e) {
                log.debug("Image TAC scan skipped a page: {}", e.getMessage());
            }
        }
        boolean changed = false;
        for (Map.Entry<COSBase, PDImageXObject> entry : images.entrySet()) {
            try {
                PDImageXObject reduced =
                        reduceCmykImage(document, entry.getValue(), maxInkCoveragePercent);
                if (reduced != null) {
                    changed |= replaceImageReferences(document, entry.getKey(), reduced);
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Image TAC reduction skipped an image: {}", e.getMessage());
            }
        }
        return changed;
    }

    private static boolean isDeviceCmykImage(PDImageXObject image) {
        try {
            return image.getColorSpace() instanceof PDDeviceCMYK;
        } catch (IOException e) {
            return false;
        }
    }

    private static PDImageXObject reduceCmykImage(
            PDDocument document, PDImageXObject image, int maxInkCoveragePercent)
            throws IOException {
        if (image.getBitsPerComponent() != 8 || image.isStencil()) {
            return null;
        }
        COSDictionary srcDict =
                dereference(image.getCOSObject()) instanceof COSDictionary dict ? dict : null;
        if (srcDict != null && maskOrMatteBlocksConversion(srcDict)) {
            return null;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        long pixels = (long) w * h;
        if (pixels <= 0 || pixels > MAX_REMAP_BYTES / 4) {
            return null;
        }
        int expected = (int) (pixels * 4);
        byte[] raw;
        try (InputStream in = image.getCOSObject().createInputStream()) {
            raw = in.readNBytes(expected);
        }
        if (raw.length < expected) {
            return null;
        }
        byte[] out = reduceCmykSamples(image, raw, maxInkCoveragePercent / 100f);
        if (out == null) {
            return null;
        }
        return rebuildImage(document, image, out, w, h, PDDeviceCMYK.INSTANCE);
    }

    /**
     * Per-pixel UCR over raw CMYK samples; {@code /Decode} ranges decode before reduction so
     * inverted scales stay honest, and the result re-quantises onto the default range (the rebuilt
     * image drops the custom {@code /Decode}). {@code null} when no pixel exceeds the limit —
     * unchanged bytes are not worth a fresh stream.
     */
    private static byte[] reduceCmykSamples(PDImageXObject image, byte[] raw, float tacLimit)
            throws IOException {
        COSArray decode = image.getDecode();
        float[] dMin = new float[4];
        float[] dMax = new float[4];
        java.util.Arrays.fill(dMax, 1f);
        if (decode != null) {
            float[] range = decode.toFloatArray();
            for (int i = 0; i < 4 && 2 * i + 1 < range.length; i++) {
                dMin[i] = range[2 * i];
                dMax[i] = range[2 * i + 1];
            }
        }
        int pixels = raw.length / 4;
        byte[] out = new byte[pixels * 4];
        boolean reduced = false;
        float[] px = new float[4];
        for (int p = 0; p < pixels; p++) {
            int base = p * 4;
            for (int i = 0; i < 4; i++) {
                px[i] = dMin[i] + (raw[base + i] & 0xFF) / 255f * (dMax[i] - dMin[i]);
            }
            float[] fixed = PreflightStreamFixer.reduceTac(px, tacLimit);
            for (int i = 0; i < 4; i++) {
                if (fixed[i] != px[i]) {
                    reduced = true;
                }
                out[base + i] = (byte) Math.round(Math.max(0, Math.min(1, fixed[i])) * 255);
            }
        }
        return reduced ? out : null;
    }

    private static PDImageXObject rebuildImage(
            PDDocument document,
            PDImageXObject source,
            byte[] samples,
            int w,
            int h,
            PDColorSpace space)
            throws IOException {
        PDStream stream = new PDStream(document);
        COSDictionary dict = stream.getCOSObject();
        dict.setItem(COSName.SUBTYPE, COSName.IMAGE);
        dict.setInt(COSName.WIDTH, w);
        dict.setInt(COSName.HEIGHT, h);
        dict.setInt(COSName.BITS_PER_COMPONENT, 8);
        dict.setItem(COSName.COLORSPACE, space.getCOSObject());
        COSBase srcObj = source.getCOSObject();
        COSBase src = srcObj instanceof COSObject ref ? ref.getObject() : srcObj;
        if (src instanceof COSDictionary srcDict) {
            copyEntry(srcDict, dict, COSName.SMASK);
            copyEntry(srcDict, dict, COSName.INTERPOLATE);
            // An explicit mask survives: its bits address pixels, not samples. A colour-key array
            // would index the old colour space — such images are declined before remap — and a
            // matte can never stay valid through a non-linear tint transform.
            if (dereference(srcDict.getItem(COSName.MASK)) instanceof COSStream) {
                copyEntry(srcDict, dict, COSName.MASK);
            }
        }
        try (java.io.OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(samples);
        }
        return new PDImageXObject(stream, null);
    }

    private static void copyEntry(COSDictionary src, COSDictionary dst, COSName key) {
        COSBase v = src.getItem(key);
        if (v != null) {
            dst.setItem(key, v);
        }
    }

    private static boolean hasFinding(PrintPreflightReport report, String code) {
        for (PrintPreflightReport.Finding f : report.getFindings()) {
            if (code.equals(f.getCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wraps each page's content in {@code q <CropBox> re W n … Q} so paint beyond the crop edge can
     * no longer render. Objects stay in the file — hidden, not deleted — and the analyser's clip
     * tracking then clears OBJECT_OUTSIDE_PAGE, which keeps the fix preview honest.
     */
    private static boolean clipToCropBox(PDDocument document) {
        boolean changed = false;
        for (PDPage page : document.getPages()) {
            PDRectangle clip = page.getCropBox();
            if (clip == null) {
                continue;
            }
            try {
                if (page.getContents() == null) {
                    continue;
                }
                PDStream wrapped = new PDStream(document);
                try (java.io.OutputStream out = wrapped.createOutputStream(COSName.FLATE_DECODE)) {
                    String head =
                            String.format(
                                    Locale.ROOT,
                                    "q%n%.4f %.4f %.4f %.4f re%nW%nn%n",
                                    clip.getLowerLeftX(),
                                    clip.getLowerLeftY(),
                                    clip.getWidth(),
                                    clip.getHeight());
                    out.write(head.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    java.util.Iterator<PDStream> contents = page.getContentStreams();
                    while (contents.hasNext()) {
                        try (InputStream in = contents.next().createInputStream()) {
                            in.transferTo(out);
                        }
                        out.write('\n');
                    }
                    out.write("Q\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }
                page.setContents(wrapped);
                changed = true;
            } catch (IOException | RuntimeException e) {
                log.debug("Clip-to-CropBox skipped a page: {}", e.getMessage());
            }
        }
        return changed;
    }

    static Set<Code> resolveWanted(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of(Code.values());
        }
        // "NONE" lets a profile or caller say "no fixups" where the empty list already means "all".
        if (requested.stream().anyMatch(s -> "NONE".equalsIgnoreCase(s.trim()))) {
            return Set.of();
        }
        Set<Code> wanted = new LinkedHashSet<>();
        for (String raw : requested) {
            try {
                wanted.add(Code.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.debug("Ignoring unknown fixup code '{}'", raw);
            }
        }
        return wanted;
    }

    private static boolean removeJavascript(PDDocument document) {
        PDDocumentNameDictionary names = document.getDocumentCatalog().getNames();
        if (names != null && names.getJavaScript() != null) {
            names.setJavascript(null);
            return true;
        }
        return false;
    }

    private static boolean removeAttachments(PDDocument document) {
        PDDocumentNameDictionary names = document.getDocumentCatalog().getNames();
        if (names != null && names.getEmbeddedFiles() != null) {
            names.setEmbeddedFiles(null);
            return true;
        }
        return false;
    }

    /**
     * Flattens AcroForm widgets into page content; a present XFA stream is dropped first so the
     * AcroForm rendition wins — that is the only rendition print RIPs can see anyway.
     */
    private static boolean flattenForm(PDDocument document) {
        PDAcroForm form = document.getDocumentCatalog().getAcroForm();
        if (form == null || form.getFields().isEmpty()) {
            return false;
        }
        try {
            if (form.hasXFA()) {
                form.setXFA(null);
            }
            form.flatten();
            return true;
        } catch (IOException e) {
            log.warn("AcroForm flatten failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Every page box is expressed in the page's user units; scaling all declared boxes by the unit
     * and clearing {@code /UserUnit} keeps identical geometry on a standard grid.
     */
    private static boolean normalizeUserUnit(PDDocument document) {
        boolean changed = false;
        for (PDPage page : document.getPages()) {
            float unit = page.getUserUnit();
            if (Math.abs(unit - 1f) <= 0.001f || unit <= 0f) {
                continue;
            }
            for (COSName boxName :
                    List.of(
                            COSName.MEDIA_BOX,
                            COSName.CROP_BOX,
                            COSName.BLEED_BOX,
                            COSName.TRIM_BOX,
                            COSName.ART_BOX)) {
                COSBase item = page.getCOSObject().getItem(boxName);
                if (item == null) {
                    continue;
                }
                PDRectangle box = toRectangle(item);
                if (box == null) {
                    continue;
                }
                setBox(
                        page,
                        boxName,
                        new PDRectangle(
                                box.getLowerLeftX() * unit,
                                box.getLowerLeftY() * unit,
                                box.getWidth() * unit,
                                box.getHeight() * unit));
                changed = true;
            }
            page.getCOSObject().removeItem(COSName.USER_UNIT);
        }
        return changed;
    }

    private static boolean setOutputIntent(PDDocument document, MultipartFile iccProfile) {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        try {
            if (!catalog.getOutputIntents().isEmpty()) {
                return false;
            }
            String identifier;
            try (InputStream icc = openIccStream(iccProfile)) {
                if (icc == null) {
                    return false;
                }
                PDOutputIntent intent = new PDOutputIntent(document, icc);
                identifier =
                        iccProfile != null && !iccProfile.isEmpty()
                                ? iccProfile.getOriginalFilename()
                                : "sRGB2014";
                intent.setInfo(identifier);
                intent.setRegistryName("http://www.color.org");
                intent.setOutputConditionIdentifier(identifier);
                intent.setOutputCondition(identifier);
                catalog.addOutputIntent(intent);
            }
            return true;
        } catch (IOException e) {
            log.warn("Could not attach output intent: {}", e.getMessage());
            return false;
        }
    }

    private static InputStream openIccStream(MultipartFile iccProfile) throws IOException {
        if (iccProfile != null && !iccProfile.isEmpty()) {
            return iccProfile.getInputStream();
        }
        return PreflightFixer.class.getResourceAsStream("/icc/sRGB2014.icc");
    }

    /** Same predicate the check uses: flagged-for-print annotation overlapping the trim. */
    private static boolean removeAnnotationsInTrim(PDDocument document) {
        boolean changed = false;
        for (PDPage page : document.getPages()) {
            PDRectangle trim = page.getTrimBox();
            if (trim == null) {
                continue;
            }
            List<PDAnnotation> keep;
            try {
                keep = new ArrayList<>();
                boolean dropped = false;
                for (PDAnnotation annotation : page.getAnnotations()) {
                    PDRectangle rect = annotation.getRectangle();
                    if (annotation.isPrinted() && rect != null && overlaps(trim, rect)) {
                        dropped = true;
                    } else {
                        keep.add(annotation);
                    }
                }
                if (dropped) {
                    page.setAnnotations(keep);
                    changed = true;
                }
            } catch (IOException e) {
                log.debug("Annotation cleanup skipped a page: {}", e.getMessage());
            }
        }
        return changed;
    }

    /**
     * Spot names that normalize to the same ink (Pantone 485 C vs pms 485cv) print as separate
     * plates; renaming every matching Separation/DeviceN colorant to the group's first name merges
     * them back onto one plate.
     */
    private static boolean mergeSpotAliases(PDDocument document, PrintPreflightReport report) {
        Set<String> printSpots = new LinkedHashSet<>(report.getFacts().getSpotColors());
        printSpots.removeAll(report.getFacts().getTechnicalSeparations());
        Map<String, String> renameTo = new LinkedHashMap<>();
        for (List<String> group : PrintPreflightService.spotAliasGroups(printSpots)) {
            String canonical = group.get(0);
            for (String alias : group) {
                if (!alias.equals(canonical)) {
                    renameTo.put(alias, canonical);
                }
            }
        }
        if (renameTo.isEmpty()) {
            return false;
        }
        boolean changed = false;
        Set<COSBase> visited = new LinkedHashSet<>();
        for (PDPage page : document.getPages()) {
            try {
                changed |= renameSeparations(page.getResources(), renameTo, visited);
            } catch (IOException e) {
                log.debug("Spot alias merge skipped a page: {}", e.getMessage());
            }
        }
        return changed;
    }

    private static boolean renameSeparations(
            PDResources resources, Map<String, String> renameTo, Set<COSBase> visited)
            throws IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) {
            return false;
        }
        boolean changed = false;
        COSDictionary csDict = resources.getCOSObject().getCOSDictionary(COSName.COLORSPACE);
        if (csDict != null) {
            for (COSName key : new ArrayList<>(csDict.keySet())) {
                COSBase value = dereference(csDict.getDictionaryObject(key));
                if (value instanceof COSArray array) {
                    changed |= renameInColorSpace(array, renameTo);
                }
            }
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xo = resources.getXObject(name);
            if (xo instanceof PDFormXObject form) {
                changed |= renameSeparations(form.getResources(), renameTo, visited);
            }
        }
        for (COSName name : resources.getPatternNames()) {
            PDAbstractPattern pattern = resources.getPattern(name);
            if (pattern instanceof PDTilingPattern tiling) {
                changed |= renameSeparations(tiling.getResources(), renameTo, visited);
            }
        }
        return changed;
    }

    private static boolean renameInColorSpace(COSArray array, Map<String, String> renameTo) {
        boolean changed = false;
        if (array.size() == 0 || !(array.get(0) instanceof COSName kind)) {
            return false;
        }
        if (COSName.SEPARATION.equals(kind) && array.size() >= 2) {
            changed |= renameColorant(array, 1, renameTo);
        } else if (COSName.DEVICEN.equals(kind)
                && array.size() >= 2
                && array.get(1) instanceof COSArray colorants) {
            for (int i = 0; i < colorants.size(); i++) {
                changed |= renameColorant(colorants, i, renameTo);
            }
        } else if ((COSName.INDEXED.equals(kind) || COSName.PATTERN.equals(kind))
                && array.size() >= 2
                && dereference(array.get(1)) instanceof COSArray base) {
            changed |= renameInColorSpace(base, renameTo);
        }
        return changed;
    }

    private static boolean renameColorant(
            COSArray colorants, int index, Map<String, String> renameTo) {
        String canonical = renameTo.get(colorants.getName(index));
        if (canonical != null) {
            colorants.setName(index, canonical);
            return true;
        }
        return false;
    }

    /**
     * Flips every OCG whose print usage is OFF back to ON — the check's "layers disabled for print"
     * means the press room layer would silently drop out of the plates.
     */
    private static boolean enableLayerPrinting(PDDocument document) {
        PDOptionalContentProperties ocProps = document.getDocumentCatalog().getOCProperties();
        if (ocProps == null) {
            return false;
        }
        boolean changed = false;
        for (PDOptionalContentGroup group : ocProps.getOptionalContentGroups()) {
            COSDictionary usage = group.getCOSObject().getCOSDictionary(COSName.USAGE);
            COSDictionary print = usage == null ? null : usage.getCOSDictionary(COSName.PRINT);
            if (print != null && COSName.OFF.equals(print.getCOSName(COSName.PRINT_STATE))) {
                print.setItem(COSName.PRINT_STATE, COSName.ON);
                changed = true;
            }
        }
        return changed;
    }

    /** Declares TrimBox = CropBox where the page never said otherwise — the check's fallback. */
    private static boolean setMissingBoxes(PDDocument document) {
        boolean changed = false;
        for (PDPage page : document.getPages()) {
            if (page.getCOSObject().getItem(COSName.TRIM_BOX) == null) {
                page.setTrimBox(page.getCropBox());
                changed = true;
            }
        }
        return changed;
    }

    /** Drops declared CropBoxes so every page displays at full MediaBox size. */
    private static boolean discardCropBox(PDDocument document) {
        boolean changed = false;
        for (PDPage page : document.getPages()) {
            if (page.getCOSObject().getItem(COSName.CROP_BOX) != null) {
                page.getCOSObject().removeItem(COSName.CROP_BOX);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Mirrors the page edge into the missing bleed (vector MIRROR, no rasterization), declares the
     * grown BleedBox and enlarges MediaBox/CropBox so the new area stays reachable. Pages that
     * already bleed enough — or that have no content to mirror — are untouched.
     */
    private static boolean extendBleed(PDDocument document, PrintPreflightRequest request) {
        float requiredPt = request.getRequiredBleedMm() * MM_TO_POINTS;
        if (requiredPt <= 0) {
            return false;
        }
        boolean changed = false;
        int pageIndex = 0;
        for (PDPage page : document.getPages()) {
            PDRectangle trim = page.getTrimBox();
            PDRectangle bleed = page.getBleedBox();
            float[] sides = PrintPreflightService.bleedWidthPerSide(bleed, trim);
            // sides order: [left, bottom, right, top]; BleedEdges: (left, right, bottom, top).
            // Paint only what's missing; declare the full required width — existing wider bleed
            // is kept by taking the max on every side.
            BleedEdges gaps =
                    sides == null
                            ? new BleedEdges(requiredPt, requiredPt, requiredPt, requiredPt)
                            : new BleedEdges(
                                    Math.max(0, requiredPt - sides[0]),
                                    Math.max(0, requiredPt - sides[2]),
                                    Math.max(0, requiredPt - sides[1]),
                                    Math.max(0, requiredPt - sides[3]));
            if (!gaps.any()) {
                pageIndex++;
                continue;
            }
            BleedEdges total =
                    sides == null
                            ? gaps
                            : new BleedEdges(
                                    Math.max(requiredPt, sides[0]),
                                    Math.max(requiredPt, sides[2]),
                                    Math.max(requiredPt, sides[1]),
                                    Math.max(requiredPt, sides[3]));
            PDRectangle target = total.unionWith(trim, false, 0, 0);
            if (page.hasContents()) {
                try {
                    PageBleedGenerator.generateBleed(
                            document,
                            page,
                            pageIndex,
                            trim,
                            gaps,
                            BleedMethod.MIRROR,
                            true,
                            300,
                            0f);
                } catch (IOException e) {
                    log.debug(
                            "Bleed generation failed on page {}: {}",
                            pageIndex + 1,
                            e.getMessage());
                    pageIndex++;
                    continue;
                }
            }
            // A BleedBox outside the MediaBox is dead geometry: grow the clip-bound boxes.
            page.setBleedBox(target);
            page.setMediaBox(union(page.getMediaBox(), target));
            page.setCropBox(union(page.getCropBox(), target));
            changed = true;
            pageIndex++;
        }
        return changed;
    }

    /**
     * Re-encodes images drawn above {@code maxImageDpi} at that target resolution, matched back to
     * resources by object identity. Skips 1-bit art, stencils, soft-masked images and colour spaces
     * a BufferedImage round-trip cannot represent faithfully (CMYK, separations).
     */
    private static boolean downsampleImages(PDDocument document, PrintPreflightRequest request) {
        float maxDpi = request.getMaxImageDpi();
        if (maxDpi <= 0) {
            return false;
        }
        // image COS object → largest placement (lowest effective dpi means biggest on page)
        Map<COSBase, PDImageXObject> imageByObj = new LinkedHashMap<>();
        Map<COSBase, Float> minDpiByImage = new LinkedHashMap<>();
        int pageIndex = 0;
        for (PDPage page : document.getPages()) {
            PreflightGraphicsEngine engine =
                    new PreflightGraphicsEngine(page, request.getMaxInkCoveragePercent());
            try {
                engine.processPage(page);
            } catch (Exception e) {
                log.debug("Downsample scan skipped page {}: {}", pageIndex + 1, e.getMessage());
            }
            for (ImageUse use : engine.getImages()) {
                if (!(use.image instanceof PDImageXObject pix)
                        || use.technical
                        || use.softMasked
                        || use.bitsPerComponent <= 1
                        || !Double.isFinite(use.effectiveDpi)
                        || use.effectiveDpi <= maxDpi) {
                    continue;
                }
                imageByObj.putIfAbsent(pix.getCOSObject(), pix);
                minDpiByImage.merge(pix.getCOSObject(), (float) use.effectiveDpi, Math::min);
            }
            pageIndex++;
        }
        if (minDpiByImage.isEmpty()) {
            return false;
        }
        boolean changed = false;
        try {
            for (Map.Entry<COSBase, Float> entry : minDpiByImage.entrySet()) {
                PDImageXObject source = imageByObj.get(entry.getKey());
                PDImageXObject replaced = resample(document, source, maxDpi, entry.getValue());
                if (replaced == null) {
                    continue;
                }
                changed |= replaceImageReferences(document, entry.getKey(), replaced);
            }
        } catch (IOException e) {
            log.warn("Image downsampling stopped early: {}", e.getMessage());
        }
        return changed;
    }

    private static PDImageXObject resample(
            PDDocument document, PDImageXObject image, float targetDpi, float currentDpi)
            throws IOException {
        try {
            if (!(image.getColorSpace() instanceof PDDeviceRGB)
                    && !(image.getColorSpace() instanceof PDDeviceGray)
                    && !(image.getColorSpace() instanceof PDICCBased)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        BufferedImage source;
        try {
            source = image.getImage();
        } catch (IOException e) {
            return null;
        }
        if (source == null) {
            return null;
        }
        float scale = targetDpi / currentDpi;
        int targetW = Math.max(1, Math.round(source.getWidth() * scale));
        int targetH = Math.max(1, Math.round(source.getHeight() * scale));
        if (targetW >= source.getWidth() || targetH >= source.getHeight()) {
            return null;
        }
        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, targetW, targetH, null);
        g.dispose();
        // JPEG keeps photographic weight down; lossless for already-Flate art and gray.
        COSBase filters =
                image.getCOSObject() instanceof COSStream stream ? stream.getFilters() : null;
        boolean jpegSource = COSName.DCT_DECODE.equals(filters) || filterListContains(filters);
        if (jpegSource) {
            return JPEGFactory.createFromImage(document, scaled, JPEG_QUALITY, (int) targetDpi);
        }
        return LosslessFactory.createFromImage(document, scaled);
    }

    private static boolean replaceImageReferences(
            PDDocument document, COSBase oldImage, PDImageXObject replacement) throws IOException {
        boolean changed = false;
        Set<COSBase> visited = new LinkedHashSet<>();
        for (PDPage page : document.getPages()) {
            changed |= replaceInResources(page.getResources(), oldImage, replacement, visited);
        }
        return changed;
    }

    private static boolean replaceInResources(
            PDResources resources,
            COSBase oldImage,
            PDImageXObject replacement,
            Set<COSBase> visited)
            throws IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) {
            return false;
        }
        boolean changed = false;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xo = resources.getXObject(name);
            if (xo instanceof PDImageXObject image && image.getCOSObject() == oldImage) {
                resources.put(name, replacement);
                changed = true;
            } else if (xo instanceof PDFormXObject form) {
                changed |= replaceInResources(form.getResources(), oldImage, replacement, visited);
            }
        }
        for (COSName name : resources.getPatternNames()) {
            if (resources.getPattern(name) instanceof PDTilingPattern tiling) {
                changed |=
                        replaceInResources(tiling.getResources(), oldImage, replacement, visited);
            }
        }
        return changed;
    }

    private static boolean filterListContains(COSBase filters) {
        if (filters instanceof COSArray fa) {
            for (int i = 0; i < fa.size(); i++) {
                if (COSName.DCT_DECODE.equals(fa.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean removeEmptyPages(PDDocument document, PrintPreflightReport report) {
        List<Integer> empties = report.getFacts().getEmptyPages();
        if (empties.isEmpty() || document.getNumberOfPages() - empties.size() < 1) {
            return false;
        }
        // Remove back to front so lower indexes stay valid while deleting.
        for (int pageNum : new TreeSet<>(empties).descendingSet()) {
            if (pageNum >= 1 && pageNum <= document.getNumberOfPages()) {
                document.removePage(pageNum - 1);
            }
        }
        return true;
    }

    private static COSBase dereference(COSBase base) {
        return base instanceof COSObject ref ? ref.getObject() : base;
    }

    private static boolean overlaps(PDRectangle a, PDRectangle b) {
        return b.getLowerLeftX() < a.getUpperRightX()
                && b.getUpperRightX() > a.getLowerLeftX()
                && b.getLowerLeftY() < a.getUpperRightY()
                && b.getUpperRightY() > a.getLowerLeftY();
    }

    private static PDRectangle union(PDRectangle a, PDRectangle b) {
        float llx = Math.min(a.getLowerLeftX(), b.getLowerLeftX());
        float lly = Math.min(a.getLowerLeftY(), b.getLowerLeftY());
        return new PDRectangle(
                llx,
                lly,
                Math.max(a.getUpperRightX(), b.getUpperRightX()) - llx,
                Math.max(a.getUpperRightY(), b.getUpperRightY()) - lly);
    }

    private static PDRectangle toRectangle(COSBase item) {
        if (dereference(item) instanceof COSArray array && array.size() >= 4) {
            return new PDRectangle(array);
        }
        return null;
    }

    private static void setBox(PDPage page, COSName name, PDRectangle rect) {
        COSArray array = new COSArray();
        array.add(new COSFloat(rect.getLowerLeftX()));
        array.add(new COSFloat(rect.getLowerLeftY()));
        array.add(new COSFloat(rect.getUpperRightX()));
        array.add(new COSFloat(rect.getUpperRightY()));
        page.getCOSObject().setItem(name, array);
    }
}
