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
import android.os.Handler;

import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.MediaFormatHolder;
import com.google.android.exoplayer.MediaFormatUtil;
import com.google.android.exoplayer.SampleHolder;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.util.MimeTypes;
import com.android.tv.tuner.TunerFlags;
import com.android.tv.tuner.exoplayer.buffer.BufferManager;
import com.android.tv.tuner.exoplayer.buffer.SamplePool;
import com.android.tv.tuner.tvinput.PlaybackBufferListener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Extracts samples from {@link MediaDataSource} for MPEG-TS streams.
 */
public final class MpegTsSampleExtractor implements SampleExtractor {
    public static final String MIMETYPE_TEXT_CEA_708 = "text/cea-708";

    private static final int CC_BUFFER_SIZE_IN_BYTES = 9600 / 8;

    private final SampleExtractor mSampleExtractor;
    private final List<MediaFormat> mTrackFormats = new ArrayList<>();
    private final List<Boolean> mReachedEos = new ArrayList<>();
    private int mVideoTrackIndex;
    private final SamplePool mCcSamplePool = new SamplePool();
    private final List<SampleHolder> mPendingCcSamples = new LinkedList<>();

    private int mCea708TextTrackIndex;
    private boolean mCea708TextTrackSelected;

    private CcParser mCcParser;

    private void init() {
        mVideoTrackIndex = -1;
        mCea708TextTrackIndex = -1;
        mCea708TextTrackSelected = false;
    }

    /**
     * Creates MpegTsSampleExtractor for {@link MediaDataSource}.
     *
     * @param source the {@link MediaDataSource} to extract from
     * @param bufferManager the manager for reading & writing samples backed by physical storage
     * @param bufferListener the {@link PlaybackBufferListener}
     *                      to notify buffer storage status change
     */
    public MpegTsSampleExtractor(MediaDataSource source,
            BufferManager bufferManager, PlaybackBufferListener bufferListener) {
        if (TunerFlags.USE_EXTRACTOR_IN_EXOPLAYER) {
            mSampleExtractor = new ExoPlayerSampleExtractor(new DataSourceAdapter(source),
                    bufferManager, bufferListener, false);
        } else {
            mSampleExtractor = new FrameworkSampleExtractor(source, bufferManager, bufferListener,
                    false);
        }
        init();
    }

    /**
     * Creates MpegTsSampleExtractor for a recorded program.
     *
     * @param bufferManager the samples provider which is stored in physical storage
     * @param bufferListener the {@link PlaybackBufferListener}
     *                      to notify buffer storage status change
     */
    public MpegTsSampleExtractor(BufferManager bufferManager,
            PlaybackBufferListener bufferListener) {
        mSampleExtractor = new FileSampleExtractor(bufferManager, bufferListener);
        init();
    }

    @Override
    public boolean prepare() throws IOException {
        if(!mSampleExtractor.prepare()) {
            return false;
        }
        List<MediaFormat> formats = mSampleExtractor.getTrackFormats();
        int trackCount = formats.size();
        mTrackFormats.clear();
        mReachedEos.clear();

        for (int i = 0; i < trackCount; ++i) {
            mTrackFormats.add(formats.get(i));
            mReachedEos.add(false);
            String mime = formats.get(i).mimeType;
            if (MimeTypes.isVideo(mime) && mVideoTrackIndex == -1) {
                mVideoTrackIndex = i;
                if (android.media.MediaFormat.MIMETYPE_VIDEO_MPEG2.equals(mime)) {
                    mCcParser = new Mpeg2CcParser();
                } else if (android.media.MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)) {
                    mCcParser = new H264CcParser();
                }
            }
        }

        if (mVideoTrackIndex != -1) {
            mCea708TextTrackIndex = trackCount;
        }
        if (mCea708TextTrackIndex >= 0) {
            mTrackFormats.add(MediaFormatUtil.createTextMediaFormat(MIMETYPE_TEXT_CEA_708,
                    mTrackFormats.get(0).durationUs));
        }
        return true;
    }

    @Override
    public List<MediaFormat> getTrackFormats() {
        return mTrackFormats;
    }

    @Override
    public void selectTrack(int index) {
        if (index == mCea708TextTrackIndex) {
            mCea708TextTrackSelected = true;
            return;
        }
        mSampleExtractor.selectTrack(index);
    }

    @Override
    public void deselectTrack(int index) {
        if (index == mCea708TextTrackIndex) {
            mCea708TextTrackSelected = false;
            return;
        }
        mSampleExtractor.deselectTrack(index);
    }

    @Override
    public long getBufferedPositionUs() {
        return mSampleExtractor.getBufferedPositionUs();
    }

    @Override
    public void seekTo(long positionUs) {
        mSampleExtractor.seekTo(positionUs);
        for (SampleHolder holder : mPendingCcSamples) {
            mCcSamplePool.releaseSample(holder);
        }
        mPendingCcSamples.clear();
    }

    @Override
    public void getTrackMediaFormat(int track, MediaFormatHolder outMediaFormatHolder) {
        if (track != mCea708TextTrackIndex) {
            mSampleExtractor.getTrackMediaFormat(track, outMediaFormatHolder);
        }
    }

    @Override
    public int readSample(int track, SampleHolder sampleHolder) {
        if (track == mCea708TextTrackIndex) {
            if (mCea708TextTrackSelected && !mPendingCcSamples.isEmpty()) {
                SampleHolder holder = mPendingCcSamples.remove(0);
                holder.data.flip();
                sampleHolder.timeUs = holder.timeUs;
                sampleHolder.data.put(holder.data);
                mCcSamplePool.releaseSample(holder);
                return SampleSource.SAMPLE_READ;
            } else {
                return mVideoTrackIndex < 0 || mReachedEos.get(mVideoTrackIndex)
                        ? SampleSource.END_OF_STREAM : SampleSource.NOTHING_READ;
            }
        }

        int result = mSampleExtractor.readSample(track, sampleHolder);
        switch (result) {
            case SampleSource.END_OF_STREAM: {
                mReachedEos.set(track, true);
                break;
            }
            case SampleSource.SAMPLE_READ: {
                if (mCea708TextTrackSelected && track == mVideoTrackIndex
                        && sampleHolder.data != null) {
                    mCcParser.mayParseClosedCaption(sampleHolder.data, sampleHolder.timeUs);
                }
                break;
            }
        }
        return result;
    }

    @Override
    public void release() {
        mSampleExtractor.release();
        mVideoTrackIndex = -1;
        mCea708TextTrackIndex = -1;
        mCea708TextTrackSelected = false;
    }

    @Override
    public boolean continueBuffering(long positionUs) {
        return mSampleExtractor.continueBuffering(positionUs);
    }

    @Override
    public void setOnCompletionListener(OnCompletionListener listener, Handler handler) { }

    private abstract class CcParser {
        abstract void mayParseClosedCaption(ByteBuffer buffer, long presentationTimeUs);

        protected void parseClosedCaption(ByteBuffer buffer, int offset, long presentationTimeUs) {
            // For the details of user_data_type_structure, see ATSC A/53 Part 4 - Table 6.9.
            int pos = offset;
            if (pos + 2 >= buffer.position()) {
                return;
            }
            boolean processCcDataFlag = (buffer.get(pos) & 64) != 0;
            int ccCount = buffer.get(pos) & 0x1f;
            pos += 2;
            if (!processCcDataFlag || pos + 3 * ccCount >= buffer.position() || ccCount == 0) {
                return;
            }
            SampleHolder holder = mCcSamplePool.acquireSample(CC_BUFFER_SIZE_IN_BYTES);
            for (int i = 0; i < 3 * ccCount; i++) {
                holder.data.put(buffer.get(pos + i));
            }
            holder.timeUs = presentationTimeUs;
            mPendingCcSamples.add(holder);
        }
    }

    private class Mpeg2CcParser extends CcParser {
        @Override
        public void mayParseClosedCaption(ByteBuffer buffer, long presentationTimeUs) {
            int pos = 0;
            while (pos + 9 < buffer.position()) {
                // Find the start prefix code of private user data.
                if (buffer.get(pos) == 0
                        && buffer.get(pos + 1) == 0
                        && buffer.get(pos + 2) == 1
                        && (buffer.get(pos + 3) & 0xff) == 0xb2) {
                    // ATSC closed caption data embedded in MPEG2VIDEO stream has 'GA94' user
                    // identifier and user data type code 3.
                    if (buffer.get(pos + 4) == 'G'
                            && buffer.get(pos + 5) == 'A'
                            && buffer.get(pos + 6) == '9'
                            && buffer.get(pos + 7) == '4'
                            && buffer.get(pos + 8) == 3) {
                        parseClosedCaption(buffer, pos + 9, presentationTimeUs);
                    }
                    pos += 9;
                } else {
                    ++pos;
                }
            }
        }
    }

    private class H264CcParser extends CcParser {
        @Override
        public void mayParseClosedCaption(ByteBuffer buffer, long presentationTimeUs) {
            int pos = 0;
            while (pos + 7 < buffer.position()) {
                // Find the start prefix code of a NAL Unit.
                if (buffer.get(pos) == 0
                        && buffer.get(pos + 1) == 0
                        && buffer.get(pos + 2) == 1) {
                    int nalType = buffer.get(pos + 3) & 0x1f;
                    int payloadType = buffer.get(pos + 4) & 0xff;

                    // ATSC closed caption data embedded in H264 private user data has NAL type 6,
                    // payload type 4, and 'GA94' user identifier for ATSC.
                    if (nalType == 6 && payloadType == 4 && buffer.get(pos + 9) == 'G'
                            && buffer.get(pos + 10) == 'A'
                            && buffer.get(pos + 11) == '9'
                            && buffer.get(pos + 12) == '4') {
                        parseClosedCaption(buffer, pos + 14, presentationTimeUs);
                    }
                    pos += 7;
                } else {
                    ++pos;
                }
            }
        }
    }
}
