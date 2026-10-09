package stirling.software.proprietary.policy.output;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.model.job.ResultFile;
import stirling.software.common.service.FileStorage;
import stirling.software.proprietary.policy.model.OutputSpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends a run's outcome by email. The output documents are attached by default ({@code
 * attachOutputs}) — the PDF an operator reviews, annotations included; the producing step's report
 * JSON is only attached when {@code attachReport} is set. Subject and body are templates rendered
 * against the run's context (see {@link EmailTemplate}); {@code mode} chooses one mail per delivery
 * or one per file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailOutputSink implements PolicyOutputSink {

    private final ApplicationProperties applicationProperties;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return "smtp";
    }

    @Override
    public boolean supports(OutputSpec spec) {
        return spec != null && type().equals(spec.type());
    }

    @Override
    public void validate(OutputSpec spec) {
        sender(); // mail.enabled + bean presence, with a readable error when off
        EmailTarget target = EmailTarget.from(spec.options());
        if (target.to().isEmpty()) {
            throw new IllegalArgumentException("An email destination needs at least one recipient");
        }
        target.recipients(); // parse-checked
        EmailTemplate.renderText(target.subject(), Map.of());
        EmailTemplate.renderText(target.body(), Map.of());
    }

    @Override
    public List<ResultFile> deliver(
            OutputDelivery delivery, List<Resource> outputs, OutputSpec spec) throws IOException {
        EmailTarget target = EmailTarget.from(spec.options());
        JavaMailSender sender = sender();
        List<InternetAddress> recipients = target.recipients();
        String from = applicationProperties.getMail().getFrom();

        List<List<Resource>> groups =
                outputs.isEmpty()
                        // A report-only terminal step produces no files: still send the run's
                        // outcome as one mail, whatever the grouping mode says.
                        ? List.of(List.of())
                        : EmailTarget.MODE_PER_FILE.equals(target.mode())
                                ? outputs.stream().map(List::of).toList()
                                : List.of(outputs);

        List<ResultFile> receipts = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            List<Resource> group = groups.get(i);
            String subject = send(delivery, target, sender, recipients, from, group);
            receipts.add(storeReceipt(recipients, subject, group, i + 1));
        }
        return receipts;
    }

    private String send(
            OutputDelivery delivery,
            EmailTarget target,
            JavaMailSender sender,
            List<InternetAddress> recipients,
            String from,
            List<Resource> group)
            throws IOException {
        Map<String, String> vars = vars(delivery, group);
        String subject = EmailTemplate.renderText(target.subject(), vars);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(recipients.toArray(InternetAddress[]::new));
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setSubject(subject);
            byte[] logo = logoBytes();
            helper.setText(
                    EmailTemplate.renderText(target.body(), vars),
                    EmailLayout.render(
                            EmailTemplate.renderHtml(target.body(), vars),
                            rows(delivery, group),
                            vars,
                            logo != null));
            boolean attachedReport = false;
            for (int i = 0; i < group.size(); i++) {
                Resource file = group.get(i);
                if (target.attachReport()) {
                    JsonNode report = delivery.reportFor(file);
                    if (report != null) {
                        byte[] json =
                                objectMapper
                                        .writerWithDefaultPrettyPrinter()
                                        .writeValueAsBytes(report);
                        String base =
                                OutputNames.safeName(file.getFilename(), i)
                                        .replaceAll("(?i)\\.pdf$", "");
                        helper.addAttachment(base + "-report.json", new ByteArrayResource(json));
                        attachedReport = true;
                    }
                }
                if (target.attachOutputs()) {
                    helper.addAttachment(OutputNames.safeName(file.getFilename(), i), file);
                }
            }
            boolean reportOnly = group.isEmpty();
            if (delivery.runReport() != null
                    && ((target.attachReport() && !attachedReport) || reportOnly)) {
                byte[] json =
                        objectMapper
                                .writerWithDefaultPrettyPrinter()
                                .writeValueAsBytes(delivery.runReport());
                helper.addAttachment("run-report.json", new ByteArrayResource(json));
            }
            if (logo != null) {
                helper.addInline("stirling-logo", new ByteArrayResource(logo), "image/png");
            }
            message.saveChanges();
            sender.send(message);
        } catch (MessagingException e) {
            throw new IOException("Email delivery failed: " + e.getMessage(), e);
        }
        return subject;
    }

    /** Placeholder values: run identity, input/output names, and the group's aggregated verdict. */
    private Map<String, String> vars(OutputDelivery delivery, List<Resource> group) {
        List<String> inputNames =
                delivery.inputs().primary().stream()
                        .map(Resource::getFilename)
                        .filter(n -> n != null && !n.isBlank())
                        .toList();
        List<String> outputNames =
                group.stream().map(r -> OutputNames.safeName(r.getFilename(), 0)).toList();

        int errors = 0;
        int warnings = 0;
        String verdict = "none";
        Set<String> fixups = new LinkedHashSet<>();
        List<JsonNode> reports = new ArrayList<>();
        for (Resource file : group) {
            JsonNode report = delivery.reportFor(file);
            if (report != null) {
                reports.add(report);
            }
        }
        // The terminal step's report stands in when the group carries none of its own — a
        // report-only step emits no file for a per-file report to hang on.
        if (reports.isEmpty() && delivery.runReport() != null) {
            reports.add(delivery.runReport());
        }
        boolean anyReport = false;
        for (JsonNode report : reports) {
            JsonNode preflight = report == null ? null : report.path("preflight");
            if (preflight == null || preflight.isMissingNode() || preflight.isNull()) {
                continue;
            }
            anyReport = true;
            verdict = worst(verdict, preflight.path("verdict").asString(null));
            errors += preflight.path("errors").asInt(0);
            warnings += preflight.path("warnings").asInt(0);
            for (JsonNode fixup : preflight.path("fixupsApplied")) {
                if (fixup.isTextual()) fixups.add(fixup.asString());
            }
        }

        String filename =
                !outputNames.isEmpty()
                        ? outputNames.getFirst()
                        : (inputNames.isEmpty() ? "" : inputNames.getFirst());
        return Map.ofEntries(
                Map.entry("filename", filename),
                Map.entry("fileNames", String.join(", ", outputNames)),
                Map.entry("inputNames", String.join(", ", inputNames)),
                Map.entry("fileCount", String.valueOf(group.size())),
                Map.entry("pipeline", delivery.policyName() == null ? "" : delivery.policyName()),
                Map.entry("verdict", anyReport ? verdict : "n/a"),
                Map.entry("errorCount", String.valueOf(errors)),
                Map.entry("warningCount", String.valueOf(warnings)),
                Map.entry("fixups", String.join(", ", fixups)),
                Map.entry("runId", delivery.runId() == null ? "" : delivery.runId()),
                Map.entry("date", Instant.now().toString()),
                Map.entry(
                        "status",
                        switch (verdict) {
                            case "fail" -> "failed";
                            case "warn" -> "completed with warnings";
                            default -> "completed";
                        }));
    }

    /** One table row per delivered file: verdict, error/warning counts, and applied fixups. */
    private List<EmailLayout.Row> rows(OutputDelivery delivery, List<Resource> group) {
        List<EmailLayout.Row> rows = new ArrayList<>();
        for (int i = 0; i < group.size(); i++) {
            Resource file = group.get(i);
            JsonNode preflight = null;
            JsonNode report = delivery.reportFor(file);
            if (report != null) {
                JsonNode node = report.path("preflight");
                if (!node.isMissingNode() && !node.isNull()) preflight = node;
            }
            List<String> fixups = new ArrayList<>();
            String verdict = "n/a";
            int errors = 0;
            int warnings = 0;
            if (preflight != null) {
                verdict = preflight.path("verdict").asString("n/a");
                errors = preflight.path("errors").asInt(0);
                warnings = preflight.path("warnings").asInt(0);
                for (JsonNode fixup : preflight.path("fixupsApplied")) {
                    if (fixup.isTextual()) fixups.add(fixup.asString());
                }
            }
            rows.add(
                    new EmailLayout.Row(
                            OutputNames.safeName(file.getFilename(), i),
                            verdict,
                            errors,
                            warnings,
                            fixups));
        }
        return rows;
    }

    /** The white Stirling banner logo for the mail header; null when the asset is unavailable. */
    private static byte[] logoBytes() {
        try (var in =
                EmailOutputSink.class.getResourceAsStream(
                        "/static/images/stirling-logo-white.png")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            log.warn("Mail logo unavailable, sending text header instead: {}", e.getMessage());
            return null;
        }
    }

    private static String worst(String current, String candidate) {
        int currentRank = rank(current);
        return rank(candidate) > currentRank ? candidate : current;
    }

    private static int rank(String verdict) {
        return switch (verdict == null ? "" : verdict) {
            case "fail" -> 3;
            case "warn" -> 2;
            case "pass" -> 1;
            default -> 0;
        };
    }

    /** What was sent, kept as the run's deliverable so the run record can link to it. */
    private ResultFile storeReceipt(
            List<InternetAddress> recipients, String subject, List<Resource> group, int index)
            throws IOException {
        byte[] receipt =
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(
                                Map.of(
                                        "type",
                                        "smtp",
                                        "to",
                                        recipients.toString(),
                                        "subject",
                                        subject,
                                        "files",
                                        group.stream()
                                                .map(r -> OutputNames.safeName(r.getFilename(), 0))
                                                .toList(),
                                        "sentAt",
                                        Instant.now().toString()));
        String name = "email-receipt-" + index + ".json";
        FileStorage.StoredFile stored =
                fileStorage.storeInputStream(new ByteArrayInputStream(receipt), name);
        return ResultFile.builder()
                .fileId(stored.fileId())
                .fileName(name)
                .contentType("application/json")
                .fileSize(stored.size())
                .build();
    }

    private JavaMailSender sender() {
        ApplicationProperties.Mail mail = applicationProperties.getMail();
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        // MailConfig builds a sender on mail.enabled alone — without a host it can never
        // connect, so treat a missing host like a missing bean.
        if (mail == null
                || !mail.isEnabled()
                || sender == null
                || mail.getHost() == null
                || mail.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "Email destinations require SMTP: set mail.enabled and mail.host in settings");
        }
        return sender;
    }
}
