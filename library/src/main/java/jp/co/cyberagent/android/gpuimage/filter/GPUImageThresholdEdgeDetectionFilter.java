package jp.co.cyberagent.android.gpuimage.filter;

/**
 * Applies sobel edge detection on the image.
 */
public class GPUImageThresholdEdgeDetectionFilter extends GPUImageFilterGroup {
    public GPUImageThresholdEdgeDetectionFilter() {
        super();
        addFilter(new GPUImageGrayscaleFilter());
        addFilter(new GPUImageSobelThresholdFilter());
    }

    public void setLineSize(final float size) {
        ((GPUImage3x3TextureSamplingFilter) getFilter(1)).setLineSize(size);
    }

    public void setThreshold(final float threshold) {
        ((GPUImageSobelThresholdFilter) getFilter(1)).setThreshold(threshold);
    }
}
