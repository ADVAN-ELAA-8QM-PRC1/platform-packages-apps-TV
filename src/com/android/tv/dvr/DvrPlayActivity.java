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
 * limitations under the License
 */

package com.android.tv.dvr;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.android.tv.R;
import com.android.tv.TvApplication;

/**
 * Simple Activity to play a {@link ScheduledRecording}.
 */
public class DvrPlayActivity extends Activity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dvr_play);

        DvrDataManager dvrDataManager = TvApplication.getSingletons(this).getDvrDataManager();
        // TODO(DVR) handle errors.
        long recordingId = getIntent().getLongExtra(ScheduledRecording.RECORDING_ID_EXTRA, 0);
        ScheduledRecording scheduledRecording = dvrDataManager.getScheduledRecording(recordingId);
        TextView textView = (TextView) findViewById(R.id.placeHolderText);
        if (scheduledRecording != null) {
            textView.setText(scheduledRecording.toString());
        } else {
            textView.setText(R.string.ut_result_not_found_title);  // TODO(DVR) update error text
        }
    }
}