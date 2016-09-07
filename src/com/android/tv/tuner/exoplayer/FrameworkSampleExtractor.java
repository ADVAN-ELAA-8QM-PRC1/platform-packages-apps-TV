/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.tv.tuner.exoplayer;

import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.os.Handler;
import android.os.ConditionVariable;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.MediaFormatHolder;
import com.google.android.exoplayer.MediaFormatUtil;
import com.google.android.exoplayer.SampleHolder;
import com.android.tv.tuner.exoplayer.buffer.BufferManager;
import com.android.tv.tuner.exoplayer.buffer.RecordingSampleBuffer;
import com.android.tv.tuner.exoplayer.buffer.SimpleSampleBuffer;
import com.android.tv.tuner.tvinput.PlaybackBufferListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A class that plays and records a live stream from tuner using a {@link MediaExtractor}
 * and the private ExtractorThread class.
 */
public class FrameworkSampleExtractor implements SampleExtractor {
    private static final String TAG = "FrameworkSampleExtractor";

    // Maximum bandwidth of 1080p channel is about 2.2MB/s. 2MB for a sample will suffice.
    private static final int SAMPLE_BUFFER_SIZE = 1024 * 1024 * 2;
    private static final AtomicLong ID_COUNTER = new AtomicLong(0);

    private final MediaDataSource mDataSource;
    private final MediaExtractor mMediaExtractor;
    private final ExtractorThread mExtractorThread;
    private final BufferManager.SampleBuffer mSampleBuffer;
    private final long mId;
    private final List<MediaFormat> mTrackFormats = new ArrayList<>();

    private boolean mReleased;
    private boolean mOnCompletionCalled;
    private HashMap<Integer, Long> mLastExtractedPositionUsMap = new HashMap<>();
    private OnCompletionListener mOnCompletionListener;
    private Handler mOnCompletionListenerHandler;

    public FrameworkSampleExtractor(MediaDataSource source, BufferManager bufferManager,
            PlaybackBufferListener bufferListener, boolean isRecording) {
        mId = ID_COUNTER.incrementAndGet();
        mDataSource = source;
        mMediaExtractor = new MediaExtractor();
        mExtractorThread = new ExtractorThread();
        if (isRecording) {
            mSampleBuffer = new RecordingSampleBuffer(bufferManager, bufferListener, false,
                    RecordingSampleBuffer.BUFFER_REASON_RECORDING);
        } else {
            if (bufferManager == null || bufferManager.isDisabled()) {
                mSampleBuffer = new SimpleSampleBuffer(bufferListener);
            } else {
                mSampleBuffer = new RecordingSampleBuffer(bufferManager, bufferListener, true,
                        RecordingSampleBuffer.BUFFER_REASON_LIVE_PLAYBACK);
            }
        }
    }

    @Override
    public void setOnCompletionListener(OnCompletionListener listener, Handler handler) {
        mOnCompletionListener = listener;
        mOnCompletionListenerHandler = handler;
    }

    private class ExtractorThread extends Thread {
        private volatile boolean mQuitRequested = false;

        public ExtractorThread() {
            super("ExtractorThread");
        }

        @Override
        public void run() {
            SampleHolder sample = new SampleHolder(SampleHolder.BUFFER_REPLACEMENT_MODE_NORMAL);
            sample.ensureSpaceForWrite(SAMPLE_BUFFER_SIZE);
            ConditionVariable conditionVariable = new ConditionVariable();
            while (!mQuitRequested) {
                fetchSample(sample, conditionVariable);
            }
            cleanUp();
        }

        private void fetchSample(SampleHolder sample, ConditionVariable conditionVariable) {
            int index = mMediaExtractor.getSampleTrackIndex();
            if (index < 0) {
                Log.i(TAG, "EoS");
                mQuitRequested = true;
                mSampleBuffer.setEos();
                return;
            }
            sample.data.clear();
            sample.size = mMediaExtractor.readSampleData(sample.data, 0);
            if (sample.size < 0 || sample.size > SAMPLE_BUFFER_SIZE) {
                // Should not happen
                Log.e(TAG, "Invalid sample size: " + sample.size);
                mMediaExtractor.advance();
                return;
            }
            sample.data.position(sample.size);
            sample.timeUs = mMediaExtractor.getSampleTime();
            sample.flags = mMediaExtractor.getSampleFlags();

            mMediaExtractor.advance();
            try {
                Long lastExtractedPositionUs = mLastExtractedPositionUsMap.get(index);
                if (lastExtractedPositionUs == null) {
                    mLastExtractedPositionUsMap.put(index, sample.timeUs);
                } else {
                    mLastExtractedPositionUsMap.put(index,
                            Math.max(lastExtractedPositionUs, sample.timeUs));
                }
                queueSample(index, sample, conditionVariable);
            } catch (IOException e) {
                mLastExtractedPositionUsMap.clear();
                mQuitRequested = true;
                mSampleBuffer.setEos();
            }
        }

        public void quit() {
            mQuitRequested = true;
        }
    }

    private void queueSample(int index, SampleHolder sample, ConditionVariable conditionVariable)
            throws IOException {
        long writeStartTimeNs = SystemClock.elapsedRealtimeNanos();
        mSampleBuffer.writeSample(index, sample, conditionVariable);

        // Checks whether the storage has enough bandwidth for recording samples.
        if (mSampleBuffer.isWriteSpeedSlow(sample.size,
                SystemClock.elapsedRealtimeNanos() - writeStartTimeNs)) {
            mSampleBuffer.handleWriteSpeedSlow();
        }
    }

    @Override
    public boolean prepare() throws IOException {
        synchronized (this) {
            mMediaExtractor.setDataSource(mDataSource);
            int trackCount = mMediaExtractor.getTrackCount();
            mTrackFormats.clear();
            for (int i = 0; i < trackCount; i++) {
                mTrackFormats.add(MediaFormatUtil.createMediaFormat(
                        mMediaExtractor.getTrackFormat(i)));
                mMediaExtractor.selectTrack(i);
            }
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < trackCount; i++) {
                ids.add(String.format(Locale.ENGLISH, "%s_%x", Long.toHexString(mId), i));
            }
            mSampleBuffer.init(ids, mTrackFormats);
        }
        mExtractorThread.start();
        return true;
    }

    @Override
    public synchronized List<MediaFormat> getTrackFormats() {
        return mTrackFormats;
    }

    @Override
    public void getTrackMediaFormat(int track, MediaFormatHolder outMediaFormatHolder) {
        outMediaFormatHolder.format = mTrackFormats.get(track);
        outMediaFormatHolder.drmInitData = null;
    }

    @Override
    public void selectTrack(int index) {
        mSampleBuffer.selectTrack(index);
    }

    @Override
    public void deselectTrack(int index) {
        mSampleBuffer.deselectTrack(index);
    }

    @Override
    public long getBufferedPositionUs() {
        return mSampleBuffer.getBufferedPositionUs();
    }

    @Override
    public boolean continueBuffering(long positionUs)  {
        return mSampleBuffer.continueBuffering(positionUs);
    }

    @Override
    public void seekTo(long positionUs) {
        mSampleBuffer.seekTo(positionUs);
    }

    @Override
    public int readSample(int track, SampleHolder sampleHolder) {
        return mSampleBuffer.readSample(track, sampleHolder);
    }

    @Override
    public void release() {
        synchronized (this) {
            mReleased = true;
        }
        if (mExtractorThread.isAlive()) {
            mExtractorThread.quit();

            // We don't join here to prevent hang --- MediaExtractor is released at the thread.
        } else {
            cleanUp();
        }
    }

    private void onCompletion(final boolean result, long lastExtractedPositionUs) {
        final OnCompletionListener listener = mOnCompletionListener;
        if (mOnCompletionListenerHandler != null && mOnCompletionListener != null) {
            mOnCompletionListenerHandler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onCompletion(result, lastExtractedPositionUs);
                }
            });
        }
        mOnCompletionCalled = true;
    }

    private long getLastExtractedPositionUs() {
        long lastExtractedPositionUs = Long.MAX_VALUE;
        for (long value : mLastExtractedPositionUsMap.values()) {
            lastExtractedPositionUs = Math.min(lastExtractedPositionUs, value);
        }
        if (lastExtractedPositionUs == Long.MAX_VALUE) {
            lastExtractedPositionUs = C.UNKNOWN_TIME_US;
        }
        return lastExtractedPositionUs;
    }

    private synchronized void cleanUp() {
        if (!mReleased) {
            if (!mOnCompletionCalled) {
                onCompletion(false, getLastExtractedPositionUs());
            }
            return;
        }
        boolean result = true;
        try {
            mSampleBuffer.release();
        } catch (IOException e) {
            result = false;
        }
        if (!mOnCompletionCalled) {
            onCompletion(result, getLastExtractedPositionUs());
        }
        setOnCompletionListener(null, null);
        mMediaExtractor.release();
    }
}
