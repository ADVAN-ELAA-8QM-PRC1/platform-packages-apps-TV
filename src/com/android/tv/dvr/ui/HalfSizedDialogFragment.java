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

package com.android.tv.dvr.ui;

import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.tv.R;
import com.android.tv.dialog.SafeDismissDialogFragment;

import java.util.concurrent.TimeUnit;

public class HalfSizedDialogFragment extends SafeDismissDialogFragment {
    public static final String DIALOG_TAG = HalfSizedDialogFragment.class.getSimpleName();
    public static final String TRACKER_LABEL = "Half sized dialog";

    private static final long AUTO_DISMISS_TIME_THRESHOLD_MS = TimeUnit.SECONDS.toMillis(30);

    private Handler mHandler = new Handler();
    private Runnable mAutoDismisser = new Runnable() {
        @Override
        public void run() {
            dismiss();
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.halfsized_dialog, container, false);
    }

    @Override
    public void onStart() {
        super.onStart();
        getDialog().setOnKeyListener(new DialogInterface.OnKeyListener() {
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent keyEvent) {
                mHandler.removeCallbacks(mAutoDismisser);
                mHandler.postDelayed(mAutoDismisser, AUTO_DISMISS_TIME_THRESHOLD_MS);
                return false;
            }
        });
        mHandler.postDelayed(mAutoDismisser, AUTO_DISMISS_TIME_THRESHOLD_MS);
    }

    @Override
    public void onStop() {
        super.onStop();
        mHandler.removeCallbacks(mAutoDismisser);
    }

    @Override
    public int getTheme() {
        return R.style.Theme_TV_dialog_HalfSizedDialog;
    }

    @Override
    public String getTrackerLabel() {
        return TRACKER_LABEL;
    }
}