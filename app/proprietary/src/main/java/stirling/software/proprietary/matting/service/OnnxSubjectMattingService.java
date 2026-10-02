package stirling.software.proprietary.matting.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.common.service.SubjectMattingService;
import stirling.software.proprietary.matting.catalog.MattingCatalogEntry;
import stirling.software.proprietary.matting.catalog.MattingCatalogService;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * U-2-Net style subject matting: the image is stretched to the model's square input, run once on
 * CPU, and the single-channel confidence map is min-max normalised and bilinearly resized back to
 * the source size.
 */
@Slf4j
@Service
@ConditionalOnClass(name = "ai.onnxruntime.OrtEnvironment")
@RequiredArgsConstructor
public class OnnxSubjectMattingService implements SubjectMattingService {

    private final MattingModelManager manager;
    private final MattingCatalogService catalog;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final Map<String, OrtSession> sessions = new ConcurrentHashMap<>();
    private final Semaphore concurrency =
            new Semaphore(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

    @Override
    public boolean isAvailable(String modelId) {
        return manager.isEngineAvailable()
                && catalog.getById(modelId).isPresent()
                && manager.isInstalled(modelId);
    }

    @Override
    public boolean isKnownModel(String modelId) {
        return catalog.getById(modelId).isPresent();
    }

    @Override
    public List<String> installedModelIds() {
        return manager.installedIds();
    }

    @Override
    public boolean isModelInstalling(String modelId) {
        return manager.isDownloading() && !manager.isInstalled(modelId);
    }

    @Override
    public float[] matte(BufferedImage image, String modelId) {
        MattingCatalogEntry entry =
                catalog.getById(modelId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Unknown matting model: " + modelId));
        Path file =
                manager.modelFile(modelId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Matting model '"
                                                        + modelId
                                                        + "' is not installed"));
        OrtSession session =
                sessions.computeIfAbsent(
                        modelId,
                        id -> {
                            try {
                                return env.createSession(file.toString());
                            } catch (OrtException e) {
                                throw new IllegalStateException(
                                        "Failed to load matting model " + id, e);
                            }
                        });
        int size = entry.getInputSize();
        float[] chw = preprocess(image, size, entry);
        try {
            concurrency.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Matting interrupted", e);
        }
        try (OnnxTensor input =
                        OnnxTensor.createTensor(
                                env, FloatBuffer.wrap(chw), new long[] {1, 3, size, size});
                OrtSession.Result result =
                        session.run(Map.of(session.getInputNames().iterator().next(), input))) {
            OnnxValue out = result.get(0);
            float[][][][] tensor = (float[][][][]) out.getValue();
            float[] small = minMaxNormalize(tensor[0][0]);
            return resizeBilinear(small, size, size, image.getWidth(), image.getHeight());
        } catch (OrtException e) {
            throw new IllegalStateException("Matting inference failed: " + e.getMessage(), e);
        } finally {
            concurrency.release();
        }
    }

    private static float[] preprocess(BufferedImage src, int size, MattingCatalogEntry entry) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        float[] chw = new float[3 * size * size];
        float[] mean = entry.getNormMean();
        float[] std = entry.getNormStd();
        for (int i = 0; i < size * size; i++) {
            int rgb = scaled.getRGB(i % size, i / size);
            chw[i] = (((rgb >> 16) & 0xFF) / 255f - mean[0]) / std[0];
            chw[size * size + i] = (((rgb >> 8) & 0xFF) / 255f - mean[1]) / std[1];
            chw[2 * size * size + i] = ((rgb & 0xFF) / 255f - mean[2]) / std[2];
        }
        return chw;
    }

    private static float[] minMaxNormalize(float[][] plane) {
        int side = plane.length;
        int dim = plane[0].length;
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        float[] flat = new float[side * dim];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < dim; x++) {
                float v = plane[y][x];
                flat[y * dim + x] = v;
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        float range = hi - lo;
        if (range <= 0) {
            return flat;
        }
        for (int i = 0; i < flat.length; i++) {
            flat[i] = (flat[i] - lo) / range;
        }
        return flat;
    }

    private static float[] resizeBilinear(float[] src, int sw, int sh, int dw, int dh) {
        float[] out = new float[dw * dh];
        for (int y = 0; y < dh; y++) {
            float sy = (y + 0.5f) * sh / dh - 0.5f;
            int y0 = (int) Math.floor(sy);
            float fy = sy - y0;
            int y0c = Math.max(0, Math.min(sh - 1, y0));
            int y1c = Math.max(0, Math.min(sh - 1, y0 + 1));
            for (int x = 0; x < dw; x++) {
                float sx = (x + 0.5f) * sw / dw - 0.5f;
                int x0 = (int) Math.floor(sx);
                float fx = sx - x0;
                int x0c = Math.max(0, Math.min(sw - 1, x0));
                int x1c = Math.max(0, Math.min(sw - 1, x0 + 1));
                float v00 = src[y0c * sw + x0c];
                float v01 = src[y0c * sw + x1c];
                float v10 = src[y1c * sw + x0c];
                float v11 = src[y1c * sw + x1c];
                out[y * dw + x] =
                        v00 * (1 - fx) * (1 - fy)
                                + v01 * fx * (1 - fy)
                                + v10 * (1 - fx) * fy
                                + v11 * fx * fy;
            }
        }
        return out;
    }

    @PreDestroy
    void close() {
        sessions.values()
                .forEach(
                        s -> {
                            try {
                                s.close();
                            } catch (OrtException e) {
                                log.warn("Failed to close matting session", e);
                            }
                        });
        sessions.clear();
    }
}
