package stirling.software.SPDF.service.preflight;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceN;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import lombok.extern.slf4j.Slf4j;

/**
 * Content-stream fixups — the corrections that cannot be done at dictionary level. One pass over
 * each page (plus nested form XObjects and tiling patterns) tracks the graphics state — colour,
 * overprint flags, text state, CTM — and rewrites only the operators the requested fixups target.
 *
 * <p>Overprint wraps use {@code q /GS gs … Q}: graphics state survives the pair, so injected
 * ExtGState entries are the only residue. Colour injections re-emit the original colour tokens
 * after the show operator because {@code q}/{@code Q} does not save colour.
 */
@Slf4j
final class PreflightStreamFixer {

    private static final float WHITE_EPSILON = 0.04f;
    private static final float CHROMATIC_EPSILON = 0.02f;
    private static final float BLACK_MIN_TINT = 0.5f;
    // Same bound the check records: text flagged below this effective size.
    private static final float RICH_BLACK_MAX_PT = 24f;

    private static final Set<String> TEXT_SHOW_OPS = Set.of("Tj", "TJ", "'", "\"");
    private static final Set<String> FILL_OPS = Set.of("f", "f*", "F", "B", "B*", "b", "b*");
    private static final Set<String> STROKE_OPS = Set.of("S", "s");
    private static final Set<String> REGISTRATION_NAMES = Set.of("all", "registration");
    private static final Set<String> BLACK_COLORANTS =
            Set.of("black", "noir", "schwarz", "nero", "preto", "negro", "svart");
    private static final Map<String, Integer> DEVICE_N_TO_CMYK =
            Map.of(
                    "cyan", 0,
                    "c", 0,
                    "magenta", 1,
                    "m", 1,
                    "yellow", 2,
                    "y", 2,
                    "black", 3,
                    "k", 3,
                    "all", 3,
                    "registration", 3);

    private final PDDocument document;
    private final Set<PreflightFixer.Code> wanted;
    private boolean changed;

    private PreflightStreamFixer(PDDocument document, Set<PreflightFixer.Code> wanted) {
        this.document = document;
        this.wanted = wanted;
    }

    /**
     * Applies the requested content-stream fixups everywhere streams exist. Returns true when at
     * least one stream was rewritten.
     */
    static boolean apply(PDDocument document, Set<PreflightFixer.Code> wanted) {
        PreflightStreamFixer fixer = new PreflightStreamFixer(document, wanted);
        Set<COSBase> visited = new LinkedHashSet<>();
        for (PDPage page : document.getPages()) {
            try {
                fixer.rewritePage(page, visited);
            } catch (IOException | RuntimeException e) {
                log.debug("Stream fixups skipped a page: {}", e.getMessage());
            }
        }
        return fixer.changed;
    }

    private void rewritePage(PDPage page, Set<COSBase> visited) throws IOException {
        PDResources resources = page.getResources();
        List<Object> rewritten = rewrite(page, resources);
        if (rewritten != null) {
            PDStream stream = new PDStream(document);
            writeTokens(stream.getCOSObject(), rewritten);
            page.setContents(stream);
        }
        walkResources(resources, visited);
    }

    private void walkResources(PDResources resources, Set<COSBase> visited) throws IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xo = resources.getXObject(name);
            if (xo instanceof PDFormXObject form && visited.add(form.getCOSObject())) {
                List<Object> rewritten = rewrite(form, form.getResources());
                if (rewritten != null) {
                    writeTokens(form.getCOSObject(), rewritten);
                }
                walkResources(form.getResources(), visited);
            }
        }
        for (COSName name : resources.getPatternNames()) {
            PDAbstractPattern pattern = resources.getPattern(name);
            if (pattern instanceof PDTilingPattern tiling && visited.add(tiling.getCOSObject())) {
                List<Object> rewritten = rewrite(tiling, tiling.getResources());
                if (rewritten != null) {
                    writeTokens(tiling.getContentStream().getCOSObject(), rewritten);
                }
                walkResources(tiling.getResources(), visited);
            }
        }
    }

    /**
     * Parses one content stream and returns the rewritten tokens, or null when no fixup fired. A
     * page's multi-stream Contents array collapses into a single stream — equivalent PDF.
     */
    private List<Object> rewrite(PDContentStream content, PDResources resources)
            throws IOException {
        List<Object> tokens = parse(content);
        if (tokens.isEmpty()) {
            return null;
        }
        RewriteState state = new RewriteState(resources);
        List<Object> out = new ArrayList<>(tokens.size() + 16);
        List<Object> operands = new ArrayList<>(8);
        boolean touched = false;
        for (Object token : tokens) {
            if (!(token instanceof Operator op)) {
                operands.add(token);
                continue;
            }
            List<Object> replacement = transform(state, op, operands);
            if (replacement == null) {
                replacement = emit(operands, op);
            } else {
                touched = true;
            }
            for (Object emitted : replacement) {
                state.track(emitted);
            }
            out.addAll(replacement);
            operands.clear();
        }
        out.addAll(operands); // dangling operands on malformed streams pass through untouched
        return touched ? out : null;
    }

    private static List<Object> parse(PDContentStream content) throws IOException {
        PDFStreamParser parser = new PDFStreamParser(content);
        List<Object> tokens = new ArrayList<>();
        Object token;
        while ((token = parser.parseNextToken()) != null) {
            tokens.add(token);
        }
        return tokens;
    }

    private static void writeTokens(COSStream stream, List<Object> tokens) throws IOException {
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            new ContentStreamWriter(out).writeTokens(tokens);
        }
    }

    private static List<Object> emit(List<Object> operands, Operator op) {
        List<Object> out = new ArrayList<>(operands.size() + 1);
        out.addAll(operands);
        out.add(op);
        return out;
    }

    /**
     * Gives each wanted fixup a shot at the current operator; the first match wins its replacement
     * tokens. Order matters: dropped invisible text must not also get an overprint wrap.
     */
    private List<Object> transform(RewriteState s, Operator op, List<Object> operands) {
        String name = op.getName();
        boolean colourOp = isColourSetter(name, false);
        boolean strokeColourOp = isColourSetter(name, true);
        if (wanted.contains(PreflightFixer.Code.REGISTRATION_TO_BLACK)) {
            if ("cs".equals(name) || "CS".equals(name)) {
                List<Object> out = rewriteRegistrationColorspace(s, operands, "cs".equals(name));
                if (out != null) {
                    return out;
                }
            }
            if ("sc".equals(name)
                    || "scn".equals(name)
                    || "SC".equals(name)
                    || "SCN".equals(name)) {
                List<Object> out =
                        rewriteRegistrationPaint(
                                s, operands, "SC".equals(name) || "SCN".equals(name));
                if (out != null) {
                    return out;
                }
            }
        }
        // Any other colour-setter supersedes a pending registration rewrite for that component.
        if (colourOp) {
            s.pendingFillReg = null;
        }
        if (strokeColourOp) {
            s.pendingStrokeReg = null;
        }
        if (wanted.contains(PreflightFixer.Code.REMOVE_INVISIBLE_TEXT)
                && TEXT_SHOW_OPS.contains(name)
                && s.tr == 3) {
            // ' and " also advance to the next line — keep the move, drop the glyphs.
            return "'".equals(name) || "\"".equals(name)
                    ? List.of(Operator.getOperator("T*"))
                    : List.of();
        }
        if (TEXT_SHOW_OPS.contains(name) && s.inText) {
            if (wanted.contains(PreflightFixer.Code.PURE_BLACK_TEXT)) {
                List<Object> out = rewriteRichBlackText(s, operands, op);
                if (out != null) {
                    return out;
                }
            }
            if (wanted.contains(PreflightFixer.Code.OVERPRINT_BLACK_TEXT)
                    && needsBlackOverprint(s)) {
                return wrap(s, operands, op, true);
            }
        }
        if (wanted.contains(PreflightFixer.Code.KNOCKOUT_WHITE)) {
            boolean textWhite =
                    TEXT_SHOW_OPS.contains(name)
                            && s.inText
                            && ((trFills(s.tr) && s.fill.isWhite() && s.opFill)
                                    || (trStrokes(s.tr) && s.stroke.isWhite() && s.opStroke));
            boolean pathWhite =
                    (FILL_OPS.contains(name) && s.fill.isWhite() && s.opFill)
                            || (STROKE_OPS.contains(name) && s.stroke.isWhite() && s.opStroke);
            if (textWhite || pathWhite) {
                return wrap(s, operands, op, false);
            }
        }
        return null;
    }

    private static boolean isColourSetter(String op, boolean stroke) {
        return stroke
                ? switch (op) {
                    case "CS", "SC", "SCN", "G", "RG", "K" -> true;
                    default -> false;
                }
                : switch (op) {
                    case "cs", "sc", "scn", "g", "rg", "k" -> true;
                    default -> false;
                };
    }

    private static boolean trFills(int tr) {
        return tr == 0 || tr == 2 || tr == 4 || tr == 6;
    }

    private static boolean trStrokes(int tr) {
        return tr == 1 || tr == 2 || tr == 5 || tr == 6;
    }

    /**
     * A {@code cs} on a Separation named All/Registration, or a DeviceN whose colorants all map to
     * process channels, is rewritten to DeviceCMYK; the pending map tells the next {@code scn} how
     * to translate its operands.
     */
    private List<Object> rewriteRegistrationColorspace(
            RewriteState s, List<Object> operands, boolean nonStroking) {
        if (operands.size() != 1 || !(operands.get(0) instanceof COSName csName)) {
            return null;
        }
        PDColorSpace cs = s.resolveColorSpace(operands);
        int[] map = cs == null ? null : registrationMap(cs);
        if (map == null) {
            return null;
        }
        if (nonStroking) {
            s.pendingFillReg = map;
        } else {
            s.pendingStrokeReg = map;
        }
        List<Object> out = new ArrayList<>(2);
        out.add(COSName.DEVICECMYK);
        out.add(Operator.getOperator(nonStroking ? "cs" : "CS"));
        return out;
    }

    /** Channel map DeviceN→CMYK for all-process colorants; {@code [3]} for All/Registration. */
    private static int[] registrationMap(PDColorSpace cs) {
        if (cs instanceof PDSeparation sep
                && sep.getColorantName() != null
                && REGISTRATION_NAMES.contains(
                        sep.getColorantName().trim().toLowerCase(Locale.ROOT))) {
            return new int[] {3};
        }
        if (cs instanceof PDDeviceN deviceN) {
            List<String> colorants = deviceN.getColorantNames();
            int[] map = new int[colorants.size()];
            for (int i = 0; i < colorants.size(); i++) {
                Integer channel =
                        DEVICE_N_TO_CMYK.get(colorants.get(i).trim().toLowerCase(Locale.ROOT));
                if (channel == null) {
                    return null;
                }
                map[i] = channel;
            }
            return map;
        }
        return null;
    }

    private List<Object> rewriteRegistrationPaint(
            RewriteState s, List<Object> operands, boolean stroke) {
        int[] map = stroke ? s.pendingStrokeReg : s.pendingFillReg;
        if (map == null || operands.size() != map.length) {
            return null;
        }
        float[] cmyk = new float[4];
        for (int i = 0; i < map.length; i++) {
            if (!(operands.get(i) instanceof COSNumber num)) {
                return null;
            }
            cmyk[map[i]] = Math.max(cmyk[map[i]], num.floatValue());
        }
        if (stroke) {
            s.pendingStrokeReg = null;
        } else {
            s.pendingFillReg = null;
        }
        List<Object> out = new ArrayList<>(5);
        for (float v : cmyk) {
            out.add(new COSFloat(v));
        }
        out.add(Operator.getOperator(stroke ? "K" : "k"));
        return out;
    }

    /**
     * Text painted in pure process black without overprint knocks out the plates underneath — the
     * misregistration risk the check flags. Wrapping the show operator in an overprinting ExtGState
     * fixes it; {@code OPM 1} keeps the K tint intact.
     */
    private boolean needsBlackOverprint(RewriteState s) {
        if (trFills(s.tr) && s.fill.isPureBlack() && !s.opFill) {
            return true;
        }
        return trStrokes(s.tr) && s.stroke.isPureBlack() && !s.opStroke;
    }

    /**
     * Rich-black text below {@value RICH_BLACK_MAX_PT}pt renders on every plate — swapping the fill
     * for K-only keeps the darkness without the registration blur. The original colour tokens are
     * re-emitted after the show operator so following text keeps its colour.
     */
    private List<Object> rewriteRichBlackText(RewriteState s, List<Object> operands, Operator op) {
        if (effectiveFontSize(s) >= RICH_BLACK_MAX_PT) {
            return null;
        }
        if (trFills(s.tr) && s.fill.isRichBlack() && s.fill.tokens() != null) {
            List<Object> out = new ArrayList<>(10 + operands.size());
            out.addAll(cmykTokens(s.fill.richToBlack(), false));
            out.addAll(emit(operands, op));
            out.addAll(s.fill.tokens());
            return out;
        }
        if (!trFills(s.tr)
                && trStrokes(s.tr)
                && s.stroke.isRichBlack()
                && s.stroke.tokens() != null) {
            List<Object> out = new ArrayList<>(10 + operands.size());
            out.addAll(cmykTokens(s.stroke.richToBlack(), true));
            out.addAll(emit(operands, op));
            out.addAll(s.stroke.tokens());
            return out;
        }
        return null;
    }

    private static List<Object> cmykTokens(float k, boolean stroke) {
        List<Object> out = new ArrayList<>(5);
        out.add(new COSFloat(0));
        out.add(new COSFloat(0));
        out.add(new COSFloat(0));
        out.add(new COSFloat(k));
        out.add(Operator.getOperator(stroke ? "K" : "k"));
        return out;
    }

    /** fontSize × the smaller axis scale of Tm×CTM — the check's effective size. */
    private static float effectiveFontSize(RewriteState s) {
        Matrix m = s.tm.multiply(s.ctm);
        float sx = Math.abs(m.getScalingFactorX());
        float sy = Math.abs(m.getScalingFactorY());
        return s.fontSize * Math.min(sx, sy);
    }

    /** {@code q /GS gs …orig… Q} — the wrap survives state tracking on the emitted tokens. */
    private List<Object> wrap(
            RewriteState s, List<Object> operands, Operator op, boolean overprintOn) {
        if (s.resources == null) {
            return null;
        }
        COSName gsName = gsResourceName(s.resources, overprintOn);
        List<Object> out = new ArrayList<>(operands.size() + 5);
        out.add(Operator.getOperator("q"));
        out.add(gsName);
        out.add(Operator.getOperator("gs"));
        out.addAll(emit(operands, op));
        out.add(Operator.getOperator("Q"));
        return out;
    }

    /**
     * One shared ExtGState per resources dictionary — {@code PFO} forces overprint on, {@code PFN}
     * forces it off. Both set {@code OPM 1} so a 0 % channel does not knock out under black
     * overprint.
     */
    private static COSName gsResourceName(PDResources resources, boolean overprintOn) {
        COSDictionary dict = extGStateDict(resources);
        String base = overprintOn ? "PFO" : "PFN";
        for (int i = 0; ; i++) {
            COSName name = COSName.getPDFName(i == 0 ? base : base + i);
            COSBase existing = dict.getItem(name);
            if (existing == null) {
                COSDictionary gs = new COSDictionary();
                gs.setBoolean(COSName.getPDFName("op"), overprintOn);
                gs.setBoolean(COSName.OP, overprintOn);
                gs.setInt(COSName.OPM, 1);
                dict.setItem(name, gs);
                return name;
            }
            if (dereference(existing) instanceof COSDictionary gs && matches(gs, overprintOn)) {
                return name;
            }
        }
    }

    private static COSDictionary extGStateDict(PDResources resources) {
        COSDictionary res = resources.getCOSObject();
        COSBase existing = res.getItem(COSName.EXT_G_STATE);
        COSDictionary dict = existing != null ? (COSDictionary) dereference(existing) : null;
        if (dict == null) {
            dict = new COSDictionary();
            res.setItem(COSName.EXT_G_STATE, dict);
        }
        return dict;
    }

    private static boolean matches(COSDictionary gs, boolean overprintOn) {
        boolean stroke = gs.getBoolean(COSName.OP, false);
        boolean fill = gs.getBoolean(COSName.OP_NS, stroke);
        return fill == overprintOn && stroke == overprintOn;
    }

    private static COSBase dereference(COSBase base) {
        return base instanceof COSObject ref ? ref.getObject() : base;
    }

    /** Painted colour at token level: resolved colour space, components, replayable tokens. */
    private record Colour(PDColorSpace cs, float[] comps, List<Object> tokens) {

        /** Mirrors {@code PreflightGraphicsEngine.isWhite}. */
        boolean isWhite() {
            if (cs == null || comps == null) {
                return false;
            }
            if (cs instanceof PDDeviceGray || iccN(1)) {
                return comps.length >= 1 && comps[0] >= 1f - WHITE_EPSILON;
            }
            if (cs instanceof PDDeviceRGB || iccN(3)) {
                return comps.length >= 3
                        && comps[0] >= 1f - WHITE_EPSILON
                        && comps[1] >= 1f - WHITE_EPSILON
                        && comps[2] >= 1f - WHITE_EPSILON;
            }
            if (cs instanceof PDDeviceCMYK || cs instanceof PDDeviceN || iccN(4)) {
                for (float v : comps) {
                    if (v > WHITE_EPSILON) {
                        return false;
                    }
                }
                return comps.length > 0;
            }
            if (cs instanceof PDSeparation) {
                return comps.length >= 1 && comps[0] <= WHITE_EPSILON;
            }
            return false;
        }

        /**
         * Pure process black — the only colour where forcing overprint is always right. DeviceGray
         * is excluded on purpose: gray overprint cannot isolate a K plate.
         */
        boolean isPureBlack() {
            if (cs == null || comps == null) {
                return false;
            }
            if ((cs instanceof PDDeviceCMYK || iccN(4)) && comps.length >= 4) {
                return comps[0] <= CHROMATIC_EPSILON
                        && comps[1] <= CHROMATIC_EPSILON
                        && comps[2] <= CHROMATIC_EPSILON
                        && comps[3] >= BLACK_MIN_TINT;
            }
            if (cs instanceof PDSeparation sep) {
                return sep.getColorantName() != null
                        && BLACK_COLORANTS.contains(
                                sep.getColorantName().trim().toLowerCase(Locale.ROOT))
                        && comps.length >= 1
                        && comps[0] >= BLACK_MIN_TINT;
            }
            return false;
        }

        /** Same shape as {@code PrintPreflightService.isRichBlackText}. */
        boolean isRichBlack() {
            if (cs == null || comps == null) {
                return false;
            }
            if (cs instanceof PDDeviceCMYK || iccN(4)) {
                if (comps.length >= 4 && comps[3] >= 0.4f) {
                    return comps[0] >= 0.15f || comps[1] >= 0.15f || comps[2] >= 0.15f;
                }
                return comps.length >= 3
                        && comps[0] >= 0.3f
                        && comps[1] >= 0.3f
                        && comps[2] >= 0.3f;
            }
            if (cs instanceof PDDeviceN) {
                int nonZero = 0;
                float sum = 0;
                for (float v : comps) {
                    if (v > 0.15f) {
                        nonZero++;
                    }
                    sum += v;
                }
                return nonZero >= 2 && sum > 0.8f;
            }
            if (cs instanceof PDDeviceRGB || iccN(3)) {
                return comps.length >= 3
                        && comps[0] < 0.35f
                        && comps[1] < 0.35f
                        && comps[2] < 0.35f;
            }
            return false;
        }

        /** The K value a rich black converts to — darkest channel keeps the weight. */
        float richToBlack() {
            if (comps == null) {
                return 1f;
            }
            if ((cs instanceof PDDeviceRGB || iccN(3)) && comps.length >= 3) {
                return 1f - Math.max(comps[0], Math.max(comps[1], comps[2]));
            }
            float max = 0;
            for (float v : comps) {
                max = Math.max(max, v);
            }
            return max;
        }

        private boolean iccN(int n) {
            return cs instanceof PDICCBased icc && icc.getNumberOfComponents() == n;
        }
    }

    /**
     * Mutable per-stream graphics state — updated only with emitted tokens, so wrap rewrites stay
     * consistent: the injected {@code gs} flips overprint and the closing {@code Q} pops it back.
     * Text state (Tr, Tf, Tm) is not part of q/Q by spec, so the snapshot covers graphics state
     * only.
     */
    private static final class RewriteState {
        final PDResources resources;
        final Deque<Snapshot> stack = new ArrayDeque<>();
        final List<Object> pendingOperands = new ArrayList<>();
        Matrix ctm = new Matrix();
        Colour fill = new Colour(null, null, null);
        Colour stroke = new Colour(null, null, null);
        List<Object> fillCsTokens;
        List<Object> strokeCsTokens;
        boolean opFill;
        boolean opStroke;
        int opm;
        boolean inText;
        int tr;
        float fontSize;
        Matrix tm = new Matrix();
        int[] pendingFillReg;
        int[] pendingStrokeReg;

        RewriteState(PDResources resources) {
            this.resources = resources;
        }

        private record Snapshot(
                Matrix ctm,
                Colour fill,
                Colour stroke,
                List<Object> fillCsTokens,
                List<Object> strokeCsTokens,
                boolean opFill,
                boolean opStroke,
                int opm) {}

        void track(Object token) {
            if (!(token instanceof Operator op)) {
                pendingOperands.add(token);
                return;
            }
            try {
                onOperator(op.getName(), pendingOperands);
            } catch (RuntimeException e) {
                log.trace("Graphics state tracking skipped '{}': {}", op.getName(), e.getMessage());
            }
            pendingOperands.clear();
        }

        private void onOperator(String op, List<Object> args) {
            switch (op) {
                case "q" ->
                        stack.push(
                                new Snapshot(
                                        ctm,
                                        fill,
                                        stroke,
                                        fillCsTokens,
                                        strokeCsTokens,
                                        opFill,
                                        opStroke,
                                        opm));
                case "Q" -> {
                    if (!stack.isEmpty()) {
                        Snapshot s = stack.pop();
                        ctm = s.ctm();
                        fill = s.fill();
                        stroke = s.stroke();
                        fillCsTokens = s.fillCsTokens();
                        strokeCsTokens = s.strokeCsTokens();
                        opFill = s.opFill();
                        opStroke = s.opStroke();
                        opm = s.opm();
                    }
                }
                case "cm" -> {
                    Matrix m = args.size() == 6 ? matrix(args) : null;
                    if (m != null) {
                        ctm = m.multiply(ctm);
                    }
                }
                case "gs" -> applyGs(args.isEmpty() ? null : args.get(0));
                case "BT" -> {
                    inText = true;
                    tm = new Matrix();
                }
                case "ET" -> inText = false;
                case "Tr" -> {
                    if (!args.isEmpty() && args.get(0) instanceof COSNumber n) {
                        tr = n.intValue();
                    }
                }
                case "Tf" -> {
                    if (args.size() == 2 && args.get(1) instanceof COSNumber n) {
                        fontSize = n.floatValue();
                    }
                }
                case "Tm" -> {
                    Matrix m = args.size() == 6 ? matrix(args) : null;
                    if (m != null) {
                        tm = m;
                    }
                }
                case "cs" -> {
                    fill = new Colour(resolveColorSpace(args), null, emitted(args, op));
                    fillCsTokens = emitted(args, op);
                }
                case "CS" -> {
                    stroke = new Colour(resolveColorSpace(args), null, emitted(args, op));
                    strokeCsTokens = emitted(args, op);
                }
                case "sc", "scn" ->
                        fill =
                                new Colour(
                                        fill.cs(),
                                        components(args),
                                        concat(fillCsTokens, emitted(args, op)));
                case "SC", "SCN" ->
                        stroke =
                                new Colour(
                                        stroke.cs(),
                                        components(args),
                                        concat(strokeCsTokens, emitted(args, op)));
                case "g" -> {
                    fill = device(PDDeviceGray.INSTANCE, args, op);
                    fillCsTokens = null;
                }
                case "rg" -> {
                    fill = device(PDDeviceRGB.INSTANCE, args, op);
                    fillCsTokens = null;
                }
                case "k" -> {
                    fill = device(PDDeviceCMYK.INSTANCE, args, op);
                    fillCsTokens = null;
                }
                case "G" -> {
                    stroke = device(PDDeviceGray.INSTANCE, args, op);
                    strokeCsTokens = null;
                }
                case "RG" -> {
                    stroke = device(PDDeviceRGB.INSTANCE, args, op);
                    strokeCsTokens = null;
                }
                case "K" -> {
                    stroke = device(PDDeviceCMYK.INSTANCE, args, op);
                    strokeCsTokens = null;
                }
                default -> {}
            }
        }

        private Colour device(PDColorSpace cs, List<Object> args, String op) {
            return new Colour(cs, components(args), emitted(args, op));
        }

        private void applyGs(Object operand) {
            if (!(operand instanceof COSName name) || resources == null) {
                return;
            }
            PDExtendedGraphicsState gs = resources.getExtGState(name);
            if (gs != null) {
                opFill = gs.getNonStrokingOverprintControl();
                opStroke = gs.getStrokingOverprintControl();
                Integer m = gs.getOverprintMode();
                opm = m != null ? m : 0;
            }
        }

        private PDColorSpace resolveColorSpace(List<Object> args) {
            if (args.size() != 1 || !(args.get(0) instanceof COSName name) || resources == null) {
                return null;
            }
            try {
                return resources.getColorSpace(name);
            } catch (IOException e) {
                return null;
            }
        }

        private static Matrix matrix(List<Object> args) {
            float[] v = new float[6];
            for (int i = 0; i < 6; i++) {
                if (!(args.get(i) instanceof COSNumber n)) {
                    return null;
                }
                v[i] = n.floatValue();
            }
            return new Matrix(v[0], v[1], v[2], v[3], v[4], v[5]);
        }

        private static float[] components(List<Object> args) {
            float[] v = new float[args.size()];
            for (int i = 0; i < args.size(); i++) {
                if (!(args.get(i) instanceof COSNumber n)) {
                    return null; // pattern-name operand — colour unknowable at token level
                }
                v[i] = n.floatValue();
            }
            return v;
        }

        private static List<Object> emitted(List<Object> args, String op) {
            List<Object> tokens = new ArrayList<>(args.size() + 1);
            tokens.addAll(args);
            tokens.add(Operator.getOperator(op));
            return tokens;
        }

        private static List<Object> concat(List<Object> a, List<Object> b) {
            if (a == null) {
                return b;
            }
            List<Object> out = new ArrayList<>(a.size() + b.size());
            out.addAll(a);
            out.addAll(b);
            return out;
        }
    }
}
