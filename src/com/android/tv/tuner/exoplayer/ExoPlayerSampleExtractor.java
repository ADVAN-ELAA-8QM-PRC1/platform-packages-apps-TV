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
import android.media.tv.TvContract;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.SystemClock;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.MediaFormatHolder;
import com.google.android.exoplayer.SampleHolder;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.extractor.ExtractorSampleSource;
import com.google.android.exoplayer.upstream.Allocator;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
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
 * A class that extracts samples from a live broadcast stream while storing the sample on the disk.
 * For demux, this class relies on {@link com.google.android.exoplayer.extractor.ts.TsExtractor}.
 */
public class ExoPlayerSampleExtractor implements SampleExtractor {
    private static final String TAG = "ExoPlayerSampleExtractor";

    // Buffer segment size for memory allocator. Copied from demo implementation of ExoPlayer.
    private static final int BUFFER_SEGMENT_SIZE_IN_BYTES = 64 * 1024;
    // Buffer segment count for sample source. Copied from demo implementation of ExoPlayer.
    private static final int BUFFER_SEGMENT_COUNT = 256;

    private static final AtomicLong ID_COUNTER = new AtomicLong(0);

    private final ExtractorThread mExtractorThread;
    private final BufferManager.SampleBuffer mSampleBuffer;
    private final long mId;
    private final List<MediaFormat> mTrackFormats = new ArrayList<>();

    private final SampleSource.SampleSourceReader mSampleSourceReader;

    private boolean mReleased;
    private boolean mOnCompletionCalled;
    private HashMap<Integer, Long> mLastExtractedPositionUsMap = new HashMap<>();
    private OnCompletionListener mOnCompletionListener;
    private Handler mOnCompletionListenerHandler;

    public ExoPlayerSampleExtractor(DataSource source, BufferManager bufferManager,
            PlaybackBufferListener bufferListener, boolean isRecording) {
        mId = ID_COUNTER.incrementAndGet();
        Allocator allocator = new DefaultAllocator(BUFFER_SEGMENT_SIZE_IN_BYTES);
        mSampleSourceReader = new ExtractorSampleSource(TvContract.Programs.CONTENT_URI, source,
                allocator, BUFFER_SEGMENT_COUNT * BUFFER_SEGMENT_SIZE_IN_BYTES, null, null, 0);

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
        private static final int FETCH_SAMPLE_INTERVAL_MS = 50;
        private volatile boolean mQuitRequested = false;
        private long mCurrentPosition;

        public ExtractorThread() {
            super("ExtractorThread");
        }

        @Override
        public void run() {
            SampleHolder sample = new SampleHolder(SampleHolder.BUFFER_REPLACEMENT_MODE_NORMAL);
            ConditionVariable conditionVariable = new ConditionVariable();
            int trackCount = mSampleSourceReader.getTrackCount();
            while (!mQuitRequested) {
                boolean didSomething = false;
                for (int i = 0; i < trackCount; ++i) {
                    if(SampleSource.NOTHING_READ != fetchSample(i, sample, conditionVariable)) {
                        didSomething = true;
                    }
                }
                if (!didSomething) {
                    try {
                        Thread.sleep(FETCH_SAMPLE_INTERVAL_MS);
                    } catch (InterruptedException e) {
                    }
                }
            }
            cleanUp();
        }

        private int fetchSample(int track, SampleHolder sample,
                ConditionVariable conditionVariable) {
            mSampleSourceReader.continueBuffering(track, mCurrentPosition);

            MediaFormatHolder formatHolder = new MediaFormatHolder();
            sample.clearData();
            int ret = mSampleSourceReader.readData(track, mCurrentPosition, formatHolder, sample);
            if (ret == SampleSource.SAMPLE_READ) {
                if (mCurrentPosition < sample.timeUs) {
                    mCurrentPosition = sample.timeUs;
                }
                try {
                    Long lastExtractedPositionUs = mLastExtractedPositionUsMap.get(track);
                    if (lastExtractedPositionUs == null) {
                        mLastExtractedPositionUsMap.put(track, sample.timeUs);
                    } else {
                        mLastExtractedPositionUsMap.put(track,
                                Math.max(lastExtractedPositionUs, sample.timeUs));
                    }
                    queueSample(track, sample, conditionVariable);
                } catch (IOException e) {
                    mLastExtractedPositionUsMap.clear();
                    mQuitRequested = true;
                    mSampleBuffer.setEos();
                }
            } else if (ret == SampleSource.END_OF_STREAM) {
                mQuitRequested = true;
                mSampleBuffer.setEos();
            }
            // TODO: Handle SampleSource.FORMAT_READ for dynamic resolution change. b/28169263
            return ret;
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
            if(!mSampleSourceReader.prepare(0)) {
                return false;
            }
            int trackCount = mSampleSourceReader.getTrackCount();
            mTrackFormats.clear();
            for (int i = 0; i < trackCount; i++) {
                mTrackFormats.add(mSampleSourceReader.getFormat(i));
                mSampleSourceReader.enable(i, 0);
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
    }
}
