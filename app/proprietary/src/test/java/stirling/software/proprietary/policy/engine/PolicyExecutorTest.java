package stirling.software.proprietary.policy.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;

import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.service.AutomationRunContext;
import stirling.software.common.service.InternalApiClient;
import stirling.software.common.service.InternalApiTimeoutException;
import stirling.software.common.service.ToolMetadataService;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.TempFileRegistry;
import stirling.software.proprietary.document.conditions.Condition;
import stirling.software.proprietary.document.conditions.ConditionInput;
import stirling.software.proprietary.policy.model.OutputSpec;
import stirling.software.proprietary.policy.model.PipelineDefinition;
import stirling.software.proprietary.policy.model.PipelineStep;
import stirling.software.proprietary.policy.model.PolicyInputs;
import stirling.software.proprietary.policy.progress.PolicyProgressListener;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link PolicyExecutor}, the shared pipeline step loop. Covers file chaining across
 * steps, multi-input vs per-file dispatch, ZIP unpacking, structured-list parameter encoding,
 * progress callbacks, and timeout propagation. External collaborators are mocked; {@link
 * TempFileManager} is real so ZIP extraction exercises real code.
 */
@ExtendWith(MockitoExtension.class)
class PolicyExecutorTest {

    private static final String ROTATE = "/api/v1/general/rotate-pdf";
    private static final String COMPRESS = "/api/v1/misc/compress-pdf";
    private static final String SPLIT = "/api/v1/general/split-pages";
    private static final String MERGE = "/api/v1/general/merge-pdfs";

    @Mock private InternalApiClient internalApiClient;
    @Mock private ToolMetadataService toolMetadataService;

    @TempDir Path tempDir;

    private TempFileManager tempFileManager;
    private PolicyExecutor executor;

    @BeforeEach
    void setUp() {
        ApplicationProperties props = new ApplicationProperties();
        props.getSystem().getTempFileManagement().setBaseTmpDir(tempDir.toString());
        props.getSystem().getTempFileManagement().setPrefix("policy-test-");
        tempFileManager = new TempFileManager(new TempFileRegistry(), props);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        executor =
                new PolicyExecutor(
                        internalApiClient, toolMetadataService, tempFileManager, objectMapper);
    }

    @Test
    void executesStepsSequentiallyChainingOutputToInput() throws IOException {
        when(toolMetadataService.isMultiInput(anyString())).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(anyString())).thenReturn(false);
        stubEndpoint(ROTATE, pdf("rotated", "rotated.pdf"));
        stubEndpoint(COMPRESS, pdf("compressed", "compressed.pdf"));

        List<Integer> steps = new ArrayList<>();
        PolicyProgressListener listener =
                new PolicyProgressListener() {
                    @Override
                    public void onStepStart(int stepIndex, int stepCount, String operation) {
                        steps.add(stepIndex);
                    }
                };

        PolicyExecutionResult result =
                executor.execute(
                        definition(
                                new PipelineStep(ROTATE, Map.of()),
                                new PipelineStep(COMPRESS, Map.of())),
                        PolicyInputs.of(List.of(pdf("input", "input.pdf"))),
                        listener);

        assertEquals(1, result.files().size());
        assertEquals("compressed.pdf", result.files().get(0).getFilename());
        verify(internalApiClient, times(1)).post(eq(ROTATE), any());
        verify(internalApiClient, times(1)).post(eq(COMPRESS), any());
        // Progress fired once per step, in order.
        assertEquals(List.of(1, 2), steps);
    }

    @Test
    void multiInputEndpointIsCalledOnceWithAllFiles() throws IOException {
        when(toolMetadataService.isMultiInput(MERGE)).thenReturn(true);
        when(toolMetadataService.shouldUnpackZipResponse(MERGE)).thenReturn(false);
        stubEndpoint(MERGE, pdf("merged", "merged.pdf"));

        PolicyExecutionResult result =
                executor.execute(
                        definition(new PipelineStep(MERGE, Map.of())),
                        PolicyInputs.of(List.of(pdf("a", "a.pdf"), pdf("b", "b.pdf"))),
                        PolicyProgressListener.NOOP);

        assertEquals(1, result.files().size());
        verify(internalApiClient, times(1)).post(eq(MERGE), any());
    }

    @Test
    void singleInputEndpointIsCalledOncePerFile() throws IOException {
        when(toolMetadataService.isMultiInput(ROTATE)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(ROTATE)).thenReturn(false);
        stubEndpoint(ROTATE, pdf("rotated", "rotated.pdf"));

        PolicyExecutionResult result =
                executor.execute(
                        definition(new PipelineStep(ROTATE, Map.of())),
                        PolicyInputs.of(List.of(pdf("a", "a.pdf"), pdf("b", "b.pdf"))),
                        PolicyProgressListener.NOOP);

        assertEquals(2, result.files().size());
        verify(internalApiClient, times(2)).post(eq(ROTATE), any());
    }

    @Test
    void noInputGeneratorEndpointIsCalledOnceWithNoFile() throws IOException {
        // A "create" workflow has no source documents: a generator tool (e.g.
        // create-pdf-from-html-agent) produces its output purely from parameters. Per-file
        // dispatch would skip it entirely (zero files = zero calls), so it must still run once.
        String createPdf = "/api/v1/ai/tools/create-pdf-from-html-agent";
        when(toolMetadataService.isMultiInput(createPdf)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(createPdf)).thenReturn(false);
        stubEndpoint(createPdf, pdf("generated", "purchase-order.pdf"));

        PolicyExecutionResult result =
                executor.execute(
                        definition(
                                new PipelineStep(
                                        createPdf,
                                        Map.of(
                                                "document",
                                                "{\"title\":\"PO\",\"sections\":[]}",
                                                "filename",
                                                "purchase-order.pdf"))),
                        PolicyInputs.of(List.of()),
                        PolicyProgressListener.NOOP);

        assertEquals(1, result.files().size());
        assertEquals("purchase-order.pdf", result.files().get(0).getFilename());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MultiValueMap<String, Object>> bodyCaptor =
                ArgumentCaptor.forClass(MultiValueMap.class);
        verify(internalApiClient, times(1)).post(eq(createPdf), bodyCaptor.capture());
        // No document stream: the body carries only the generator's parameters, no fileInput.
        assertNull(bodyCaptor.getValue().get("fileInput"));
    }

    @Test
    void zipResponseIsUnpackedIntoIndividualFiles() throws IOException {
        when(toolMetadataService.isMultiInput(SPLIT)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(SPLIT)).thenReturn(true);
        stubEndpoint(
                SPLIT,
                zip(
                        "doc.zip",
                        List.of(new Entry("page-1.pdf", "one"), new Entry("page-2.pdf", "two"))));

        PolicyExecutionResult result =
                executor.execute(
                        definition(new PipelineStep(SPLIT, Map.of())),
                        PolicyInputs.of(List.of(pdf("doc", "doc.pdf"))),
                        PolicyProgressListener.NOOP);

        assertEquals(2, result.files().size());
        assertEquals("page-1.pdf", result.files().get(0).getFilename());
        assertEquals("page-2.pdf", result.files().get(1).getFilename());
    }

    @Test
    void structuredListParameterIsJsonEncodedAsSingleField() throws IOException {
        String editText = "/api/v1/general/edit-text";
        when(toolMetadataService.isMultiInput(editText)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(editText)).thenReturn(false);
        stubEndpoint(editText, pdf("edited", "edited.pdf"));

        // LinkedHashMap so the serialized key order is deterministic for the assertion below.
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("find", "foo");
        edit.put("replace", "bar");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("edits", List.of(edit));
        params.put("useRegex", false);

        executor.execute(
                definition(new PipelineStep(editText, params)),
                PolicyInputs.of(List.of(pdf("in", "in.pdf"))),
                PolicyProgressListener.NOOP);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MultiValueMap<String, Object>> bodyCaptor =
                ArgumentCaptor.forClass(MultiValueMap.class);
        verify(internalApiClient).post(eq(editText), bodyCaptor.capture());
        MultiValueMap<String, Object> body = bodyCaptor.getValue();

        List<Object> edits = body.get("edits");
        assertNotNull(edits);
        assertEquals(1, edits.size());
        assertEquals("[{\"find\":\"foo\",\"replace\":\"bar\"}]", edits.get(0));
    }

    @Test
    void supportingFilesAreBoundToTheirNamedFields() throws IOException {
        String addStamp = "/api/v1/misc/add-stamp-to-pdf";
        when(toolMetadataService.isMultiInput(addStamp)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(addStamp)).thenReturn(false);
        stubEndpoint(addStamp, pdf("stamped", "stamped.pdf"));

        PipelineStep step =
                new PipelineStep(addStamp, Map.of("opacity", 0.5), Map.of("stampImage", "logo"));
        PolicyInputs inputs =
                new PolicyInputs(
                        List.of(pdf("doc", "doc.pdf")),
                        Map.of("logo", List.of(pdf("logo-bytes", "logo.png"))));

        executor.execute(
                new PipelineDefinition("stamp", List.of(step), OutputSpec.inline()),
                inputs,
                PolicyProgressListener.NOOP);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MultiValueMap<String, Object>> bodyCaptor =
                ArgumentCaptor.forClass(MultiValueMap.class);
        verify(internalApiClient).post(eq(addStamp), bodyCaptor.capture());
        MultiValueMap<String, Object> body = bodyCaptor.getValue();
        // The document goes to fileInput; the supporting image is bound to its named field and is
        // not part of the document stream.
        assertEquals(1, body.get("fileInput").size());
        assertNotNull(body.get("stampImage"));
        assertEquals(1, body.get("stampImage").size());
    }

    @Test
    void missingSupportingFileFailsTheStep() {
        String addStamp = "/api/v1/misc/add-stamp-to-pdf";
        when(toolMetadataService.isMultiInput(addStamp)).thenReturn(false);
        PipelineStep step = new PipelineStep(addStamp, Map.of(), Map.of("stampImage", "logo"));

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                executor.execute(
                                        new PipelineDefinition(
                                                "stamp", List.of(step), OutputSpec.inline()),
                                        PolicyInputs.of(List.of(pdf("doc", "doc.pdf"))),
                                        PolicyProgressListener.NOOP));
        assertTrue(ex.getMessage().contains("logo"));
    }

    @Test
    void documentOfAnUnacceptedTypeFailsTheStep() {
        String compress = "/api/v1/misc/compress-pdf";
        when(toolMetadataService.getExtensionTypes(false, compress)).thenReturn(List.of("pdf"));

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                executor.execute(
                                        definition(new PipelineStep(compress, Map.of())),
                                        PolicyInputs.of(List.of(pdf("img", "image.png"))),
                                        PolicyProgressListener.NOOP));
        // Names the rejected extension, never the document. This message becomes the run's error
        // and
        // is persisted on the failure record, which holds no document name.
        assertTrue(ex.getMessage().contains("png"));
        assertFalse(ex.getMessage().contains("image.png"));
        // Type check happens before any dispatch.
        verify(internalApiClient, never()).post(anyString(), any());
    }

    @Test
    void documentOfAnAcceptedTypeProceeds() throws IOException {
        String compress = "/api/v1/misc/compress-pdf";
        when(toolMetadataService.getExtensionTypes(false, compress)).thenReturn(List.of("pdf"));
        when(toolMetadataService.isMultiInput(compress)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(compress)).thenReturn(false);
        stubEndpoint(compress, pdf("compressed", "compressed.pdf"));

        PolicyExecutionResult result =
                executor.execute(
                        definition(new PipelineStep(compress, Map.of())),
                        PolicyInputs.of(List.of(pdf("doc", "doc.pdf"))),
                        PolicyProgressListener.NOOP);

        assertEquals(1, result.files().size());
        verify(internalApiClient, times(1)).post(eq(compress), any());
    }

    @Test
    void filterOperationWithEmptyResultDropsTheFile() throws IOException {
        String filter = "/api/v1/filter/filter-page-count";
        when(toolMetadataService.isMultiInput(filter)).thenReturn(false);
        stubEndpoint(filter, pdf("", "filtered.pdf")); // empty body => filtered out

        PolicyExecutionResult result =
                executor.execute(
                        definition(new PipelineStep(filter, Map.of())),
                        PolicyInputs.of(List.of(pdf("doc", "doc.pdf"))),
                        PolicyProgressListener.NOOP);

        assertEquals(0, result.files().size());
    }

    @Test
    void timeoutFromAStepPropagates() {
        when(toolMetadataService.isMultiInput(ROTATE)).thenReturn(false);
        when(internalApiClient.post(eq(ROTATE), any()))
                .thenThrow(
                        new InternalApiTimeoutException(
                                ROTATE,
                                java.time.Duration.ofSeconds(300),
                                new IOException("Read timed out")));

        assertThrows(
                InternalApiTimeoutException.class,
                () ->
                        executor.execute(
                                definition(new PipelineStep(ROTATE, Map.of())),
                                PolicyInputs.of(List.of(pdf("in", "in.pdf"))),
                                PolicyProgressListener.NOOP));
    }

    @Test
    void emptyStepsPassInputsThroughUnchanged() throws Exception {
        // A pure routing policy has no steps: inputs are delivered to the
        // destinations untouched, so execute returns them as-is.
        Resource input = pdf("in", "in.pdf");
        PolicyExecutionResult result =
                executor.execute(
                        new PipelineDefinition("empty", List.of(), OutputSpec.inline()),
                        PolicyInputs.of(List.of(input)),
                        PolicyProgressListener.NOOP);

        assertEquals(1, result.files().size());
        assertEquals("in.pdf", result.files().get(0).getFilename());
    }

    @Test
    void stepReportIsAttachedToEachFileItProduced() throws IOException {
        String fix = "/api/v1/security/print-preflight-fix";
        when(toolMetadataService.isMultiInput(fix)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(fix)).thenReturn(false);
        // The fix endpoint reports a verdict per dispatched file; per-file dispatch must keep
        // each file's own verdict instead of sharing the first non-null one.
        when(internalApiClient.post(eq(fix), any()))
                .thenAnswer(
                        inv -> {
                            MultiValueMap<String, Object> body = inv.getArgument(1);
                            Resource input = (Resource) body.getFirst("fileInput");
                            String verdict = input.getFilename().contains("bad") ? "fail" : "pass";
                            return ResponseEntity.ok()
                                    .header(
                                            "X-Stirling-Tool-Report",
                                            "{\"preflight\":{\"verdict\":\""
                                                    + verdict
                                                    + "\",\"errors\":0,\"warnings\":0}}")
                                    .body(pdf("fixed", input.getFilename()));
                        });

        PolicyExecutionResult result =
                executor.execute(
                        definition(new PipelineStep(fix, Map.of())),
                        PolicyInputs.of(List.of(pdf("a", "good.pdf"), pdf("b", "bad.pdf"))),
                        PolicyProgressListener.NOOP);

        assertEquals(2, result.reports().size());
        assertEquals("pass", result.reports().get(0).at("/preflight/verdict").asString());
        assertEquals("fail", result.reports().get(1).at("/preflight/verdict").asString());
    }

    @Test
    void archiveChainHeadersAreFoldedIntoTheReport() throws IOException {
        String fix = "/api/v1/security/print-preflight-fix";
        when(toolMetadataService.isMultiInput(fix)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(fix)).thenReturn(false);
        // A versioned prepress response carries the chain headers even without a tool report —
        // the run must still link back to the immutable archive chain.
        when(internalApiClient.post(eq(fix), any()))
                .thenAnswer(
                        inv ->
                                ResponseEntity.ok()
                                        .header("X-Prepress-Chain-Id", "abc123")
                                        .header("X-Prepress-Version", "4")
                                        .body(pdf("fixed", "fixed.pdf")));

        PolicyExecutionResult result =
                executor.execute(
                        definition(new PipelineStep(fix, Map.of())),
                        PolicyInputs.of(List.of(pdf("in", "in.pdf"))),
                        PolicyProgressListener.NOOP);

        assertNotNull(result.reports().get(0));
        assertEquals("abc123", result.reports().get(0).at("/prepress/chainId").asString());
        assertEquals("4", result.reports().get(0).at("/prepress/version").asString());
    }

    @Test
    void silentStepKeepsThePreviousReportOnTheFile() throws IOException {
        String fix = "/api/v1/security/print-preflight-fix";
        when(toolMetadataService.isMultiInput(anyString())).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(anyString())).thenReturn(false);
        when(internalApiClient.post(eq(fix), any()))
                .thenAnswer(
                        inv ->
                                ResponseEntity.ok()
                                        .header(
                                                "X-Stirling-Tool-Report",
                                                "{\"preflight\":{\"verdict\":\"warn\"}}")
                                        .body(pdf("fixed", "fixed.pdf")));
        // Compress emits no report: the file must keep its preflight verdict for routing.
        stubEndpoint(COMPRESS, pdf("compressed", "compressed.pdf"));

        PolicyExecutionResult result =
                executor.execute(
                        definition(
                                new PipelineStep(fix, Map.of()),
                                new PipelineStep(COMPRESS, Map.of())),
                        PolicyInputs.of(List.of(pdf("in", "in.pdf"))),
                        PolicyProgressListener.NOOP);

        assertEquals(1, result.reports().size());
        assertEquals("warn", result.reports().get(0).at("/preflight/verdict").asString());
    }

    @Test
    void archiveOnlyReportLayersOverTheCarriedVerdict() throws IOException {
        String fix = "/api/v1/security/print-preflight-fix";
        String cut = "/api/v1/security/cut-contour";
        when(toolMetadataService.isMultiInput(anyString())).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(anyString())).thenReturn(false);
        when(internalApiClient.post(eq(fix), any()))
                .thenAnswer(
                        inv ->
                                ResponseEntity.ok()
                                        .header(
                                                "X-Stirling-Tool-Report",
                                                "{\"preflight\":{\"verdict\":\"fail\",\"errors\":1}}")
                                        .body(pdf("fixed", "fixed.pdf")));
        // A versioned prepress step emits chain headers only: layering them over the carried
        // report must keep the verdict, or fail routes lose the file to the fallback.
        when(internalApiClient.post(eq(cut), any()))
                .thenAnswer(
                        inv ->
                                ResponseEntity.ok()
                                        .header("X-Prepress-Chain-Id", "chain9")
                                        .header("X-Prepress-Version", "2")
                                        .body(pdf("cut", "cut.pdf")));

        PolicyExecutionResult result =
                executor.execute(
                        definition(
                                new PipelineStep(fix, Map.of()), new PipelineStep(cut, Map.of())),
                        PolicyInputs.of(List.of(pdf("in", "in.pdf"))),
                        PolicyProgressListener.NOOP);

        assertEquals("fail", result.reports().get(0).at("/preflight/verdict").asString());
        assertEquals("chain9", result.reports().get(0).at("/prepress/chainId").asString());
    }

    @Test
    void silentMergeKeepsAVerdictAllInputsAgreeOn() throws IOException {
        String fix = "/api/v1/security/print-preflight-fix";
        when(toolMetadataService.isMultiInput(fix)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(fix)).thenReturn(false);
        when(internalApiClient.post(eq(fix), any()))
                .thenAnswer(
                        inv ->
                                ResponseEntity.ok()
                                        .header(
                                                "X-Stirling-Tool-Report",
                                                "{\"preflight\":{\"verdict\":\"fail\",\"errors\":2}}")
                                        .body(pdf("fixed", "f.pdf")));
        when(toolMetadataService.isMultiInput(MERGE)).thenReturn(true);
        when(toolMetadataService.shouldUnpackZipResponse(MERGE)).thenReturn(false);
        stubEndpoint(MERGE, pdf("merged", "merged.pdf")); // silent merge emits no report

        PolicyExecutionResult result =
                executor.execute(
                        definition(
                                new PipelineStep(fix, Map.of()), new PipelineStep(MERGE, Map.of())),
                        PolicyInputs.of(List.of(pdf("a", "a.pdf"), pdf("b", "b.pdf"))),
                        PolicyProgressListener.NOOP);

        assertEquals("fail", result.reports().get(0).at("/preflight/verdict").asString());
    }

    @Test
    void silentMergeDropsAVerdictInputsDisagreeOn() throws IOException {
        String fix = "/api/v1/security/print-preflight-fix";
        when(toolMetadataService.isMultiInput(fix)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(fix)).thenReturn(false);
        when(internalApiClient.post(eq(fix), any()))
                .thenAnswer(
                        inv -> {
                            MultiValueMap<String, Object> body = inv.getArgument(1);
                            Resource input = (Resource) body.getFirst("fileInput");
                            String verdict = input.getFilename().contains("bad") ? "fail" : "pass";
                            return ResponseEntity.ok()
                                    .header(
                                            "X-Stirling-Tool-Report",
                                            "{\"preflight\":{\"verdict\":\"" + verdict + "\"}}")
                                    .body(pdf("fixed", input.getFilename()));
                        });
        when(toolMetadataService.isMultiInput(MERGE)).thenReturn(true);
        when(toolMetadataService.shouldUnpackZipResponse(MERGE)).thenReturn(false);
        stubEndpoint(MERGE, pdf("merged", "merged.pdf"));

        PolicyExecutionResult result =
                executor.execute(
                        definition(
                                new PipelineStep(fix, Map.of()), new PipelineStep(MERGE, Map.of())),
                        PolicyInputs.of(List.of(pdf("a", "good.pdf"), pdf("b", "bad.pdf"))),
                        PolicyProgressListener.NOOP);

        // pass + fail merged: the output cannot honestly carry either verdict, so routing on
        // report.preflight.* must not match — a quarantine rule silently passing would be worse.
        assertNull(result.reports().get(0));
    }

    // --- step gates (when) ---

    @Test
    void gatedStepDispatchesOnlyMatchingFiles() throws IOException {
        when(toolMetadataService.isMultiInput(ROTATE)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(ROTATE)).thenReturn(false);
        stubEndpoint(ROTATE, pdf("rotated", "rotated.pdf"));

        PolicyExecutionResult result =
                executor.execute(
                        definition(
                                new PipelineStep(
                                        ROTATE,
                                        Map.of(),
                                        Map.of(),
                                        gateOn("document.extension", "pdf"))),
                        PolicyInputs.of(List.of(pdf("a", "a.pdf"), pdf("b", "b.docx"))),
                        PolicyProgressListener.NOOP);

        // Only the matching file was dispatched; the .docx flowed through untouched.
        assertEquals(2, result.files().size());
        assertEquals("rotated.pdf", result.files().get(0).getFilename());
        assertEquals("b.docx", result.files().get(1).getFilename());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MultiValueMap<String, Object>> bodyCaptor =
                ArgumentCaptor.forClass(MultiValueMap.class);
        verify(internalApiClient, times(1)).post(eq(ROTATE), bodyCaptor.capture());
        Resource dispatched = (Resource) bodyCaptor.getValue().getFirst("fileInput");
        assertEquals("a.pdf", dispatched.getFilename());
    }

    @Test
    void gatedStepReportsHowManyFilesMatched() throws IOException {
        when(toolMetadataService.isMultiInput(ROTATE)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(ROTATE)).thenReturn(false);
        stubEndpoint(ROTATE, pdf("rotated", "rotated.pdf"));
        List<String> gates = new ArrayList<>();
        PolicyProgressListener listener =
                new PolicyProgressListener() {
                    @Override
                    public void onStepGate(int stepIndex, int matched, int total) {
                        gates.add(stepIndex + ":" + matched + "/" + total);
                    }
                };

        executor.execute(
                definition(
                        new PipelineStep(
                                ROTATE, Map.of(), Map.of(), gateOn("document.extension", "pdf"))),
                PolicyInputs.of(List.of(pdf("a", "a.pdf"), pdf("b", "b.docx"), pdf("c", "c.docx"))),
                listener);

        assertEquals(List.of("1:1/3"), gates);
    }

    @Test
    void gatedStepReadsThePreviousStepsReport() throws IOException {
        // The prepress diamond: preflight annotates each file with its verdict, then the fix step
        // runs only where the verdict failed - exactly the "fix if errors" pipeline the gate is
        // for.
        String preflight = "/api/v1/security/print-preflight-annotated";
        String fix = "/api/v1/security/print-preflight-fix";
        when(toolMetadataService.isMultiInput(anyString())).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(anyString())).thenReturn(false);
        when(internalApiClient.post(eq(preflight), any()))
                .thenAnswer(
                        inv -> {
                            MultiValueMap<String, Object> body = inv.getArgument(1);
                            Resource input = (Resource) body.getFirst("fileInput");
                            String verdict = input.getFilename().contains("bad") ? "fail" : "pass";
                            return ResponseEntity.ok()
                                    .header(
                                            "X-Stirling-Tool-Report",
                                            "{\"preflight\":{\"verdict\":\"" + verdict + "\"}}")
                                    .body(pdf("checked", input.getFilename()));
                        });
        when(internalApiClient.post(eq(fix), any()))
                .thenAnswer(
                        inv -> {
                            MultiValueMap<String, Object> body = inv.getArgument(1);
                            Resource input = (Resource) body.getFirst("fileInput");
                            return ResponseEntity.ok(pdf("fixed", "fixed-" + input.getFilename()));
                        });

        PolicyExecutionResult result =
                executor.execute(
                        definition(
                                new PipelineStep(preflight, Map.of()),
                                new PipelineStep(
                                        fix,
                                        Map.of(),
                                        Map.of(),
                                        gateOn("report.preflight.verdict", "fail"))),
                        PolicyInputs.of(List.of(pdf("a", "good.pdf"), pdf("b", "bad.pdf"))),
                        PolicyProgressListener.NOOP);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MultiValueMap<String, Object>> bodyCaptor =
                ArgumentCaptor.forClass(MultiValueMap.class);
        verify(internalApiClient, times(1)).post(eq(fix), bodyCaptor.capture());
        Resource fixed = (Resource) bodyCaptor.getValue().getFirst("fileInput");
        assertEquals("bad.pdf", fixed.getFilename());

        // The passing file bypassed the fix keeping its slot and its verdict for routing.
        assertEquals(2, result.files().size());
        assertEquals("good.pdf", result.files().get(0).getFilename());
        assertEquals("fixed-bad.pdf", result.files().get(1).getFilename());
        assertEquals("pass", result.reports().get(0).at("/preflight/verdict").asString());
        assertEquals("fail", result.reports().get(1).at("/preflight/verdict").asString());
    }

    @Test
    void aGateNothingMatchesNeverCallsTheTool() throws IOException {
        PolicyExecutionResult result =
                executor.execute(
                        definition(
                                new PipelineStep(
                                        ROTATE,
                                        Map.of(),
                                        Map.of(),
                                        gateOn("report.preflight.verdict", "fail"))),
                        PolicyInputs.of(List.of(pdf("a", "a.pdf"))),
                        PolicyProgressListener.NOOP);

        verify(internalApiClient, never()).post(any(), any());
        assertEquals(1, result.files().size());
        assertEquals("a.pdf", result.files().get(0).getFilename());
    }

    @Test
    void aGatedGeneratorStepNeverRuns() throws IOException {
        // No input files means no document facts to evaluate, so the gate cannot match.
        String createPdf = "/api/v1/ai/tools/create-pdf-from-html-agent";

        PolicyExecutionResult result =
                executor.execute(
                        definition(
                                new PipelineStep(
                                        createPdf,
                                        Map.of(),
                                        Map.of(),
                                        gateOn("document.extension", "pdf"))),
                        PolicyInputs.of(List.of()),
                        PolicyProgressListener.NOOP);

        verify(internalApiClient, never()).post(eq(createPdf), any());
        assertEquals(0, result.files().size());
    }

    @Test
    void aGatedMultiInputStepMergesOnlyTheMatchingFiles() throws IOException {
        when(toolMetadataService.isMultiInput(MERGE)).thenReturn(true);
        when(toolMetadataService.shouldUnpackZipResponse(MERGE)).thenReturn(false);
        stubEndpoint(MERGE, pdf("merged", "merged.pdf"));

        PolicyExecutionResult result =
                executor.execute(
                        definition(
                                new PipelineStep(
                                        MERGE,
                                        Map.of(),
                                        Map.of(),
                                        gateOn("document.extension", "pdf"))),
                        PolicyInputs.of(
                                List.of(pdf("a", "a.pdf"), pdf("b", "b.docx"), pdf("c", "c.pdf"))),
                        PolicyProgressListener.NOOP);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MultiValueMap<String, Object>> bodyCaptor =
                ArgumentCaptor.forClass(MultiValueMap.class);
        verify(internalApiClient, times(1)).post(eq(MERGE), bodyCaptor.capture());
        List<Object> inputs = bodyCaptor.getValue().get("fileInput");
        assertEquals(2, inputs.size());

        // The merge's product first, then the bypassed file, still carrying its own identity.
        assertEquals(2, result.files().size());
        assertEquals("merged.pdf", result.files().get(0).getFilename());
        assertEquals("b.docx", result.files().get(1).getFilename());
    }

    private static Condition gateOn(String field, String... values) {
        return new Condition.MatchesAny(new ConditionInput.DocumentField(field), List.of(values));
    }

    // --- helpers ---

    private static PipelineDefinition definition(PipelineStep... steps) {
        return new PipelineDefinition("test", List.of(steps), OutputSpec.inline());
    }

    @Test
    void perFileStepScopesADistinctDocumentIdForEachFile() throws IOException {
        // Within a run over several documents, each file's dispatch carries a distinct, run-scoped
        // document id so a linked instance bills each source document once - not the whole run
        // once.
        when(toolMetadataService.isMultiInput(ROTATE)).thenReturn(false);
        when(toolMetadataService.shouldUnpackZipResponse(ROTATE)).thenReturn(false);
        List<String> docIds = new ArrayList<>();
        when(internalApiClient.post(eq(ROTATE), any()))
                .thenAnswer(
                        inv -> {
                            docIds.add(AutomationRunContext.currentDocument());
                            return ResponseEntity.ok(pdf("rotated", "rotated.pdf"));
                        });

        try (AutomationRunContext.Scope run = AutomationRunContext.open("run-x")) {
            executor.execute(
                    definition(new PipelineStep(ROTATE, Map.of())),
                    PolicyInputs.of(List.of(pdf("a", "a.pdf"), pdf("b", "b.pdf"))),
                    PolicyProgressListener.NOOP);
        }

        assertEquals(2, docIds.size());
        assertNotNull(docIds.get(0));
        assertNotNull(docIds.get(1));
        assertNotEquals(docIds.get(0), docIds.get(1)); // distinct per source document
        assertTrue(docIds.get(0).startsWith("run-x:")); // run-scoped
    }

    private void stubEndpoint(String endpoint, Resource body) {
        when(internalApiClient.post(eq(endpoint), any())).thenReturn(ResponseEntity.ok(body));
    }

    private static ByteArrayResource pdf(String content, String filename) {
        return new ByteArrayResource(content.getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static ByteArrayResource zip(String filename, List<Entry> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Entry entry : entries) {
                zos.putNextEntry(new ZipEntry(entry.name()));
                zos.write(entry.content().getBytes());
                zos.closeEntry();
            }
        }
        byte[] zipBytes = baos.toByteArray();
        return new ByteArrayResource(zipBytes) {
            @Override
            public String getFilename() {
                return filename;
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(zipBytes);
            }
        };
    }

    private record Entry(String name, String content) {}
}
