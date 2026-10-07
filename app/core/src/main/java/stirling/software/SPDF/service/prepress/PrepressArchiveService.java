package stirling.software.SPDF.service.prepress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import stirling.software.common.configuration.InstallationPathConfig;
import stirling.software.common.model.ApplicationProperties;

/**
 * Server-side version archive for prepress operations. Every recorded transform stores the output
 * bytes under {@code <root>/chains/<chainId>/vNNN-<tool>/} and appends one JSON line to that
 * chain's {@code manifest.jsonl} — the workflow trace lives on the server, never inside the PDF, so
 * the files stay RIP-safe.
 *
 * <p>Chaining is content-based: the SHA-256 of the input is looked up in {@code <root>/index/}
 * which maps every stored document (root source or produced output) to its chain and version. A
 * re-uploaded fixed PDF therefore attaches to its own chain automatically.
 *
 * <p>Audit calls (preflight, previews) append to the chain and index the input hash so a later
 * transform still attaches — but they do not retain the document bytes: the source is only stored
 * once the file actually goes through a transform, backfilling the chain's {@code v001-source}
 * entry at that point.
 */
@Service
@Slf4j
public class PrepressArchiveService {

    public static final String HEADER_CHAIN_ID = "X-Prepress-Chain-Id";
    public static final String HEADER_VERSION = "X-Prepress-Version";

    private static final String MANIFEST = "manifest.jsonl";
    private static final String SPOOL_DIR = ".spool";

    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;
    private final int maxVersionsPerChain;
    private final Path root;

    public PrepressArchiveService(ApplicationProperties properties) {
        ApplicationProperties.Prepress.Archive config = properties.getPrepress().getArchive();
        this.enabled = config.isEnabled();
        this.maxVersionsPerChain = config.getMaxVersionsPerChain();
        // Absolute + normalized: the default config path is "./configs", and resolveVersion
        // compares normalized stored paths against this root — an unnormalized root would
        // never match.
        Path configured =
                config.getRoot().isBlank()
                        ? Path.of(InstallationPathConfig.getConfigPath(), "prepress-archive")
                        : Path.of(config.getRoot());
        this.root = configured.toAbsolutePath().normalize();
    }

    /** What an archived transform produced, for response headers. Null fields when disabled. */
    public record Handle(String chainId, int version) {}

    /** Sets {@code X-Prepress-Chain-Id}/{@code X-Prepress-Version} on a response, when present. */
    public static void setChainHeaders(ResponseEntity<?> response, Optional<Handle> handle) {
        handle.ifPresent(
                h -> {
                    response.getHeaders().set(HEADER_CHAIN_ID, h.chainId());
                    response.getHeaders().set(HEADER_VERSION, String.valueOf(h.version()));
                });
    }

    /**
     * Records a transform: the output is stored as a new version on the input's chain (the input
     * itself is stored as {@code v001-source} on first sight). Returns the archive handle, or an
     * empty Optional when the archive is disabled or the write failed — archiving never breaks a
     * job.
     */
    public Optional<Handle> recordVersion(
            String tool,
            MultipartFile input,
            byte[] output,
            String outputName,
            Map<String, ?> meta) {
        if (!enabled || input == null || output == null) {
            return Optional.empty();
        }
        try {
            InputSpool spool = spool(input);
            try {
                return Optional.of(
                        commitTransform(tool, input, spool, null, output, outputName, meta));
            } finally {
                Files.deleteIfExists(spool.file());
            }
        } catch (Exception e) {
            log.warn(
                    "Prepress archive failed for {} on {}: {}",
                    tool,
                    input.getOriginalFilename(),
                    e.toString());
            return Optional.empty();
        }
    }

    /** File-backed variant for endpoints that write to a managed temp file. */
    public Optional<Handle> recordVersion(
            String tool,
            MultipartFile input,
            Path outputFile,
            String outputName,
            Map<String, ?> meta) {
        if (!enabled || input == null || outputFile == null) {
            return Optional.empty();
        }
        try {
            InputSpool spool = spool(input);
            try {
                return Optional.of(
                        commitTransform(tool, input, spool, outputFile, null, outputName, meta));
            } finally {
                Files.deleteIfExists(spool.file());
            }
        } catch (Exception e) {
            log.warn(
                    "Prepress archive failed for {} on {}: {}",
                    tool,
                    input.getOriginalFilename(),
                    e.toString());
            return Optional.empty();
        }
    }

    /**
     * Records an analysis step (preflight, fix preview): no file stored, but the manifest gains an
     * {@code audit} entry so the chain shows the full workflow, not only the transforms.
     */
    public void recordAudit(String tool, MultipartFile input, Map<String, ?> meta) {
        if (!enabled || input == null) {
            return;
        }
        try {
            String shaIn;
            try (InputStream in = input.getInputStream()) {
                shaIn = sha256(in);
            }
            commitAudit(tool, input, shaIn, meta);
        } catch (Exception e) {
            log.warn(
                    "Prepress archive audit failed for {} on {}: {}",
                    tool,
                    input.getOriginalFilename(),
                    e.toString());
        }
    }

    /** Input bytes staged on disk with their hash — avoids a second copy of the upload in heap. */
    private record InputSpool(String sha256, Path file, long bytes) {}

    private InputSpool spool(MultipartFile input) throws IOException {
        Path spoolDir = root.resolve(SPOOL_DIR);
        Files.createDirectories(spoolDir);
        Path tmp = Files.createTempFile(spoolDir, "in-", ".bin");
        MessageDigest digest = newDigest();
        try (InputStream in = input.getInputStream();
                DigestInputStream din = new DigestInputStream(in, digest)) {
            long bytes = Files.copy(din, tmp, StandardCopyOption.REPLACE_EXISTING);
            return new InputSpool(HexFormat.of().formatHex(digest.digest()), tmp, bytes);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private synchronized Handle commitTransform(
            String tool,
            MultipartFile input,
            InputSpool spool,
            Path outputFile,
            byte[] outputBytes,
            String outputName,
            Map<String, ?> meta)
            throws IOException {
        Files.createDirectories(root.resolve("index"));
        String shaIn = spool.sha256();
        IndexEntry parent = lookup(shaIn).orElse(null);
        String chainId;
        int parentVersion;
        Path chainDir;
        String inputName = safeName(input.getOriginalFilename());
        if (parent == null) {
            chainId = shaIn.substring(0, 16);
            chainDir = chainDir(chainId);
            Files.createDirectories(chainDir);
            Path sourceDest = versionDir(chainDir, 1, "source").resolve(inputName);
            Files.createDirectories(sourceDest.getParent());
            moveAtomic(spool.file(), sourceDest);
            append(
                    chainDir,
                    sourceEntry(
                            1, shaIn, inputName, rel(chainDir, sourceDest), spool.bytes(), false));
            writeIndex(shaIn, new IndexEntry(chainId, 1));
            parentVersion = 1;
        } else {
            chainId = parent.chainId();
            parentVersion = parent.version();
            chainDir = chainDir(chainId);
            backfillIfMissing(chainDir, shaIn, inputName, spool, parentVersion);
        }

        int version = nextVersion(chainDir);
        String destName = safeName(outputName != null ? outputName : input.getOriginalFilename());
        Path dest = versionDir(chainDir, version, tool).resolve(destName);
        Files.createDirectories(dest.getParent());
        // The output hash is produced by the copy itself, in the same single pass.
        String shaOut =
                outputFile != null ? copyAtomic(outputFile, dest) : writeAtomic(dest, outputBytes);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("v", version);
        entry.put("kind", "version");
        entry.put("tool", tool);
        entry.put("at", Instant.now().toString());
        entry.put("parent", parentVersion);
        entry.put("in", shaIn);
        entry.put("out", shaOut);
        entry.put("file", rel(chainDir, dest));
        entry.put("name", destName);
        entry.put("bytes", Files.size(dest));
        if (meta != null) entry.put("meta", meta);
        append(chainDir, entry);
        writeIndex(shaOut, new IndexEntry(chainId, version));
        evict(chainDir);
        return new Handle(chainId, version);
    }

    private synchronized void commitAudit(
            String tool, MultipartFile input, String shaIn, Map<String, ?> meta)
            throws IOException {
        Files.createDirectories(root.resolve("index"));
        IndexEntry parent = lookup(shaIn).orElse(null);
        String chainId;
        int parentVersion;
        Path chainDir;
        if (parent == null) {
            chainId = shaIn.substring(0, 16);
            chainDir = chainDir(chainId);
            Files.createDirectories(chainDir);
            // Index the hash so later transforms attach, but retain no bytes for an audit-only
            // sighting — the source is stored (backfilled) by the first real transform instead.
            append(
                    chainDir,
                    sourceEntry(
                            1,
                            shaIn,
                            safeName(input.getOriginalFilename()),
                            null,
                            input.getSize(),
                            false));
            writeIndex(shaIn, new IndexEntry(chainId, 1));
            parentVersion = 1;
        } else {
            chainId = parent.chainId();
            parentVersion = parent.version();
            chainDir = chainDir(chainId);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", "audit");
        entry.put("tool", tool);
        entry.put("at", Instant.now().toString());
        entry.put("on", shaIn);
        entry.put("onVersion", parentVersion);
        entry.put("name", safeName(input.getOriginalFilename()));
        if (meta != null) entry.put("meta", meta);
        append(chainDir, entry);
    }

    private static Map<String, Object> sourceEntry(
            int version, String sha, String name, String relFile, long bytes, boolean backfilled) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("v", version);
        entry.put("kind", "source");
        entry.put("tool", "source");
        entry.put("at", Instant.now().toString());
        entry.put("in", sha);
        if (relFile != null) entry.put("file", relFile);
        entry.put("name", name);
        entry.put("bytes", bytes);
        if (backfilled) entry.put("backfilled", true);
        return entry;
    }

    /**
     * Stores the input as the chain's source when its manifest entry exists but has no stored file
     * (the chain was opened by an audit). Evicted versions are never resurrected — only entries
     * that never had a {@code file} are backfilled.
     */
    private void backfillIfMissing(
            Path chainDir, String shaIn, String inputName, InputSpool spool, int parentVersion)
            throws IOException {
        Map<String, Object> parentStep = null;
        for (Map<String, Object> step : readManifest(chainDir)) {
            if (step.get("v") instanceof Number n
                    && n.intValue() == parentVersion
                    && !step.containsKey("file")) {
                parentStep = step;
                break;
            }
        }
        if (parentStep == null) {
            return;
        }
        String parentTool = String.valueOf(parentStep.getOrDefault("tool", "source"));
        Path dest = versionDir(chainDir, parentVersion, parentTool).resolve(inputName);
        Files.createDirectories(dest.getParent());
        moveAtomic(spool.file(), dest);
        append(
                chainDir,
                sourceEntry(
                        parentVersion,
                        shaIn,
                        inputName,
                        rel(chainDir, dest),
                        Files.size(dest),
                        true));
    }

    /** All chains, most recently active first. */
    public List<Map<String, Object>> listChains() throws IOException {
        Path chainsDir = root.resolve("chains");
        if (!Files.isDirectory(chainsDir)) {
            return List.of();
        }
        List<Map<String, Object>> chains = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(chainsDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                List<Map<String, Object>> steps = readManifest(dir);
                if (steps.isEmpty()) continue;
                Map<String, Object> summary = new LinkedHashMap<>();
                Map<String, Object> source =
                        steps.stream()
                                .filter(s -> "source".equals(s.get("kind")))
                                .findFirst()
                                .orElse(Map.of());
                Map<String, Object> last = steps.get(steps.size() - 1);
                summary.put("chainId", dir.getFileName().toString());
                summary.put("sourceName", source.getOrDefault("name", "?"));
                summary.put("sourceSha256", source.getOrDefault("in", "?"));
                summary.put(
                        "versions",
                        steps.stream().filter(s -> "version".equals(s.get("kind"))).count());
                summary.put("steps", steps.size());
                summary.put("lastTool", last.get("tool"));
                summary.put("lastAt", last.get("at"));
                chains.add(summary);
            }
        }
        chains.sort(
                Comparator.comparing(
                        c -> String.valueOf(c.get("lastAt")), Comparator.reverseOrder()));
        return chains;
    }

    /** Every manifest line of a chain, in order. Empty if the chain does not exist. */
    public List<Map<String, Object>> getChain(String chainId) throws IOException {
        if (!chainId.matches("[a-f0-9]{16}")) {
            return List.of();
        }
        return readManifest(chainDir(chainId));
    }

    /** The stored file for one version of a chain, if present on disk (not evicted). */
    public Optional<StoredVersion> resolveVersion(String chainId, int version) throws IOException {
        if (!chainId.matches("[a-f0-9]{16}")) {
            return Optional.empty();
        }
        Path chainDir = chainDir(chainId);
        for (Map<String, Object> step : readManifest(chainDir)) {
            if (!(step.get("v") instanceof Number n) || n.intValue() != version) continue;
            Object rel = step.get("file");
            if (rel == null) continue;
            Path file = chainDir.resolve(rel.toString()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                continue;
            }
            return Optional.of(
                    new StoredVersion(
                            file, String.valueOf(step.getOrDefault("name", "document.pdf"))));
        }
        return Optional.empty();
    }

    public record StoredVersion(Path file, String name) {}

    private record IndexEntry(String chainId, int version) {}

    private Path chainDir(String chainId) {
        return root.resolve("chains").resolve(chainId);
    }

    private static Path versionDir(Path chainDir, int version, String tool) {
        return chainDir.resolve(String.format("v%03d-%s", version, tool));
    }

    private static String rel(Path base, Path file) {
        return base.relativize(file).toString();
    }

    private int nextVersion(Path chainDir) throws IOException {
        int max = 0;
        for (Map<String, Object> step : readManifest(chainDir)) {
            if (step.get("v") instanceof Number n) {
                max = Math.max(max, n.intValue());
            }
        }
        return max + 1;
    }

    private void evict(Path chainDir) throws IOException {
        if (maxVersionsPerChain <= 0) return;
        List<Map<String, Object>> steps = readManifest(chainDir);
        List<Map<String, Object>> stored =
                steps.stream()
                        .filter(s -> "version".equals(s.get("kind")) && s.get("v") != null)
                        .toList();
        int excess = stored.size() - maxVersionsPerChain;
        for (Map<String, Object> step : stored) {
            if (excess <= 0) break;
            Object rel = step.get("file");
            if (rel == null) continue;
            Path dir = chainDir.resolve(rel.toString()).normalize().getParent();
            if (dir != null && dir.startsWith(chainDir) && Files.isDirectory(dir)) {
                deleteTree(dir);
                excess--;
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private Optional<IndexEntry> lookup(String sha) throws IOException {
        Path index = root.resolve("index").resolve(sha);
        if (!Files.isRegularFile(index)) {
            return Optional.empty();
        }
        String[] parts = Files.readString(index).trim().split("\t");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new IndexEntry(parts[0], Integer.parseInt(parts[1])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void writeIndex(String sha, IndexEntry entry) throws IOException {
        Path index = root.resolve("index").resolve(sha);
        if (!Files.exists(index)) {
            writeAtomic(
                    index,
                    (entry.chainId() + "\t" + entry.version()).getBytes(StandardCharsets.UTF_8));
        }
    }

    // A crash mid-write must not leave a partial file referenced by the manifest.
    // Both return the SHA-256 of what was written, computed during the write pass.
    private static String writeAtomic(Path dest, byte[] bytes) throws IOException {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        MessageDigest digest = newDigest();
        digest.update(bytes);
        Files.write(tmp, bytes);
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String copyAtomic(Path src, Path dest) throws IOException {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(src);
                DigestInputStream din = new DigestInputStream(in, digest)) {
            Files.copy(din, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void moveAtomic(Path src, Path dest) throws IOException {
        Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void append(Path chainDir, Map<String, Object> entry) throws IOException {
        Files.writeString(
                chainDir.resolve(MANIFEST),
                mapper.writeValueAsString(entry) + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private List<Map<String, Object>> readManifest(Path chainDir) throws IOException {
        Path manifest = chainDir.resolve(MANIFEST);
        List<Map<String, Object>> steps = new ArrayList<>();
        if (!Files.isRegularFile(manifest)) {
            return steps;
        }
        for (String line : Files.readAllLines(manifest)) {
            if (line.isBlank()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> step = mapper.readValue(line, Map.class);
                steps.add(step);
            } catch (IOException e) {
                log.warn("Skipping corrupt manifest line in {}: {}", manifest, e.toString());
            }
        }
        return steps;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return sha256(in);
        }
    }

    private static String sha256(InputStream in) throws IOException {
        MessageDigest digest = newDigest();
        DigestInputStream din = new DigestInputStream(in, digest);
        din.transferTo(OutputStream.nullOutputStream());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "document.pdf";
        }
        String leaf;
        try {
            leaf = Path.of(name).getFileName().toString();
        } catch (InvalidPathException e) {
            leaf = name;
        }
        String cleaned = leaf.replaceAll("[^\\w.()\\- ]", "_");
        // A name made only of dots would resolve outside its version directory.
        return cleaned.isBlank() || cleaned.chars().allMatch(c -> c == '.')
                ? "document.pdf"
                : cleaned;
    }
}
