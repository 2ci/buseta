package com.alvinhkh.buseta.service;

import android.app.IntentService;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.support.v7.preference.PreferenceManager;
import android.text.TextUtils;

import com.alvinhkh.buseta.Api;
import com.alvinhkh.buseta.C;
import com.alvinhkh.buseta.kmb.KmbService;
import com.alvinhkh.buseta.kmb.model.KmbEtaRoutes;
import com.alvinhkh.buseta.utils.ConnectivityUtil;
import com.alvinhkh.buseta.R;
import com.alvinhkh.buseta.model.AppUpdate;
import com.alvinhkh.buseta.provider.SuggestionProvider;
import com.alvinhkh.buseta.provider.SuggestionTable;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.observers.DisposableObserver;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

public class CheckUpdateService extends IntentService {

    SharedPreferences mPrefs;

    public CheckUpdateService() {
        super("CheckUpdateService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Boolean manualUpdate = false;
        if (null != intent) {
            manualUpdate = intent.getBooleanExtra(C.EXTRA.MANUAL, false);
        }
        mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        int routeVersion = mPrefs.getInt(C.PREF.VERSION_RECORD, 0);
        // Check internet connection
        if (!ConnectivityUtil.isConnected(this)) {
            Intent i = new Intent(C.ACTION.SUGGESTION_ROUTE_UPDATE);
            i.putExtra(C.EXTRA.UPDATED, true);
            i.putExtra(C.EXTRA.MANUAL, manualUpdate);
            i.putExtra(C.EXTRA.MESSAGE_RID, R.string.message_no_internet_connection);
            sendBroadcast(i);
            return;
        }
        // app update check
        Api apiService = Api.retrofit.create(Api.class);
        apiService.appUpdate()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeWith(appUpdateObserver(manualUpdate));
        // start fetch available bus route
        KmbService kmbService = KmbService.etadatafeed.create(KmbService.class);
        kmbService.getEtaRoutes()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeWith(kmbRoutesObserver(manualUpdate));
    }

    DisposableObserver<List<KmbEtaRoutes>> kmbRoutesObserver(Boolean manualUpdate) {
        return new DisposableObserver<List<KmbEtaRoutes>>() {
            @Override
            public void onNext(List<KmbEtaRoutes> res) {
                if (res.size() < 1 || TextUtils.isEmpty(res.get(0).r_no)) return;
                // count existing routes
                Cursor mCursor_suggestion = getContentResolver().query(SuggestionProvider.CONTENT_URI,
                        null, SuggestionTable.COLUMN_TYPE + " = '" + SuggestionTable.TYPE_DEFAULT + "'",
                        null, SuggestionTable.COLUMN_DATE + " DESC");
                int count = 0;
                if (null != mCursor_suggestion) {
                    count = mCursor_suggestion.getCount();
                    mCursor_suggestion.close();
                }
                //
                String routes = res.get(0).r_no;
                String[] routeArray = routes.split(",");
                Timber.d("Suggestion In DB: %s Available: %s", count, routeArray.length);
                if (routeArray.length > count || count == 0) {
                    // clear existing suggested routes
                    getContentResolver().delete(SuggestionProvider.CONTENT_URI,
                            SuggestionTable.COLUMN_TYPE + "=?",
                            new String[]{SuggestionTable.TYPE_DEFAULT});
                    //
                    ContentValues[] contentValues = new ContentValues[routeArray.length];
                    for (int i = 0; i < routeArray.length; i++) {
                        ContentValues values = new ContentValues();
                        values.put(SuggestionTable.COLUMN_TEXT, routeArray[i]);
                        values.put(SuggestionTable.COLUMN_TYPE, SuggestionTable.TYPE_DEFAULT);
                        values.put(SuggestionTable.COLUMN_DATE, "0");
                        contentValues[i] = values;
                    }
                    int insertedRows = getContentResolver().bulkInsert(
                            SuggestionProvider.CONTENT_URI, contentValues);
                    if (null != mPrefs) {
                        // update record version number
                        Calendar now = Calendar.getInstance();
                        String nowYear = String.format(Locale.ENGLISH, "%02d", now.get(Calendar.YEAR));
                        String nowMonth = String.format(Locale.ENGLISH, "%02d", now.get(Calendar.MONTH) + 1);
                        String nowDay = String.format(Locale.ENGLISH, "%02d", now.get(Calendar.DAY_OF_MONTH));
                        String date = nowYear + nowMonth + nowDay;
                        Integer version = Integer.valueOf(date);
                        SharedPreferences.Editor editor = mPrefs.edit();
                        editor.putInt(C.PREF.VERSION_RECORD, version);
                        editor.apply();
                    }
                    if (insertedRows > 0) {
                        Timber.d("updated available routes suggestion: %s", insertedRows);
                    } else {
                        Timber.d("error when inserting available routes to database");
                    }
                    Intent i = new Intent(C.ACTION.SUGGESTION_ROUTE_UPDATE);
                    i.putExtra(C.EXTRA.UPDATED, true);
                    i.putExtra(C.EXTRA.MANUAL, manualUpdate);
                    i.putExtra(C.EXTRA.MESSAGE_RID, R.string.message_database_updated);
                    sendBroadcast(i);
                }
            }

            @Override
            public void onError(Throwable e) {
                Timber.d(e);
                Intent i = new Intent(C.ACTION.SUGGESTION_ROUTE_UPDATE);
                i.putExtra(C.EXTRA.UPDATED, true);
                i.putExtra(C.EXTRA.MANUAL, manualUpdate);
                i.putExtra(C.EXTRA.MESSAGE_RID, R.string.message_fail_to_request);
                sendBroadcast(i);
            }

            @Override
            public void onComplete() {
            }
        };
    }

    DisposableObserver<List<AppUpdate>> appUpdateObserver(Boolean manualUpdate) {
        return new DisposableObserver<List<AppUpdate>>() {
            @Override
            public void onNext(List<AppUpdate> res) {
                if (res.size() < 1) return;
                AppUpdate appUpdate = res.get(0);
                Intent i = new Intent(C.ACTION.APP_UPDATE);
                i.putExtra(C.EXTRA.UPDATED, true);
                i.putExtra(C.EXTRA.MANUAL, manualUpdate);
                i.putExtra(C.EXTRA.APP_UPDATE_OBJECT, appUpdate);
                sendBroadcast(i);
            }

            @Override
            public void onError(Throwable e) {
                Timber.d(e);
            }

            @Override
            public void onComplete() {
            }
        };
    }
}
