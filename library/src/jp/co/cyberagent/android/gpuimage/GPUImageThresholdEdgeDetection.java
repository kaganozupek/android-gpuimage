package jp.co.cyberagent.android.gpuimage;

/**
 * Applies sobel edge detection on the image.
 */
public class GPUImageThresholdEdgeDetection extends GPUImageFilterGroup {
    public GPUImageThresholdEdgeDetection() {
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
