package stirling.software.proprietary.matting.catalog;

import lombok.Data;

/** One downloadable subject-matting model from {@code matting/model-catalog.json}. */
@Data
public class MattingCatalogEntry {

    private String id;
    private String displayName;
    private String description;
    private String license;
    private long sizeBytes;
    private String onnxUrl;
    private String sha256;

    /** Square side the network consumes; input is stretched to inputSize × inputSize. */
    private int inputSize = 320;

    private float[] normMean = {0.485f, 0.456f, 0.406f};
    private float[] normStd = {0.229f, 0.224f, 0.225f};

    /** The model used when the request leaves the id blank. */
    private boolean defaultModel = false;
}
