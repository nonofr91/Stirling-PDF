package stirling.software.common.service;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Produces a per-pixel subject confidence mask for a rendered page. Implemented by the proprietary
 * ONNX-backed service when the runtime and a model are installed; absent in builds without them, so
 * callers must treat the dependency as optional.
 */
public interface SubjectMattingService {

    /**
     * True when an inference engine is bundled AND {@code modelId} resolves to an installed model.
     */
    boolean isAvailable(String modelId);

    /** True when {@code modelId} exists in the bundled catalog (installed or not). */
    boolean isKnownModel(String modelId);

    /** Catalog ids of models whose file is installed and verified. */
    List<String> installedModelIds();

    /**
     * True while {@code modelId} is being downloaded, so an explicit-AI request can tell the caller
     * to retry rather than reporting a plain "not installed".
     */
    default boolean isModelInstalling(String modelId) {
        return false;
    }

    /**
     * Runs subject matting on {@code image} with {@code modelId}.
     *
     * @return row-major mask, one confidence value in [0,1] per pixel, same dimensions as the input
     * @throws IllegalStateException the model is not installed or the engine is missing
     */
    float[] matte(BufferedImage image, String modelId);
}
