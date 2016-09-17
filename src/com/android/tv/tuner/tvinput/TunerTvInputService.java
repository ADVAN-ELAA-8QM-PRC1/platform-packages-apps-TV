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

package com.android.tv.tuner.tvinput;

import android.content.ComponentName;
import android.content.Context;
import android.media.tv.TvContract;
import android.media.tv.TvInputService;
import android.util.Log;

import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.audio.AudioCapabilitiesReceiver;
import com.android.tv.TvApplication;
import com.android.tv.tuner.exoplayer.buffer.BufferManager;
import com.android.tv.tuner.exoplayer.buffer.TrickplayStorageManager;
import com.android.tv.tuner.util.SystemPropertiesProxy;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * {@link TunerTvInputService} serves TV channels coming from a tuner device.
 */
public class TunerTvInputService extends TvInputService
        implements AudioCapabilitiesReceiver.Listener{
    private static final String TAG = "TunerTvInputService";
    private static final boolean DEBUG = false;


    private static final String MAX_BUFFER_SIZE_KEY = "tv.tuner.buffersize_mbytes";
    private static final int MAX_BUFFER_SIZE_DEF = 2 * 1024;  // 2GB
    private static final int MIN_BUFFER_SIZE_DEF = 256;  // 256MB

    // WeakContainer for {@link TvInputSessionImpl}
    private final Set<TunerSession> mTunerSessions = Collections.newSetFromMap(new WeakHashMap<>());
    private ChannelDataManager mChannelDataManager;
    private AudioCapabilitiesReceiver mAudioCapabilitiesReceiver;
    private AudioCapabilities mAudioCapabilities;
    private BufferManager mBufferManager;

    @Override
    public void onCreate() {
        TvApplication.setCurrentRunningProcess(this, false);
        super.onCreate();
        if (DEBUG) Log.d(TAG, "onCreate");
        mChannelDataManager = new ChannelDataManager(getApplicationContext());
        mAudioCapabilitiesReceiver = new AudioCapabilitiesReceiver(getApplicationContext(), this);
        mAudioCapabilitiesReceiver.register();
        mBufferManager = createBufferManager();
        if (mBufferManager == null) {
            Log.i(TAG, "Trickplay is disabled");
        } else {
            Log.i(TAG, "Trickplay is enabled");
        }
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "onDestroy");
        super.onDestroy();
        mChannelDataManager.release();
        mAudioCapabilitiesReceiver.unregister();
        if (mBufferManager != null) {
            mBufferManager.close();
        }
    }

    @Override
    public RecordingSession onCreateRecordingSession(String inputId) {
        return new TunerRecordingSession(this, inputId, mChannelDataManager);
    }

    @Override
    public Session onCreateSession(String inputId) {
        if (DEBUG) Log.d(TAG, "onCreateSession");
        try {
            final TunerSession session = new TunerSession(
                    this, mChannelDataManager, mBufferManager);
            mTunerSessions.add(session);
            session.setAudioCapabilities(mAudioCapabilities);
            session.setOverlayViewEnabled(true);
            return session;
        } catch (RuntimeException e) {
            // There are no available DVB devices.
            Log.e(TAG, "Creating a session for " + inputId + " failed.", e);
            return null;
        }
    }

    @Override
    public void onAudioCapabilitiesChanged(AudioCapabilities audioCapabilities) {
        mAudioCapabilities = audioCapabilities;
        for (TunerSession session : mTunerSessions) {
            if (!session.isReleased()) {
                session.setAudioCapabilities(audioCapabilities);
            }
        }
    }

    private BufferManager createBufferManager() {
        int maxBufferSizeMb =
                SystemPropertiesProxy.getInt(MAX_BUFFER_SIZE_KEY, MAX_BUFFER_SIZE_DEF);
        if (maxBufferSizeMb >= MIN_BUFFER_SIZE_DEF) {
            return new BufferManager(
                    new TrickplayStorageManager(getApplicationContext(), getCacheDir(),
                            1024L * 1024 * maxBufferSizeMb));
        }
        return null;
    }

    public static String getInputId(Context context) {
        return TvContract.buildInputId(new ComponentName(context, TunerTvInputService.class));
    }
}