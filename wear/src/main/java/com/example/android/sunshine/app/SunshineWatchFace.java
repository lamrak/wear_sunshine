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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
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
    private static final String LOG_TAG = "wear";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        private static final String WEARABLE_DATA_PATH = "/weather";
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mWeatherIconPaint;
        Paint mTextPaint;
        Paint mDatePaint;
        Paint mLowPaint;
        boolean mAmbient;
        Time mTime;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        boolean isRound;
        float mCenterX;
        float mCenterY;
        float mXOffset;
        float mYOffset;
        float mWeatherYOffset;
        float mWeatherXOffset;

        Bitmap mWeatherIconBitmap;
        String mHighTemp;
        String mLowTemp;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));
            mWeatherIconPaint = new Paint();

            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint = createTextPaint(resources.getColor(R.color.text_color));
            mLowPaint  = createTextPaint(resources.getColor(R.color.text_gray_color));
            mTime = new Time();

            initWeather(0, 0, null);
        }

        private void initWeather(int high, int low, String icon  ){
            if (icon == null){
                icon = "clear";
            }
            mWeatherIconBitmap = BitmapFactory.decodeResource(getResources(),
                    getResources().getIdentifier("ic_" + icon, "drawable", getPackageName()));
            mHighTemp = String.format("%3s", String.valueOf(high)) + "°C";
            mLowTemp = String.format("%3s", String.valueOf(low)) + "°C";

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
                registerReceiver();
                mGoogleApiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
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
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            mCenterX = width / 2f;
            mCenterY = height / 2f;
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

            mYOffset = resources.getDimension(isRound ?
                    R.dimen.digital_y_offset_round : R.dimen.digital_y_offset);

            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float dateSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_size_round : R.dimen.digital_date_size);

            mWeatherYOffset = resources.getDimension(isRound ?
                    R.dimen.weather_round_y_offset : R.dimen.weather_y_offset);

            mWeatherXOffset = resources.getDimension(isRound ?
                    R.dimen.weather_x_offset_round : R.dimen.digital_x_offset);

            mTextPaint.setTextSize(textSize);
            mDatePaint.setTextSize(dateSize);
            mLowPaint.setTextSize(dateSize);
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
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                }

                if (!mAmbient) {
                    mBackgroundPaint.setColor(SunshineWatchFace.this.getResources()
                            .getColor(R.color.sun_background));
                } else {
                    mBackgroundPaint.setColor(Color.BLACK);
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
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
//                    mTapCount++;
//                    mBackgroundPaint.setColor(resources.getColor(R.color.sun_background));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawColor(SunshineWatchFace.this.getResources()
                        .getColor(R.color.sun_background));
                //canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow(); //:%02d, , mTime.second
            canvas.drawText(
                    String.format("%d:%02d", mTime.hour, mTime.minute), mXOffset, mYOffset, mTextPaint);
            if (!mAmbient) {
                SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d, ''yy", Locale.US);
                String dayString = sdf.format(new Date());
                canvas.drawText(dayString, mXOffset, mYOffset + 50, mDatePaint);
                canvas.drawLine(mXOffset, mCenterY + 15, mCenterX + 80, mCenterY + 15, mDatePaint);
                // draw weather
                canvas.drawBitmap(mWeatherIconBitmap, mWeatherXOffset, mWeatherYOffset, mWeatherIconPaint);
                canvas.drawText(mHighTemp, mWeatherXOffset + 60, mWeatherYOffset + 34, mLowPaint);
                canvas.drawText(mLowTemp, mWeatherXOffset + 124, mWeatherYOffset + 34, mDatePaint);
            }
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

        // Google Api and device connection

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();


        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {}

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "onDataChanged");
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (dataItem.getUri().getPath().equals(WEARABLE_DATA_PATH)) {

                    DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                    DataMap dMap = dataMapItem.getDataMap();
                    int high = (int) Math.round(dMap.getDouble("high"));
                    int low = (int) Math.round(dMap.getDouble("low"));
                    Long id = dMap.getLong("id");
                    String icon = getWeatherUrlById(id);
                    initWeather(high, low, icon);
                    invalidate();
                }
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {

        }
    }

    private String getWeatherUrlById(Long weatherId) {
        if (weatherId >= 200 && weatherId <= 232) {
            return "storm";
        } else if (weatherId >= 300 && weatherId <= 321) {
            return "light_rain";
        } else if (weatherId >= 500 && weatherId <= 504) {
            return "rain";
        } else if (weatherId == 511) {
            return "snow";
        } else if (weatherId >= 520 && weatherId <= 531) {
            return "rain";
        } else if (weatherId >= 600 && weatherId <= 622) {
            return "snow";
        } else if (weatherId >= 701 && weatherId <= 761) {
            return "fog";
        } else if (weatherId == 761 || weatherId == 781) {
            return "storm";
        } else if (weatherId == 800) {
            return "clear";
        } else if (weatherId == 801) {
            return "light_clouds";
        } else if (weatherId >= 802 && weatherId <= 804) {
            return "cloudy";
        }

        return null;
    }
}
