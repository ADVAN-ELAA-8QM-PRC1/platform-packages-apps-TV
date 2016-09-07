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
package com.android.tv.util;

import android.os.Environment;
import android.content.Context;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import com.android.tv.common.feature.CommonFeatures;

import java.io.File;

/**
 * A utility class for storage usage of DVR recording.
 */
public class DvrTunerStorageUtils {
    // STOPSHIP: turn off ALLOW_REMOVABLE_STORAGE. b/30768857
    private static final boolean ALLOW_REMOVABLE_STORAGE = true;

    private static final long MIN_STORAGE_SIZE_FOR_DVR_IN_BYTES = 50 * 1024 * 1024 * 1024L;
    private static final long MIN_FREE_STORAGE_SIZE_FOR_DVR_IN_BYTES = 10 * 1024 * 1024 * 1024L;
    private static final String RECORDING_DATA_SUB_PATH = "/recording/";
    // Since {@link StorageVolume#getUuid} will return null for internal storage and {@code null}
    // should be used for missing storage status, we need the internal storage specifier.
    private static final String INTERNAL_STORAGE_UUID = "internal_storage_uuid";

    /**
     * Returns the path to DVR recording data directory.
     * @param context {@link Context}
     * @param recordingId unique {@link String} specifier for each recording
     * @return {@link File}
     */
    public static File getRecordingDataDirectory(Context context, String recordingId) {
        File recordingDataRootDir = getRootDirectory(context);
        if (recordingDataRootDir == null) {
            return null;
        }
        return new File(recordingDataRootDir + RECORDING_DATA_SUB_PATH + recordingId);
    }

    /**
     * Returns the unique identifier for the storage which will be used to store recordings.
     * @param context {@link Context}
     * @return {@link String} of the unique identifier when storage exists, {@code null} otherwise
     */
    public static String getRecordingStorageUuid(Context context) {
        File recordingDataRootDir = getRootDirectory(context);
        StorageManager manager = (StorageManager) context.getSystemService(context.STORAGE_SERVICE);
        StorageVolume volume = manager.getStorageVolume(recordingDataRootDir);
        if (volume == null) {
            return null;
        }
        if (!Environment.MEDIA_MOUNTED.equals(volume.getState())) {
            return null;
        }
        String uuid = volume.getUuid();
        return uuid == null ? INTERNAL_STORAGE_UUID : uuid;
    }

    /**
     * Returns whether the storage has sufficient storage.
     * @param context {@link Context}
     * @return {@code true} when there is sufficient storage, {@code false} otherwise
     */
    public static boolean isStorageSufficient(Context context) {
        File recordingDataRootDir = getRootDirectory(context);
        if (recordingDataRootDir == null || !recordingDataRootDir.isDirectory()) {
            return false;
        }
        if (CommonFeatures.FORCE_RECORDING_UNTIL_NO_SPACE.isEnabled(context)) {
            return true;
        }
        StatFs statFs = new StatFs(recordingDataRootDir.toString());
        return statFs.getTotalBytes() >= MIN_STORAGE_SIZE_FOR_DVR_IN_BYTES
                && statFs.getAvailableBytes() >= MIN_FREE_STORAGE_SIZE_FOR_DVR_IN_BYTES;
    }

    private static File getRootDirectory(Context context) {
        if (!ALLOW_REMOVABLE_STORAGE) {
            return context.getExternalFilesDir(null);
        }
        File[] dirs = context.getExternalFilesDirs(null);
        if (dirs == null) {
            return null;
        }
        for (File dir : dirs) {
            if (dir == null) {
                continue;
            }
            StatFs statFs = new StatFs(dir.toString());
            if (statFs.getTotalBytes() >= MIN_STORAGE_SIZE_FOR_DVR_IN_BYTES
                    && statFs.getAvailableBytes() >= MIN_FREE_STORAGE_SIZE_FOR_DVR_IN_BYTES) {
                return dir;
            }
        }
        return dirs[0];
    }
}
