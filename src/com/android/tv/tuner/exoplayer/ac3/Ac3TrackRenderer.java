/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tv.tuner.exoplayer.ac3;

import android.media.MediaCodec;
import android.os.Handler;

import android.util.Log;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecSelector;
import com.google.android.exoplayer.SampleSource;
import com.android.tv.tuner.TunerFlags;

import java.nio.ByteBuffer;

/**
 * MPEG-2 TS audio track renderer.
 * <p>Since the audio output from {@link android.media.MediaExtractor} contains extra samples at
 * the beginning, using original {@link MediaCodecAudioTrackRenderer} as audio renderer causes
 * asynchronous Audio/Video outputs.
 * This class calculates the offset of audio data and adjust the presentation times to avoid the
 * asynchronous Audio/Video problem.
 */
public class Ac3TrackRenderer extends MediaCodecAudioTrackRenderer {
    private final String TAG = "Ac3TrackRenderer";
    private final boolean DEBUG = false;

    private int mZeroPresentationTimeCount;
    private Long mPresentationTimeOffset;
    private boolean mPresentationTimeOffsetNeeded;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private final Ac3EventListener mListener;

    public interface Ac3EventListener extends EventListener {
        /**
         * Invoked when a {@link android.media.PlaybackParams} set to an
         * {@link android.media.AudioTrack} is not valid.
         *
         * @param e The corresponding exception.
         */
        void onAudioTrackSetPlaybackParamsError(IllegalArgumentException e);
    }

    public Ac3TrackRenderer(SampleSource source, MediaCodecSelector mediaCodecSelector,
            Handler eventHandler, EventListener eventListener) {
        super(source, mediaCodecSelector, eventHandler, eventListener);
        mListener = (Ac3EventListener) eventListener;
    }

    @Override
    protected void onDiscontinuity(long positionUs) throws ExoPlaybackException {
        super.onDiscontinuity(positionUs);
        if (TunerFlags.USE_EXTRACTOR_IN_EXOPLAYER) {
            return;
        }
        if (DEBUG) Log.d(TAG, "onDiscontinuity(), positionUs = " + positionUs);
        mZeroPresentationTimeCount = 0;
        mPresentationTimeOffset = null;
        mPresentationTimeOffsetNeeded = false;
    }

    @Override
    protected void onQueuedInputBuffer(long presentationTimeUs, ByteBuffer buffer,
            int bufferSize, boolean sampleEncrypted) {
        if (TunerFlags.USE_EXTRACTOR_IN_EXOPLAYER) {
            return;
        }
        if (DEBUG) Log.d(TAG, "onQueuedInputBuffer(), presentationTimeUs = " + presentationTimeUs);
        if (presentationTimeUs == 0) {
            // A sequence of consecutive zero presentation times indicate
            // the starting of a data stream.
            // Count the number of leading zeros
            mZeroPresentationTimeCount++;
            // Start waiting for the first non-zero presentation time.
            mPresentationTimeOffsetNeeded = true;
        } else if (mPresentationTimeOffset == null && mPresentationTimeOffsetNeeded) {
            // Sets time offset based on the first non-zero presentation timestamp,
            // which is the first timestamp we can trust.
            mPresentationTimeOffset = mZeroPresentationTimeCount
                    * Ac3PassthroughTrackRenderer.AC3_SAMPLE_DURATION_US
                    - presentationTimeUs;
        }

    }

    @Override
    protected boolean processOutputBuffer(long positionUs, long elapsedRealtimeUs, MediaCodec codec,
            ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo, int bufferIndex,
            boolean shouldSkip) throws ExoPlaybackException {
        if (TunerFlags.USE_EXTRACTOR_IN_EXOPLAYER) {
            return super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer,
                    bufferInfo, bufferIndex, shouldSkip);
        }
        if (mPresentationTimeOffset != null) {
            // Adjust the presentation time. We don't modify the given {@code bufferInfo} here since
            // this method can be called multiple times with the same buffer.
            long presentationTimeUs =
                    Math.max(bufferInfo.presentationTimeUs - mPresentationTimeOffset, 0);
            mBufferInfo.set(bufferInfo.offset, bufferInfo.size,
                    presentationTimeUs, bufferInfo.flags);
        } else {
            mBufferInfo.set(bufferInfo.offset, bufferInfo.size,
                    bufferInfo.presentationTimeUs, bufferInfo.flags);
        }
        try {
            return super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer,
                    mBufferInfo, bufferIndex, shouldSkip);
        } catch (IllegalArgumentException e) {
            if (isAudioTrackSetPlaybackParamsError(e)) {
                notifyAudioTrackSetPlaybackParamsError(e);
            }
            return false;
        }
    }

    @Override
    public void handleMessage(int messageType, Object message) throws ExoPlaybackException {
        if (messageType == MSG_SET_PLAYBACK_PARAMS) {
            try {
                super.handleMessage(messageType, message);
            } catch (IllegalArgumentException e) {
                if (isAudioTrackSetPlaybackParamsError(e)) {
                    notifyAudioTrackSetPlaybackParamsError(e);
                }
            }
            return;
        }
        super.handleMessage(messageType, message);
    }

    private void notifyAudioTrackSetPlaybackParamsError(final IllegalArgumentException e) {
        if (eventHandler != null && mListener != null) {
            eventHandler.post(new Runnable()  {
                @Override
                public void run() {
                    mListener.onAudioTrackSetPlaybackParamsError(e);
                }
            });
        }
    }

    static private boolean isAudioTrackSetPlaybackParamsError(IllegalArgumentException e) {
        if (e.getStackTrace() == null || e.getStackTrace().length < 1) {
            return false;
        }
        for (StackTraceElement element : e.getStackTrace()) {
            String elementString = element.toString();
            if (elementString.startsWith("android.media.AudioTrack.setPlaybackParams")) {
                return true;
            }
        }
        return false;
    }
}