package stirling.software.proprietary.matting.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.common.configuration.InstallationPathConfig;
import stirling.software.proprietary.matting.catalog.MattingCatalogEntry;
import stirling.software.proprietary.matting.catalog.MattingCatalogService;

/**
 * Downloads, verifies and tracks subject-matting models under {@code <configs>/models/cut-contour}.
 * URLs and SHA-256 come only from the bundled catalog, so a caller can never steer the download at
 * an arbitrary host. The default model is fetched once in the background when the ONNX runtime is
 * bundled, so AI mode works out of the box on fat builds; air-gapped deployments drop the .onnx
 * into the directory manually.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MattingModelManager {

    private static final String ALLOWED_MODEL_URL_PREFIX = "https://huggingface.co/";
    private static final List<String> ALLOWED_REDIRECT_HOSTS = List.of("huggingface.co", "hf.co");
    private static final int MAX_REDIRECTS = 5;
    private static final long DOWNLOAD_SLACK_BYTES = 8L * 1024 * 1024;
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private static final boolean ENGINE_PRESENT = isOnnxRuntimePresent();

    private final MattingCatalogService catalog;
    private final AtomicBoolean installing = new AtomicBoolean(false);
    private volatile String error = null;

    private static boolean isOnnxRuntimePresent() {
        try {
            Class.forName(
                    "ai.onnxruntime.OrtEnvironment",
                    false,
                    MattingModelManager.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(modelDir());
        } catch (IOException e) {
            log.warn("Cannot create matting model directory {}: {}", modelDir(), e.getMessage());
            return;
        }
        if (!ENGINE_PRESENT) {
            return;
        }
        Optional<MattingCatalogEntry> def = catalog.getDefault();
        if (def.isPresent() && !isInstalled(def.get().getId()) && def.get().getOnnxUrl() != null) {
            Thread thread =
                    new Thread(
                            () -> {
                                try {
                                    install(def.get().getId());
                                } catch (Exception e) {
                                    log.warn(
                                            "Matting model '{}' auto-install failed: {}",
                                            def.get().getId(),
                                            e.getMessage());
                                }
                            },
                            "matting-model-install");
            thread.setDaemon(true);
            thread.start();
        }
    }

    public boolean isEngineAvailable() {
        return ENGINE_PRESENT;
    }

    public boolean isInstalled(String modelId) {
        return modelFile(modelId).isPresent();
    }

    public Optional<Path> modelFile(String modelId) {
        if (modelId == null || !SAFE_ID.matcher(modelId).matches()) {
            return Optional.empty();
        }
        Path p = modelDir().resolve(modelId + ".onnx");
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    public List<String> installedIds() {
        try (Stream<Path> files = Files.list(modelDir())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".onnx"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.onnx$", ""))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public boolean isDownloading() {
        return installing.get();
    }

    public String lastError() {
        return error;
    }

    Path modelDir() {
        return Path.of(InstallationPathConfig.getConfigPath(), "models", "cut-contour");
    }

    /**
     * Synchronous download + sha256 verify + atomic publish. Runs in a daemon thread at startup;
     * also callable on demand.
     */
    public void install(String modelId) {
        if (!SAFE_ID.matcher(modelId).matches()) {
            throw new IllegalArgumentException("Invalid model id: " + modelId);
        }
        MattingCatalogEntry entry =
                catalog.getById(modelId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Unknown model id: " + modelId));
        String url = entry.getOnnxUrl();
        String sha = entry.getSha256() == null ? null : entry.getSha256().toLowerCase(Locale.ROOT);
        if (url == null || url.isBlank() || sha == null || sha.isBlank()) {
            throw new IllegalStateException("Model '" + modelId + "' has no download URL/checksum");
        }
        if (!url.startsWith(ALLOWED_MODEL_URL_PREFIX)) {
            throw new IllegalArgumentException("Model URL is not on the allowlist: " + url);
        }
        if (!installing.compareAndSet(false, true)) {
            throw new IllegalStateException("A matting model install is already in progress");
        }
        Path tmp = null;
        try {
            Files.createDirectories(modelDir());
            tmp = Files.createTempFile(modelDir(), modelId + "-", ".part");
            long size = download(url, entry.getSizeBytes(), tmp);
            String actual = sha256Of(tmp);
            if (!actual.equalsIgnoreCase(sha)) {
                Files.deleteIfExists(tmp);
                throw new IOException(
                        "Model checksum mismatch: expected " + sha + " got " + actual);
            }
            Path target = modelDir().resolve(modelId + ".onnx");
            try {
                Files.move(
                        tmp,
                        target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Matting model '{}' installed ({} bytes)", modelId, size);
            error = null;
        } catch (Exception e) {
            error = e.getMessage();
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
            throw new RuntimeException("Matting model install failed: " + e.getMessage(), e);
        } finally {
            installing.set(false);
        }
    }

    private long download(String url, long expectedSize, Path tmp) throws IOException {
        String current = url;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(current).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                if (loc == null) {
                    throw new IOException("Redirect without Location from " + current);
                }
                URI next = URI.create(loc);
                String host = next.getHost();
                if (host == null
                        || (ALLOWED_REDIRECT_HOSTS.stream()
                                .noneMatch(h -> host.equals(h) || host.endsWith("." + h)))) {
                    throw new IOException("Download redirect left the allowlist: " + loc);
                }
                current = loc;
                continue;
            }
            if (code != 200) {
                throw new IOException("HTTP " + code + " downloading model");
            }
            long cap = expectedSize > 0 ? expectedSize + DOWNLOAD_SLACK_BYTES : MAX_DOWNLOAD_BYTES;
            try (InputStream in = conn.getInputStream();
                    OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                long total = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (total > cap) {
                        throw new IOException("Model download exceeded expected size");
                    }
                    out.write(buf, 0, n);
                }
                return total;
            }
        }
        throw new IOException("Too many redirects downloading model");
    }

    private static String sha256Of(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
