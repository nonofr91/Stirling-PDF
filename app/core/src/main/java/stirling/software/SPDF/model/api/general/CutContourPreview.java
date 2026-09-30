package stirling.software.SPDF.model.api.general;

import java.util.List;

import lombok.Data;

/** Traced cut paths per page, in the page's unrotated user-space points. */
@Data
public class CutContourPreview {

    private List<Page> pages;

    @Data
    public static class Page {
        /** 1-based page number. */
        private int page;

        /** Extraction mode that produced this page's mask. */
        private String mode;

        /** Rendered space the paths live in: [llx, lly, width, height] in points. */
        private float[] space;

        /** Closed rings as flattened [x0,y0,x1,y1,...] point pairs in user-space points. */
        private List<List<Float>> paths;

        /** Parallel to {@link #paths}: true when the ring is an interior hole. */
        private List<Boolean> holes;
    }
}
