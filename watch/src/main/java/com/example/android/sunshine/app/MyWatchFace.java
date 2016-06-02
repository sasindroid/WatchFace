/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final String TAG = "MyWatchFace";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }


        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaintTime, mTextPaintFormattedDate, mTextPaintMaxTemp, mTextPaintMinTemp;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        // Data Map related
        private static final String WEAR_WEATHER_DATA_PATH = "/weather";
        String mMaxTemp = "";
        String mMinTemp = "";
        Bitmap mWeatherGraphic;

        boolean mBlinkColons = true;
        float mAdjustment;

        String mDateFormat = "EEE, MMM d yyyy";


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private GoogleApiClient mGoogleApiClient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    // Request access only to the Wearable API
                    .addApi(Wearable.API)
                    .build();

            mGoogleApiClient.connect();


            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mAdjustment = resources.getDimension(R.dimen.adjustment);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            mTextPaintTime = new Paint();
            mTextPaintTime = createTextPaint(resources.getColor(R.color.digital_text));

            mTextPaintFormattedDate = new Paint();
            mTextPaintFormattedDate = createTextPaint(ContextCompat.getColor(getBaseContext(), R.color.digital_text_secondary));

            mTextPaintMaxTemp = new Paint();
            mTextPaintMaxTemp = createTextPaint(ContextCompat.getColor(getBaseContext(), R.color.digital_text));

            mTextPaintMinTemp = new Paint();
            mTextPaintMinTemp = createTextPaint(ContextCompat.getColor(getBaseContext(), R.color.digital_text_secondary));

            mTime = new Time();


        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {

                if (!mGoogleApiClient.isConnected()) {
                    mGoogleApiClient.connect();
                }

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaintTime.setTextSize(textSize);
            mTextPaintFormattedDate.setTextSize(resources.getDimension(R.dimen.digital_text_size_secondary));
            mTextPaintMaxTemp.setTextSize(resources.getDimension(R.dimen.digital_text_size_weather_max));
            mTextPaintMinTemp.setTextSize(resources.getDimension(R.dimen.digital_text_size_weather_min));
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaintTime.setAntiAlias(!inAmbientMode);
                    mTextPaintFormattedDate.setAntiAlias(!inAmbientMode);
                    mTextPaintMaxTemp.setAntiAlias(!inAmbientMode);
                    mTextPaintMinTemp.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
//            Resources resources = MyWatchFace.this.getResources();
//            switch (tapType) {
//                case TAP_TYPE_TOUCH:
//                    // The user has started touching the screen.
//                    break;
//                case TAP_TYPE_TOUCH_CANCEL:
//                    // The user has started a different gesture or otherwise cancelled the tap.
//                    break;
//                case TAP_TYPE_TAP:
//                    // The user has completed the tap gesture.
//                    mTapCount++;
//                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
//                            R.color.background : R.color.background2));
//                    break;
//            }
//            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            Log.d(TAG, "onDraw called: " + mMaxTemp + " : " + mMinTemp);

            Resources res = getResources();

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();

//            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);

            int width = bounds.width();
            int height = bounds.height();
            float centerX = width / 2;
            float centerY = height / 2;

            mBlinkColons = (System.currentTimeMillis() % 1000) < 500;
//            String timeText = (mAmbient || mBlinkColons) ? String.format("%d %02d", mTime.hour, mTime.minute) : String.format("%d:%02d", mTime.hour, mTime.minute);

            String timeText = String.format("%02d:%02d", mTime.hour, mTime.minute);

            float timeTextWidth = mTextPaintTime.measureText(timeText);
            float positionX = centerX - (timeTextWidth / 2);
            float positionY;
//            positionY = getResources().getDimension(R.dimen.digital_y_offset);
            positionY = centerY - (centerY / 2) + mAdjustment;

            Log.d(TAG, "positionY: " + positionY);

            canvas.drawText(timeText, positionX, positionY, mTextPaintTime);

            // Row 2 - FRI, JUL 14 2015
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat(mDateFormat);
            String dateFormatted = simpleDateFormat.format(new Date()).toUpperCase();

            timeTextWidth = mTextPaintFormattedDate.measureText(dateFormatted);
            positionX = centerX - (timeTextWidth / 2);
            positionY = centerY - (centerY / 6);

            canvas.drawText(dateFormatted, positionX, positionY, mTextPaintFormattedDate);

            // Row 3 - A center horizontal line.
            canvas.drawLine(centerX - 50, centerY, centerX + 50, centerY, mTextPaintFormattedDate);

            // Row 4 - Center data - Max Temp
            timeTextWidth = mTextPaintMaxTemp.measureText(mMaxTemp);
            canvas.drawText(mMaxTemp, centerX - (timeTextWidth / 2), centerY + (centerY / 2), mTextPaintMaxTemp);

            // Row 4 - Left - Weather Graphic
            if (mWeatherGraphic != null && !mAmbient) {
                canvas.drawBitmap(mWeatherGraphic, centerX - (centerX / 2) - (mWeatherGraphic.getWidth() / 2),
                        centerY + (mWeatherGraphic.getHeight() / 2) - 10, null);
            }

            // Row 4 - Right - Min Temp
            timeTextWidth = mTextPaintMinTemp.measureText(mMinTemp);
//            canvas.drawText(mMinTemp, centerX + (centerX / 2) - (timeTextWidth / 2), centerY + (centerY / 2) - 12, mTextPaintMinTemp);
            canvas.drawText(mMinTemp, centerX + (centerX / 2) - (timeTextWidth / 2), centerY + (centerY / 2), mTextPaintMinTemp);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        // GoogleApiClient.ConnectionCallbacks
        @Override
        public void onConnected(@Nullable Bundle bundle) {

            Log.d(TAG, "onConnected called");

            // When connected, enable the DataApi listener (onDataChanged).
            Wearable.DataApi.addListener(mGoogleApiClient, this);

            Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(new ResultCallback<DataItemBuffer>() {
                @Override
                public void onResult(@NonNull DataItemBuffer dataItems) {

                    Log.d(TAG, "onResult called");

                    for (DataItem dataItem : dataItems) {

                        parseDataAndSetValues(dataItem);
                    }
                }
            });

        }

        // GoogleApiClient.ConnectionCallbacks
        @Override
        public void onConnectionSuspended(int i) {

            // When disconnected, disable the DataApi listener (onDataChanged).
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
//            mGoogleApiClient.disconnect();

        }

        // GoogleApiClient.OnConnectionFailedListener
        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        // DataApi.DataListener
        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {

            Log.d(TAG, "onDataChanged called");

            for (DataEvent dataEvent : dataEventBuffer) {

                // Proceed only if the data items have changed.
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {

                    parseDataAndSetValues(dataEvent.getDataItem());
                }
            }

        }

        private void parseDataAndSetValues(DataItem dataItem) {

            Uri uri = dataItem.getUri();
            Log.d(TAG, "URI PATH: " + uri.toString());

//                    if (uri.equals(WEAR_WEATHER_DATA_PATH)) {

            // Retrieve the data from the data map.
//                    DATA_KEY_MAX_TEMP DATA_KEY_WEATHER_ID

            DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();

            mMaxTemp = dataMap.getString("DATA_KEY_MAX_TEMP");
            mMinTemp = dataMap.getString("DATA_KEY_MIN_TEMP");

            int weatherID = dataMap.getInt("DATA_KEY_WEATHER_ID");

            Log.d(TAG, mMaxTemp + ":" + mMinTemp + " : " + weatherID);

            // Get the updated weather graphic based on the weather id.
            setWeatherGraphicBitmap(weatherID);
//                    }

        }

        private void setWeatherGraphicBitmap(int weatherID) {

            int drawableRes = Utility.getArtResourceForWeatherCondition(weatherID);

            // Convert the drawable to bitmap.
            mWeatherGraphic = BitmapFactory.decodeResource(getResources(), drawableRes);
            mWeatherGraphic = Bitmap.createScaledBitmap(mWeatherGraphic, 72, 72, true);
        }
    }
}
