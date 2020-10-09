package nl.ma.decoder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import org.kivy.android.PythonService;

import java.io.File;

import nl.ma.utopiaserver.UtopiaServer;

public class mindaffectBCIService extends Service {
    private static final int ONGOING_NOTIFICATION_ID = 55 ;
    int mStartMode=Service.START_STICKY;       // indicates how to behave if the service is killed
    IBinder mBinder;      // interface for clients that bind
    boolean mAllowRebind=true; // indicates whether onRebind should be used

    private static final String TAG = mindaffectBCIService.class.getSimpleName();
    UtopiaServer utopiaServer;
    Thread serverThread = null;
    GanglionAndroidBluetooth gab = null;
    Thread ganglionThread = null;
    Thread decoderThread = null;
    WifiManager.MulticastLock multicastlock;


    @Override
    public void onCreate() {

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The service is starting, due to a call to startService()
        startBackgroundThreads(intent);
        return mStartMode;
    }
    @Override
    public IBinder onBind(Intent intent) {
        // A client is binding to the service with bindService()
        startBackgroundThreads(intent);
        return mBinder;
    }
    @Override
    public boolean onUnbind(Intent intent) {
        // All clients have unbound with unbindService()
        return mAllowRebind;
    }
    @Override
    public void onRebind(Intent intent) {
        // A client is binding to the service with bindService(),
        // after onUnbind() has already been called
    }
    @Override
    public void onDestroy() {
        // The service is no longer used and is being destroyed
        super.onDestroy();
        // only cleanup if we are actually being destroyed!, i.e. not on rotation.
        Log.v(TAG, "Killing client threads");
        if (ganglionThread != null) {
            ganglionThread.interrupt();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
        // release multi-cast lock
        multicastlock.release();
            // TODO[] does this work?  A: no!
        ServiceMindaffectbci.mService.stopSelf();
    }

    public void startBackgroundThreads(Intent intent){
        Intent notificationIntent = new Intent(this, mindaffectBCIService.class);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Notification notification =
                null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, NotificationChannel.DEFAULT_CHANNEL_ID)
                    .setContentTitle(getText(R.string.notification_title))
                    .setContentText(getText(R.string.notification_message))
                    .setSmallIcon(R.drawable.icon)
                    .setContentIntent(pendingIntent)
                    .setTicker(getText(R.string.ticker_text))
                    .build();
        }
        startForeground(ONGOING_NOTIFICATION_ID, notification);

        // The service is being created
        /* Turn off multicast filter */
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            multicastlock = wifi.createMulticastLock("gab_multicastlock");
            multicastlock.acquire();
        }

        //Window window = getWindow();
        //window.addOnFrameMetricsAvailableListener();

        // TODO []: move this to a service? and bind to it from all client apps?
        // TODO [x]: tell it where to save the logs...
        if (utopiaServer == null) {
            File external = getExternalFilesDir(null);
            String dataFile = null;
            if (external != null && external.canWrite()) {
                dataFile = external.getParent() + "/mindaffectBCI.txt";
            }
            utopiaServer = new UtopiaServer();
            utopiaServer.initialize(-1, dataFile);
            serverThread = utopiaServer.startServerThread(Thread.NORM_PRIORITY + 2, false);
        }

        if (gab == null) {
            gab = new GanglionAndroidBluetooth(this);
            gab.initialize();
            ganglionThread = new Thread(gab);
            ganglionThread.start();
        }

        // TODO [x]: start the decoder service..
        // manual call to the python service
        if ( decoderThread == null){
            ServiceMindaffectbci decoder = new ServiceMindaffectbci();
            decoder.prepare(this.getApplication().getApplicationContext());
            //ServiceMindaffectbci.start(this.getApplication().getApplicationContext(), "");
            decoderThread = new Thread(decoder);
            decoderThread.start();
        }


    }

}
