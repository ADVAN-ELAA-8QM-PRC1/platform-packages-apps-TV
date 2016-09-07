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
 * limitations under the License
 */

package com.android.tv.dvr;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.tv.TvInputManager;

import com.android.tv.common.SharedPreferencesUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A class to manage DVR watched state.
 * It will remember and provides previous watched position of DVR playback.
 */
public class DvrWatchedPositionManager {
    private final static String TAG = "DvrWatchedPositionManager";
    private final boolean DEBUG = false;

    private SharedPreferences mWatchedPositions;
    private final Context mContext;
    private final Map<Long, Set> mListeners = new HashMap<>();

    public DvrWatchedPositionManager(Context context) {
        mContext = context.getApplicationContext();
        mWatchedPositions = mContext.getSharedPreferences(SharedPreferencesUtils
                .SHARED_PREF_DVR_WATCHED_POSITION, Context.MODE_PRIVATE);
    }

    /**
     * Sets the watched position of the give program.
     */
    public void setWatchedPosition(long recordedProgramId, long positionMs) {
        mWatchedPositions.edit().putLong(Long.toString(recordedProgramId), positionMs).apply();
        notifyWatchedPositionChanged(recordedProgramId, positionMs);
    }

    /**
     * Gets the watched position of the give program.
     */
    public long getWatchedPosition(long recordedProgramId) {
        return mWatchedPositions.getLong(Long.toString(recordedProgramId),
                TvInputManager.TIME_SHIFT_INVALID_TIME);
    }

    /**
     * Adds {@link WatchedPositionChangedListener}.
     */
    public void addListener(WatchedPositionChangedListener listener, long recordedProgramId) {
        if (recordedProgramId == RecordedProgram.ID_NOT_SET) {
            return;
        }
        Set<WatchedPositionChangedListener> listenerSet = mListeners.get(recordedProgramId);
        if (listenerSet == null) {
            listenerSet = new CopyOnWriteArraySet<>();
            mListeners.put(recordedProgramId, listenerSet);
        }
        listenerSet.add(listener);
    }

    /**
     * Removes {@link WatchedPositionChangedListener}.
     */
    public void removeListener(WatchedPositionChangedListener listener) {
        for (long recordedProgramId : new ArrayList<>(mListeners.keySet())) {
            removeListener(listener, recordedProgramId);
        }
    }

    /**
     * Removes {@link WatchedPositionChangedListener}.
     */
    public void removeListener(WatchedPositionChangedListener listener, long recordedProgramId) {
        Set<WatchedPositionChangedListener> listenerSet = mListeners.get(recordedProgramId);
        if (listenerSet == null) {
            return;
        }
        listenerSet.remove(listener);
        if (listenerSet.isEmpty()) {
            mListeners.remove(recordedProgramId);
        }
    }

    private void notifyWatchedPositionChanged(long recordedProgramId, long positionMs) {
        Set<WatchedPositionChangedListener> listenerSet = mListeners.get(recordedProgramId);
        if (listenerSet == null) {
            return;
        }
        for (WatchedPositionChangedListener listener : listenerSet) {
            listener.onWatchedPositionChanged(recordedProgramId, positionMs);
        }
    }

    public interface WatchedPositionChangedListener {
        /**
         * Called when the watched position of some program is changed.
         */
        void onWatchedPositionChanged(long recordedProgramId, long positionMs);
    }
}
