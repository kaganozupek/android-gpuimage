/*
 * Copyright (C) 2012 CyberAgent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.co.cyberagent.android.gpuimage.filter;

import android.annotation.SuppressLint;
import android.opengl.GLES20;
import jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;

import static jp.co.cyberagent.android.gpuimage.GPUImageRenderer.CUBE;
import static jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.TEXTURE_NO_ROTATION;

/**
 * Resembles a filter that consists of multiple filters applied after each other.
 *
 * Thread-safe via synchronization on {@link @mFilters}, this class's source of truth.
 */
public class GPUImageFilterGroup extends GPUImageFilter {

    /**
     * Ordered collection of {@link GPUImageFilter} instances managed by this instance
     */
    protected List<GPUImageFilter> mFilters;

    /**
     * Ordered collection of all root {@link GPUImageFilter} instances managed by this instance
     * (i.e. including root nodes from nested {@link GPUImageFilterGroup} instances)
     */
    protected List<GPUImageFilter> mMergedFilters;

    /**
     * Internal flag to indicate we need to rebuild mFrameBuffers and mFrameBufferTextures
     * on the next draw (e.g. because mFilters membership changed).
     */
    private AtomicBoolean mFrameNeedsRefresh = new AtomicBoolean(false);

    private int[] mFrameBuffers;
    private int[] mFrameBufferTextures;

    private final FloatBuffer mGLCubeBuffer;
    private final FloatBuffer mGLTextureBuffer;
    private final FloatBuffer mGLTextureFlipBuffer;

    /**
     * Instantiates a new GPUImageFilterGroup with no filters.
     */
    public GPUImageFilterGroup() {
        this(null);
    }

    /**
     * Instantiates a new GPUImageFilterGroup with the given filters.
     *
     * @param filters the filters which represent this filter
     */
    public GPUImageFilterGroup(List<GPUImageFilter> filters) {
        mFilters = filters;
        if (mFilters == null) {
            mFilters = new ArrayList<>();
        } else {
            updateMergedFilters();
        }

        mGLCubeBuffer = ByteBuffer.allocateDirect(CUBE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mGLCubeBuffer.put(CUBE).position(0);

        mGLTextureBuffer = ByteBuffer.allocateDirect(TEXTURE_NO_ROTATION.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mGLTextureBuffer.put(TEXTURE_NO_ROTATION).position(0);

        float[] flipTexture = TextureRotationUtil.getRotation(Rotation.NORMAL, false, true);
        mGLTextureFlipBuffer = ByteBuffer.allocateDirect(flipTexture.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mGLTextureFlipBuffer.put(flipTexture).position(0);
    }

    public void addFilter(GPUImageFilter aFilter) {
        if (aFilter == null) {
            return;
        }
        synchronized (mFilters) {
            mFilters.add(aFilter);
            updateMergedFilters();
        }
    }

    /*
     * (non-Javadoc)
     * @see jp.co.cyberagent.android.gpuimage.GPUImageFilter#onInit()
     */
    @Override
    public void onInit() {
        super.onInit();
        synchronized (mFilters) {
            for (GPUImageFilter filter : mFilters) {
                filter.init();
            }
        }
    }

    /*
     * (non-Javadoc)
     * @see jp.co.cyberagent.android.gpuimage.GPUImageFilter#onDestroy()
     */
    @Override
    public void onDestroy() {
        synchronized (mFilters) {
            destroyFramebuffers();
            for (GPUImageFilter filter : mFilters) {
                filter.destroy();
            }
        }
        super.onDestroy();
    }

    private void destroyFramebuffers() {
        synchronized (mFilters) {
            if (mFrameBufferTextures != null) {
                GLES20.glDeleteTextures(mFrameBufferTextures.length, mFrameBufferTextures, 0);
                mFrameBufferTextures = null;
            }
            if (mFrameBuffers != null) {
                GLES20.glDeleteFramebuffers(mFrameBuffers.length, mFrameBuffers, 0);
                mFrameBuffers = null;
            }
        }
    }

    /*
     * (non-Javadoc)
     * @see
     * jp.co.cyberagent.android.gpuimage.GPUImageFilter#onOutputSizeChanged(int,
     * int)
     */
    @Override
    public void onOutputSizeChanged(final int width, final int height) {
        super.onOutputSizeChanged(width, height);
        synchronized (mFilters) {
            for (int i = 0; i < mFilters.size(); i++) {
                mFilters.get(i).onOutputSizeChanged(width, height);
            }
            // Trigger texture updates on the next draw to guarantee thread owns the OpenGL context
            mFrameNeedsRefresh.getAndSet(true);
        }
    }

    /*
     * (non-Javadoc)
     * @see jp.co.cyberagent.android.gpuimage.GPUImageFilter#onDraw(int,
     * java.nio.FloatBuffer, java.nio.FloatBuffer)
     */
    @SuppressLint("WrongCall")    
    @Override
    public void onDraw(final int textureId, final FloatBuffer cubeBuffer,
                       final FloatBuffer textureBuffer) {
        if (!isInitialized()){
            return;
        }
        runPendingOnDrawTasks();
        synchronized (mFilters) {
            if (mFrameNeedsRefresh.getAndSet(false)) {
                if (mFrameBuffers != null) {
                    destroyFramebuffers();
                }
                if (mMergedFilters != null && mMergedFilters.size() > 0) {
                    int size = mMergedFilters.size();
                    mFrameBuffers = new int[size - 1];
                    mFrameBufferTextures = new int[size - 1];
                    for (GPUImageFilter gpuImageFilter : mMergedFilters) {
                        for (int i = 0; i < size - 1; i++) {
                            GLES20.glGenFramebuffers(1, mFrameBuffers, i);
                            GLES20.glGenTextures(1, mFrameBufferTextures, i);
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mFrameBufferTextures[i]);
                            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                                    gpuImageFilter.getOutputWidth(),
                                    gpuImageFilter.getOutputHeight(),
                                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBuffers[i]);
                            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                                    GLES20.GL_TEXTURE_2D, mFrameBufferTextures[i], 0);

                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                        }
                    }
                }
            }
            if (mFrameBuffers == null || mFrameBufferTextures == null) {
                // No textures to draw
                return;
            }
            // Draw the texture for each filter
            if (mMergedFilters != null) {
                Iterator<GPUImageFilter> filterIterator = mMergedFilters.iterator();
                boolean first = true;
                boolean last;
                int previousTexture = textureId;
                int size = mMergedFilters.size();
                int i = 0;
                while (filterIterator.hasNext()) {
                    GPUImageFilter filter = filterIterator.next();
                    last = filterIterator.hasNext() == false;
                    if (!last) {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBuffers[i]);
                        GLES20.glClearColor(0, 0, 0, 0);
                    }

                    if (first) {
                        filter.onDraw(previousTexture, cubeBuffer, textureBuffer);
                        first = false;
                    } else if (last) {
                        filter.onDraw(previousTexture, mGLCubeBuffer, (size % 2 == 0) ? mGLTextureFlipBuffer : mGLTextureBuffer);
                    } else {
                        filter.onDraw(previousTexture, mGLCubeBuffer, mGLTextureBuffer);
                    }

                    if (!last) {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                        previousTexture = mFrameBufferTextures[i];
                    }
                    i++;
                }
            }
        }
     }

    /**
     * Safely get the filter at index.
     *
     * @return null if index is out of bounds
     */
    protected GPUImageFilter getFilter(int index) {
        synchronized (mFilters) {
            if (mFilters == null || index < 0 || index >= mFilters.size()) {
                return null;
            }
            return mFilters.get(index);
        }
    }

    /**
     * @return an unmodifiable copy of mMergedFilters (unmodifiable to preserve thread safety)
     */
    protected List<GPUImageFilter> getMergedFilters() {
        return Collections.unmodifiableList(mMergedFilters);
    }

    private void updateMergedFilters() {
        synchronized (mFilters) {
            if (mFilters == null) {
                return;
            }

            if (mMergedFilters == null) {
                mMergedFilters = new ArrayList<GPUImageFilter>();
            } else {
                mMergedFilters.clear();
            }

            List<GPUImageFilter> filters;
            for (GPUImageFilter filter : mFilters) {
                if (filter instanceof GPUImageFilterGroup) {
                    ((GPUImageFilterGroup) filter).updateMergedFilters();
                    filters = ((GPUImageFilterGroup) filter).getMergedFilters();
                    if (filters == null || filters.isEmpty())
                        continue;
                    mMergedFilters.addAll(filters);
                    continue;
                }
                mMergedFilters.add(filter);
            }
            // Trigger texture updates on the next draw to guarantee thread owns the OpenGL context
            mFrameNeedsRefresh.getAndSet(true);
        }
    }
}
