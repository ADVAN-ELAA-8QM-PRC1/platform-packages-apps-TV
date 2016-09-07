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

package com.android.tv.dvr.ui;

import android.content.Context;
import android.support.v17.leanback.widget.AbstractDetailsDescriptionPresenter;

import com.android.tv.util.Utils;

/**
 * Presents a {@link DetailsContent}.
 */
public class DetailsContentPresenter extends AbstractDetailsDescriptionPresenter {
    @Override
    protected void onBindDescription(final ViewHolder viewHolder, Object itemData) {
        DetailsContent detailsContent = (DetailsContent) itemData;
        Context context = viewHolder.view.getContext();
        viewHolder.getTitle().setText(detailsContent.getTitle());
        if (detailsContent.getStartTimeUtcMillis() != DetailsContent.INVALID_TIME
                && detailsContent.getEndTimeUtcMillis() != DetailsContent.INVALID_TIME) {
            String playTime = Utils.getDurationString(context,
                    detailsContent.getStartTimeUtcMillis(),
                    detailsContent.getEndTimeUtcMillis(), false);
            viewHolder.getSubtitle().setText(playTime);
        }
        viewHolder.getBody().setText(detailsContent.getDescription());
    }
}
