package com.example.android.sunshine.app;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

public class MyWatchService extends IntentService implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private static final String TAG = "MyWatchService";
    public static final String ACTION_UPDATE_WEAR_DATA = "ACTION_UPDATE_WEAR_DATA";
    private static final String WEAR_WEATHER_DATA_PATH = "/weather";

    GoogleApiClient mGoogleApiClient;

    private static final String[] FORECAST_COLUMNS = {
            WeatherContract.WeatherEntry.TABLE_NAME + "." + WeatherContract.WeatherEntry._ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID
    };

    /**
     * Creates an IntentService.  Invoked by your subclass's constructor.
     */
    public MyWatchService() {
        super("SERVICE_UPDATE_WEAR_DATA");
    }


    /**
     * This method is invoked on the worker thread with a request to process.
     * Only one Intent is processed at a time, but the processing happens on a
     * worker thread that runs independently from other application logic.
     * So, if this code takes a long time, it will hold up other requests to
     * the same IntentService, but it will not hold up anything else.
     * When all requests have been handled, the IntentService stops itself,
     * so you should not call {@link #stopSelf}.
     *
     * @param intent The value passed to {@link
     *               Context#startService(Intent)}.
     */
    @Override
    protected void onHandleIntent(Intent intent) {

        if (intent != null && intent.getAction().equals(ACTION_UPDATE_WEAR_DATA)) {

            mGoogleApiClient = new GoogleApiClient.Builder(getBaseContext())
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            mGoogleApiClient.connect();
        }
    }

    // GoogleApiClient.ConnectionCallbacks
    @Override
    public void onConnected(@Nullable Bundle bundle) {

        // Once connected, query the db and retrieve the weather data.

        // Sort order:  Ascending, by date.
        String sortOrder = WeatherContract.WeatherEntry.COLUMN_DATE + " ASC";

        String locationSetting = Utility.getPreferredLocation(getBaseContext());
        Uri weatherForLocationUri = WeatherContract.WeatherEntry.buildWeatherLocationWithStartDate(
                locationSetting, System.currentTimeMillis());

        Cursor cursor = null;

        try {
            cursor = getContentResolver().query(
                    weatherForLocationUri,
                    FORECAST_COLUMNS,
                    null,
                    null,
                    sortOrder);

            // Move the cursor to the first row.
            if (cursor.moveToFirst()) {

                // Now get the data and set it to the data map.

                double maxTemp = cursor.getDouble(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP));
                double minTemp = cursor.getDouble(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP));
                int weatherID = cursor.getInt(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID));

                String sMaxTemp = Utility.formatTemperature(getBaseContext(), maxTemp);
                String sMinTemp = Utility.formatTemperature(getBaseContext(), minTemp);

                Log.d(TAG, "RETRIEVED DATA: " + sMaxTemp + " - " + sMinTemp + " - " + weatherID);

                PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEAR_WEATHER_DATA_PATH);
                putDataMapRequest.getDataMap().putString("DATA_KEY_MAX_TEMP", sMaxTemp);
                putDataMapRequest.getDataMap().putString("DATA_KEY_MIN_TEMP", sMinTemp);
                putDataMapRequest.getDataMap().putInt("DATA_KEY_WEATHER_ID", weatherID);

                PutDataRequest request = putDataMapRequest.asPutDataRequest().setUrgent();

                Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                        .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                            @Override
                            public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {

                                // Remember the success here tells the object has been stored locally
                                // and it doesn't tell if the received received it successfully or not.

                                Log.d(TAG, "RESULT: " + dataItemResult.getStatus().isSuccess());
                            }
                        });
            }

            cursor.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

    }

    // GoogleApiClient.ConnectionCallbacks
    @Override
    public void onConnectionSuspended(int i) {

    }

    // GoogleApiClient.OnConnectionFailedListener
    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }
}
