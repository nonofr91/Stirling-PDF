package stirling.software.proprietary.policy.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.Address;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.model.job.ResultFile;
import stirling.software.common.service.FileStorage;
import stirling.software.proprietary.policy.model.OutputSpec;
import stirling.software.proprietary.policy.model.PolicyInputs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests for {@link EmailOutputSink}: per-run/per-file grouping, report-only attachments, template
 * rendering and fail-fast validation when SMTP is off.
 */
class EmailOutputSinkTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final ObjectProvider<JavaMailSender> provider = uncheckedProvider();
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ApplicationProperties properties = new ApplicationProperties();

    private EmailOutputSink sink;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> uncheckedProvider() {
        return mock(ObjectProvider.class);
    }

    @BeforeEach
    void setUp() throws MessagingException, IOException {
        properties.getMail().setEnabled(true);
        properties.getMail().setHost("smtp.example.com");
        properties.getMail().setFrom("pdf@example.com");
        sink = new EmailOutputSink(properties, provider, fileStorage, mapper);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        when(fileStorage.storeInputStream(any(), any()))
                .thenReturn(new FileStorage.StoredFile("receipt", 80));
    }

    @Test
    void oneMailPerRunAttachesReportsButNotThePdfs() throws Exception {
        Resource a = named("a.pdf", "aaa");
        Resource b = named("b.pdf", "bb");
        OutputDelivery delivery =
                delivery(
                        List.of(named("doc-in.pdf", "in")),
                        Map.of(a, report("warn", 2, 1), b, report("pass", 0, 0)));

        List<ResultFile> receipts =
                sink.deliver(delivery, List.of(a, b), spec(Map.of("to", "ops@example.com")));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, times(1)).send(sent.capture());
        MimeMessage message = sent.getValue();
        assertEquals("ops@example.com", recipients(message));
        // Worst verdict of the group wins in the subject; {filename} is the output name.
        assertEquals("[warn] a.pdf — nightly", message.getSubject());
        List<String> attachments = attachmentNames(message);
        assertEquals(List.of("a-report.json", "b-report.json"), attachments);
        assertFalse(attachments.stream().anyMatch(n -> n.endsWith(".pdf")));
        assertEquals(1, receipts.size());
        assertEquals("receipt", receipts.getFirst().getFileId());
    }

    @Test
    void perFileModeSendsOneMailPerFileWithItsOwnVerdict() throws Exception {
        Resource a = named("a.pdf", "aaa");
        Resource b = named("b.pdf", "bb");
        OutputDelivery delivery =
                delivery(
                        List.of(named("in.pdf", "in")),
                        Map.of(a, report("pass", 0, 0), b, report("fail", 4, 0)));

        sink.deliver(
                delivery, List.of(a, b), spec(Map.of("to", "ops@example.com", "mode", "perFile")));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, times(2)).send(sent.capture());
        assertEquals("[pass] a.pdf — nightly", sent.getAllValues().get(0).getSubject());
        assertEquals("[fail] b.pdf — nightly", sent.getAllValues().get(1).getSubject());
        // Each mail carries only its own file's report.
        assertEquals(List.of("a-report.json"), attachmentNames(sent.getAllValues().get(0)));
        assertEquals(List.of("b-report.json"), attachmentNames(sent.getAllValues().get(1)));
    }

    @Test
    void templatesRenderCountsAndLeaveUnknownPlaceholdersLiteral() throws Exception {
        Resource a = named("a.pdf", "aaa");
        OutputDelivery delivery = delivery(List.of(), Map.of(a, report("fail", 3, 2)));

        sink.deliver(
                delivery,
                List.of(a),
                spec(
                        Map.of(
                                "to", "ops@example.com",
                                "subject", "{pipeline}/{unknown}:{errorCount}e {warningCount}w",
                                "body", "run {runId}")));

        MimeMessage message = sentMail();
        assertEquals("nightly/{unknown}:3e 2w", message.getSubject());
        assertTrue(bodyHtml(message).contains("run run-9"));
    }

    @Test
    void htmlBodyEscapesInjectedFilenames() throws Exception {
        Resource a = named("<script>.pdf", "aaa");
        OutputDelivery delivery = delivery(List.of(), Map.of(a, report("pass", 0, 0)));

        sink.deliver(
                delivery,
                List.of(a),
                spec(Map.of("to", "ops@example.com", "body", "File: {fileNames}")));

        String html = bodyHtml(sentMail());
        assertTrue(html.contains("&lt;script&gt;.pdf"));
        assertFalse(html.contains("<script>"));
    }

    @Test
    void attachOutputsAddsTheProcessedFiles() throws Exception {
        Resource a = named("a.pdf", "aaa");
        OutputDelivery delivery = delivery(List.of(), Map.of(a, report("pass", 0, 0)));

        sink.deliver(
                delivery,
                List.of(a),
                spec(Map.of("to", "ops@example.com", "attachOutputs", "true")));

        assertEquals(List.of("a-report.json", "a.pdf"), attachmentNames(sentMail()));
    }

    @Test
    void reportAttachmentCarriesThePreflightReport() throws Exception {
        Resource a = named("a.pdf", "aaa");
        OutputDelivery delivery = delivery(List.of(), Map.of(a, report("fail", 3, 1)));

        sink.deliver(delivery, List.of(a), spec(Map.of("to", "ops@example.com")));

        String json = attachmentText(sentMail(), "a-report.json");
        assertTrue(
                json.contains("\"verdict\":\"fail\"") || json.contains("\"verdict\" : \"fail\""));
        assertTrue(json.contains("\"errors\":3") || json.contains("\"errors\" : 3"));
    }

    @Test
    void reportOnlyRunStillSendsItsVerdictAndReport() throws Exception {
        // A terminal step that emits only a JSON verdict delivers zero files — the mail must
        // still carry the run's outcome instead of going out empty or not at all.
        Resource in = named("scan.pdf", "in");
        OutputDelivery delivery = delivery(List.of(in), Map.of(), report("fail", 5, 1));

        sink.deliver(delivery, List.of(), spec(Map.of("to", "ops@example.com", "mode", "perFile")));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, times(1)).send(sent.capture());
        MimeMessage message = sent.getValue();
        // {filename} falls back to the run's input name; verdict comes from the run report.
        assertEquals("[fail] scan.pdf — nightly", message.getSubject());
        assertEquals(List.of("run-report.json"), attachmentNames(message));
        String json = attachmentText(message, "run-report.json");
        assertTrue(json.contains("\"errors\":5") || json.contains("\"errors\" : 5"));
    }

    @Test
    void missingSmtpHostFailsValidationEvenWhenEnabled() {
        properties.getMail().setHost(null);
        assertThrows(
                IllegalArgumentException.class,
                () -> sink.validate(spec(Map.of("to", "ops@example.com"))));
        properties.getMail().setHost(" ");
        assertThrows(
                IllegalArgumentException.class,
                () -> sink.validate(spec(Map.of("to", "ops@example.com"))));
    }

    @Test
    void smtpDisabledFailsValidationBeforeAnySend() {
        properties.getMail().setEnabled(false);
        OutputSpec spec = spec(Map.of("to", "ops@example.com"));

        assertThrows(IllegalArgumentException.class, () -> sink.validate(spec));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        sink.deliver(
                                delivery(List.of(), Map.of()), List.of(named("a.pdf", "a")), spec));
    }

    @Test
    void missingSenderBeanFailsValidation() {
        when(provider.getIfAvailable()).thenReturn(null);
        assertThrows(
                IllegalArgumentException.class,
                () -> sink.validate(spec(Map.of("to", "ops@example.com"))));
    }

    @Test
    void emptyOrMalformedRecipientsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> sink.validate(spec(Map.of("to", ""))));
        assertThrows(
                IllegalArgumentException.class,
                () -> sink.validate(spec(Map.of("to", "not-an-address"))));
    }

    @Test
    void multipleRecipientsAndSemicolonsParse() throws Exception {
        sink.deliver(
                delivery(List.of(), Map.of()),
                List.of(named("a.pdf", "a")),
                spec(Map.of("to", "ops@example.com, qa@example.com;lead@example.com")));

        assertEquals("ops@example.com,qa@example.com,lead@example.com", recipients(sentMail()));
    }

    private static OutputSpec spec(Map<String, Object> options) {
        return new OutputSpec("smtp", options);
    }

    private OutputDelivery delivery(List<Resource> inputs, Map<Resource, JsonNode> reports) {
        return delivery(inputs, reports, null);
    }

    private OutputDelivery delivery(
            List<Resource> inputs, Map<Resource, JsonNode> reports, JsonNode runReport) {
        return new OutputDelivery(
                "run-9",
                "policy-1",
                PolicyInputs.of(inputs),
                null,
                null,
                "nightly",
                new IdentityHashMap<>(reports),
                runReport);
    }

    private JsonNode report(String verdict, int errors, int warnings) {
        return mapper.valueToTree(
                Map.of(
                        "preflight",
                        Map.of(
                                "verdict", verdict,
                                "errors", errors,
                                "warnings", warnings,
                                "fixupsApplied", List.of())));
    }

    private MimeMessage sentMail() throws MessagingException {
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        return sent.getValue();
    }

    private static String recipients(MimeMessage message) throws MessagingException {
        Address[] to = message.getRecipients(RecipientType.TO);
        List<String> addresses = new ArrayList<>();
        for (Address address : to) {
            addresses.add(address.toString());
        }
        return String.join(",", addresses);
    }

    private static List<Part> leafParts(Part part) throws Exception {
        List<Part> leaves = new ArrayList<>();
        if (part.isMimeType("multipart/*")) {
            Multipart nested = (Multipart) part.getContent();
            for (int i = 0; i < nested.getCount(); i++) {
                leaves.addAll(leafParts(nested.getBodyPart(i)));
            }
        } else {
            leaves.add(part);
        }
        return leaves;
    }

    private static List<String> attachmentNames(MimeMessage message) throws Exception {
        List<String> names = new ArrayList<>();
        for (Part part : leafParts(message)) {
            if (Part.ATTACHMENT.equals(part.getDisposition())) {
                names.add(part.getFileName());
            }
        }
        return names;
    }

    private static String attachmentText(MimeMessage message, String name) throws Exception {
        for (Part part : leafParts(message)) {
            if (name.equals(part.getFileName())) {
                return new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no attachment named " + name);
    }

    private static String bodyHtml(MimeMessage message) throws Exception {
        for (Part part : leafParts(message)) {
            if (part.isMimeType("text/html")) {
                return (String) part.getContent();
            }
        }
        throw new AssertionError("no html body part");
    }

    private static ByteArrayResource named(String filename, String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }
}
