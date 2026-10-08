package stirling.software.common.model.job;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Represents a single file result from a job execution */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultFile {

    /** The file ID for accessing the file */
    private String fileId;

    /** The original file name */
    private String fileName;

    /** MIME type of the file */
    private String contentType;

    /** Size of the file in bytes */
    private long fileSize;

    /**
     * The producing step's structured report (e.g. a preflight verdict or an archive chain id),
     * when the pipeline's last reporting step emitted one. Null for tools that report nothing.
     */
    private Map<String, Object> report;
}
