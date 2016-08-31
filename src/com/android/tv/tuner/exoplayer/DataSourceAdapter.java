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
package com.android.tv.tuner.exoplayer;


import android.media.MediaDataSource;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DataSpec;

import java.io.IOException;

/**
 * A DataSource adapter implementation by using {@link MediaDataSource}.
 */
public class DataSourceAdapter implements DataSource {
    private MediaDataSource mMediaDataSource;
    private long mReadPosition;

    public DataSourceAdapter(MediaDataSource mediaDataSource) {
        mMediaDataSource = mediaDataSource;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        return C.LENGTH_UNBOUNDED;
    }

    @Override
    public void close() throws IOException {
        mMediaDataSource.close();
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        int ret = mMediaDataSource.readAt(mReadPosition, buffer, offset, readLength);
        if (ret > 0) {
            mReadPosition += ret;
            return ret;
        }
        return C.RESULT_END_OF_INPUT;
    }
}
