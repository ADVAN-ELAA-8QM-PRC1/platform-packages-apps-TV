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

package com.android.tv.dvr.ui.list;

import android.os.Bundle;
import android.support.v17.leanback.widget.ClassPresenterSelector;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.tv.R;
import com.android.tv.TvApplication;
import com.android.tv.common.SoftPreconditions;
import com.android.tv.dvr.SeriesRecording;
import com.android.tv.dvr.ui.DvrSchedulesActivity;
import com.android.tv.dvr.ui.list.SchedulesHeaderRow.SeriesRecordingHeaderRow;
import com.android.tv.dvr.ui.list.SchedulesHeaderRowPresenter.SeriesRecordingHeaderRowPresenter;

/**
 * A fragment to show the list of series schedule recordings.
 */
public class DvrSeriesSchedulesFragment extends BaseDvrSchedulesFragment {
    /**
     * The key for series recording whose scheduled recording list will be displayed.
     */
    public static String SERIES_SCHEDULES_KEY_SERIES_RECORDING =
            "series_schedules_key_series_recording";

    private static String TAG = "DvrSeriesSchedulesFragment";

    private SeriesRecording mSeries;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Bundle args = getArguments();
        if (args != null) {
            mSeries = args.getParcelable(SERIES_SCHEDULES_KEY_SERIES_RECORDING);
        }
        super.onCreate(savedInstanceState);
        // "1" means there is only title row in series schedules list. So we should show an empty
        // state info view.
        if (getRowsAdapter().size() == 1) {
            showEmptyMessage(R.string.dvr_series_schedules_empty_state);
        }
        ((DvrSchedulesActivity) getActivity()).setCancelAllClickedRunnable(new Runnable() {
            @Override
            public void run() {
                SoftPreconditions.checkState(getRowsAdapter().get(0) instanceof
                        SeriesRecordingHeaderRow, TAG, "First row is not SchedulesHeaderRow");
                SeriesRecordingHeaderRow headerRow =
                        (SeriesRecordingHeaderRow) getRowsAdapter().get(0);
                headerRow.setCancelAllChecked(true);
                if (headerRow.getSeriesRecording() != null) {
                    TvApplication.getSingletons(getContext()).getDvrManager()
                            .updateSeriesRecording(SeriesRecording.buildFrom(
                                    headerRow.getSeriesRecording()).setState(
                                    SeriesRecording.STATE_SERIES_CANCELED).build());
                }
                onUpdateAllScheduleRows();
            }
        });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public SchedulesHeaderRowPresenter onCreateHeaderRowPresenter() {
        return new SeriesRecordingHeaderRowPresenter(getContext());
    }

    @Override
    public ScheduleRowPresenter onCreateRowPresenter() {
        return new SeriesScheduleRowPresenter(getContext());
    }

    @Override
    public ScheduleRowAdapter onCreateRowsAdapter(ClassPresenterSelector presenterSelector) {
        return new SeriesScheduleRowAdapter(getContext(), presenterSelector, mSeries);
    }

    @Override
    protected int getFirstItemPosition() {
        if (mSeries != null && mSeries.getState() == SeriesRecording.STATE_SERIES_CANCELED) {
            return -1;
        }
        return super.getFirstItemPosition();
    }

    @Override
    public void onDestroy() {
        ((DvrSchedulesActivity) getActivity()).setCancelAllClickedRunnable(null);
        super.onDestroy();
    }
}