package com.alvinhkh.buseta.service;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.SparseArray;

import com.alvinhkh.buseta.Constants;
import com.alvinhkh.buseta.R;
import com.alvinhkh.buseta.holder.EtaAdapterHelper;
import com.alvinhkh.buseta.holder.RouteStop;

import org.jsoup.Jsoup;

import java.util.Date;

public class NotificationService extends Service {

    private static final String TAG = NotificationService.class.getSimpleName();
    private static final String ACTION_CANCEL = "ACTION_CANCEL";
    private static final String NOTIFICATION_ID = "NOTIFICATION_ID";

    protected NotificationManagerCompat mNotifyManager;
    protected NotificationCompat.Builder mBuilder;

    private SparseArray<RouteStop> routeStopArray;
    private UpdateEtaReceiver mReceiver;

    public NotificationService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Log.d(TAG, "onCreate");
        routeStopArray = new SparseArray<>();
        // Broadcast Reciever
        IntentFilter mFilter_eta = new IntentFilter(Constants.MESSAGE.ETA_UPDATED);
        mReceiver = new UpdateEtaReceiver();
        mFilter_eta.addAction(Constants.MESSAGE.ETA_UPDATED);
        registerReceiver(mReceiver, mFilter_eta);
        // Auto Refresh
        if (null != mAutoRefreshHandler && null != mAutoRefreshRunnable)
            mAutoRefreshHandler.post(mAutoRefreshRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Log.d(TAG, "onStartCommand");
        if (intent == null) {
            stopSelf();
            return -1;
        }
        Bundle extras = intent.getExtras();
        if (extras == null) {
            stopSelf();
            return -1;
        }
        String action = intent.getAction();
        if (null != action && action.equals(ACTION_CANCEL)) {
            int notificationId = extras.getInt(NOTIFICATION_ID);
            // Log.d(TAG, "Remove: " + notificationId);
            if (null != routeStopArray)
                routeStopArray.delete(notificationId);
            mNotifyManager = NotificationManagerCompat.from(this);
            mNotifyManager.cancel(notificationId);
            if (routeStopArray.size() < 1)
                stopSelf();
            return -1;
        }
        RouteStop object = extras.getParcelable(Constants.BUNDLE.STOP_OBJECT);
        if (null == object || null == object.route_bound) {
            stopSelf();
            return -1;
        }
        int notificationId = parse(object);
        // ask for update
        Intent updateIntent = new Intent(getApplicationContext(), CheckEtaService.class);
        updateIntent.putExtra(Constants.BUNDLE.STOP_OBJECT, object);
        updateIntent.putExtra(Constants.MESSAGE.NOTIFICATION_UPDATE, notificationId);
        startService(updateIntent);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding, so return null
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        if (null != mAutoRefreshHandler && null != mAutoRefreshRunnable)
            mAutoRefreshHandler.removeCallbacks(mAutoRefreshRunnable);
        mNotifyManager = NotificationManagerCompat.from(this);
        mNotifyManager.cancelAll();
        if (null != routeStopArray)
            routeStopArray.clear();
        if (null != mReceiver)
            unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    private int parse(RouteStop object) {
        if (null == object || null == object.route_bound) return -1;
        StringBuilder smallContentTitle = new StringBuilder();
        StringBuilder smallText = new StringBuilder();
        StringBuilder bigText = new StringBuilder();
        StringBuilder bigContentTitle = new StringBuilder();
        StringBuilder bigSummaryText = new StringBuilder();
        StringBuilder subText = new StringBuilder();
        StringBuilder contentInfo = new StringBuilder();
        smallContentTitle.append(object.route_bound.route_no);
        bigContentTitle.append(object.route_bound.route_no);
        bigContentTitle.append(" ");
        bigContentTitle.append(object.name_tc);
        bigContentTitle.append(" ");
        bigContentTitle.append(getString(R.string.destination, object.route_bound.destination_tc));
        subText.append(object.name_tc);
        subText.append(" ");
        subText.append(getString(R.string.destination, object.route_bound.destination_tc));
        if (null != object.eta) {
            // Request Time
            String server_time = "";
            Date server_date = null;
            if (null != object.eta.server_time && !object.eta.server_time.equals("")) {
                server_date = EtaAdapterHelper.serverDate(object);
                server_time = (null != server_date) ?
                        EtaAdapterHelper.display_format.format(server_date) : object.eta.server_time;
            }
            // last updated
            String updated_time = "";
            Date updated_date;
            if (null != object.eta.updated && !object.eta.updated.equals("")) {
                updated_date = EtaAdapterHelper.updatedDate(object);
                updated_time = (null != updated_date) ?
                        EtaAdapterHelper.display_format.format(updated_date) : object.eta.updated;
            }
            // ETAs
            String eta = Jsoup.parse(object.eta.etas).text();
            String[] etas = eta.replaceAll("　", " ").split(", ?");
            for (int i = 0; i < etas.length; i++) {
                bigText.append(etas[i]);
                String estimate = EtaAdapterHelper.etaEstimate(object, etas, i, server_date, null, null, null);
                bigText.append(estimate);
                if (i < etas.length - 1) {
                    bigText.append("\n");
                }
                String text = etas[i].replaceAll(" ?　?預定班次", "");
                if (i == 0) {
                    smallContentTitle.append(" ");
                    smallContentTitle.append(text);
                    smallContentTitle.append(estimate);
                } else {
                    smallText.append(text);
                    smallText.append(estimate);
                    if (i < etas.length - 1) {
                        smallText.append(" ");
                    }
                }
            }
            if (null != updated_time && !updated_time.equals("")) {
                bigSummaryText.append(updated_time);
            } else if (null != server_time && !server_time.equals("")) {
                bigSummaryText.append(server_time);
            }
        }
        contentInfo.append(getString(R.string.app_name));
        // Foreground Notification
        Context context = getApplicationContext();
        int notificationId = 100;
        for (int i = 0; i < object.route_bound.route_no.length(); i++) {
            notificationId += object.route_bound.route_no.charAt(i);
        }
        notificationId += object.name_tc.codePointAt(0);
        notificationId -= object.name_tc.codePointAt(object.name_tc.length()-1);
        notificationId += object.route_bound.destination_tc.codePointAt(0);
        int color = ContextCompat.getColor(context, R.color.primary);
        NotificationCompat.WearableExtender wearableExtender = new NotificationCompat.WearableExtender();
        wearableExtender.setHintScreenTimeout(NotificationCompat.WearableExtender.SCREEN_TIMEOUT_LONG);
        if (null != object.bitmap)
            wearableExtender.setBackground(object.bitmap);
        if (null != object.details) {
            Uri uri = new Uri.Builder().scheme("geo")
                    .appendPath(object.details.lat + "," + object.details.lng)
                    .appendQueryParameter("q", object.details.lat + "," + object.details.lng +
                            "(" + object.name_tc + ")")
                    .build();
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, uri);
            PendingIntent pendingIntent =
                    PendingIntent.getActivity(this, 0, mapIntent, 0);
            NotificationCompat.Action action =
                    new NotificationCompat.Action.Builder(R.drawable.ic_map_white_48dp,
                            getString(R.string.action_open_map), pendingIntent)
                            .build();
            wearableExtender.addAction(action);
        }
        if (null == mBuilder)
            mBuilder = new NotificationCompat.Builder(context);
        Intent notificationIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.URI.STOP));
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        notificationIntent.putExtra(Constants.BUNDLE.STOP_OBJECT, object);
        PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(),
                notificationId, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        mBuilder.setContentTitle(smallContentTitle.length() > 0 ? smallContentTitle : null)
                .setContentText(smallText.length() > 0 ? smallText : null)
                .setSubText(subText.length() > 0 ? subText : null)
                .setContentInfo(contentInfo.length() > 0 ? contentInfo : null)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .setBigContentTitle(bigContentTitle.length() > 0 ? bigContentTitle : null)
                        .setSummaryText(bigSummaryText.length() > 0 ? bigSummaryText : null)
                        .bigText(bigText.length() > 0 ? bigText : null))
                .setSmallIcon(R.drawable.ic_directions_bus_white_24dp)
                .setColor(color)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(pendingIntent)
                .setDeleteIntent(createDeleteIntent(context, notificationId))
                .extend(wearableExtender);
        mNotifyManager = NotificationManagerCompat.from(this);
        mNotifyManager.notify(notificationId, mBuilder.build());
        routeStopArray.put(notificationId, object);
        // Log.d(TAG, "Add: " + notificationId + " " + object.name_tc);
        return notificationId;
    }

    private PendingIntent createDeleteIntent(Context context, int notificationId) {
        Intent deleteIntent = new Intent(context, NotificationService.class);
        deleteIntent.setAction(ACTION_CANCEL);
        deleteIntent.putExtra(NOTIFICATION_ID, notificationId);
        return PendingIntent.getService(context.getApplicationContext(), notificationId,
                deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public class UpdateEtaReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle bundle = intent.getExtras();
            Boolean aBoolean = bundle.getBoolean(Constants.MESSAGE.ETA_UPDATED);
            if (aBoolean) {
                RouteStop routeStop = bundle.getParcelable(Constants.BUNDLE.STOP_OBJECT);
                Integer notificationId = bundle.getInt(Constants.MESSAGE.NOTIFICATION_UPDATE);
                if (notificationId > 0) {
                    RouteStop object = routeStopArray.get(notificationId);
                    if (null != routeStop && null != routeStop.route_bound
                            && null != object && null != object.route_bound) {
                        if (object.route_bound.route_no.equals(routeStop.route_bound.route_no) &&
                                object.route_bound.route_bound.equals(routeStop.route_bound.route_bound) &&
                                object.stop_seq.equals(routeStop.stop_seq) &&
                                object.code.equals(routeStop.code)) {
                            object.eta = routeStop.eta;
                            object.eta_loading = routeStop.eta_loading;
                            object.eta_fail = routeStop.eta_fail;
                            parse(object);
                        }
                    }
                }
            }
        }
    }

    Handler mAutoRefreshHandler = new Handler();
    Runnable mAutoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            for (int i = 0; i < routeStopArray.size(); i++) {
                int key = routeStopArray.keyAt(i);
                RouteStop object = routeStopArray.get(key);
                Intent updateIntent = new Intent(getApplicationContext(), CheckEtaService.class);
                object.bitmap = null;
                updateIntent.putExtra(Constants.BUNDLE.STOP_OBJECT, object);
                updateIntent.putExtra(Constants.MESSAGE.NOTIFICATION_UPDATE, key);
                if (null != getApplication())
                    getApplication().startService(updateIntent);
            }
            mAutoRefreshHandler.postDelayed(mAutoRefreshRunnable, 15 * 1000); // every 15 seconds
        }
    };

}
