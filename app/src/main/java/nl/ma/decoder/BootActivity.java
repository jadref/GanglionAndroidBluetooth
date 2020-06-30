package nl.ma.decoder;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;


import java.io.File;
import java.net.SocketException;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

import nl.ma.utopiaserver.ServerUtopiaMessage;
import nl.ma.utopiaserver.UtopiaServer;

import nl.ma.utopiaserver.messages.DataPacket;
import nl.ma.utopiaserver.messages.SignalQuality;
import nl.ma.utopiaserver.messages.UtopiaMessage;


import android.net.wifi.WifiManager;

public class BootActivity extends Activity {

    private static final String TAG = BootActivity.class.getSimpleName();
    UtopiaServer utopiaServer;
    Thread serverThread = null;
    GanglionAndroidBluetooth gab = null;
    Thread ganglionThread = null;
    Thread decoderThread = null;
    List<Button> chBut = new LinkedList<>();
    List<LineView> chLineView = new LinkedList<>();
    TextView text_state = null;
    TextView device_ID = null;
    TextView hostIP = null;
    CountDownTimer chartUpdate = null;
    private int dataTrackingStart;
    private int nsample;
    WifiManager.MulticastLock multicastlock;

    Deque<DataPacket> dataringbuffer = new LinkedList<>();
    int datawindow_ms = 5000;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.acitivity_main);



        /* Turn off multicast filter */
        WifiManager wifi = (WifiManager) getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            multicastlock = wifi.createMulticastLock("gab_multicastlock");
            multicastlock.acquire();
        }

        //Window window = getWindow();
        //window.addOnFrameMetricsAvailableListener();

        // TODO []: move this to a service?
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
            gab.initialize(this);
            ganglionThread = new Thread(gab);
            ganglionThread.start();
        }

        // TODO [x]: start the decoder service..
        ServiceMindaffectbci.prepare(this.getApplication().getApplicationContext());
        ServiceMindaffectbci.start(this.getApplication().getApplicationContext(), "");

        // setup the links to the channel button and plots
        chBut.add((Button) findViewById(R.id.button));
        chBut.add((Button) findViewById(R.id.button2));
        chBut.add((Button) findViewById(R.id.button3));
        chBut.add((Button) findViewById(R.id.button4));
        chLineView.add((LineView)findViewById(R.id.view));
        chLineView.add((LineView)findViewById(R.id.view2));
        chLineView.add((LineView)findViewById(R.id.view3));
        chLineView.add((LineView)findViewById(R.id.view4));

        text_state = (TextView) findViewById(R.id.text_state);
        text_state.setText("disconnected - searching");
        device_ID = (TextView) findViewById(R.id.text_ID);
        device_ID.setText("searching");
        hostIP = (TextView) findViewById(R.id.text_host_ip);
        hostIP.setText(gethostIPs());
    }


    public void onResume() {
        super.onResume();
        chartUpdate = new CountDownTimer(15000, 250) {
            @Override
            public void onTick(long millisUntilFinished) {
                chartUpdateTick();
            }

            @Override
            public void onFinish() {
                // re-start when done.
                chartUpdate.start();
            }
        };
        chartUpdate.start();
        utopiaServer.flushInMessageQueue = false;
        // reset the sample tracking info
        nsample = -1;
    }

    public void onPause() {
        super.onPause();
        // stop updating the plots when not visible
        chartUpdate.cancel();
        utopiaServer.flushInMessageQueue = true;
    }

    public void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
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
            //ServiceMindaffectbci.stop();
        }
    }

    private void chartUpdateTick() {
        device_ID.setText(gab.getmGanglionBluetoothDeviceAddress());

        List<ServerUtopiaMessage> newmsgs = utopiaServer.popMessages();
        for (ServerUtopiaMessage svrmsg : newmsgs) {
            UtopiaMessage msg = svrmsg.clientmsg;
            if (msg.msgID() == SignalQuality.MSGID) {
                // update the signal-quality display
                onSignalQuality((SignalQuality) msg);
            } else if (msg.msgID() == DataPacket.MSGID) {
                // update the raw signal display
                onDataPacket((DataPacket) msg);
            }
        }
    }

    private void onSignalQuality(SignalQuality msg) {
        int chi = 0;
        Log.v(TAG, "SigQ:" + msg.toString());
        for (Button but : chBut) {
            if (chi >= msg.signalQuality.length)
                break;
            float noise2sig = msg.signalQuality[chi];
            double qual = Math.log10(noise2sig) / 2;  // n2s=50->1 n2s=10->.5 n2s=1->0
            qual = Math.max(0, Math.min(qual, 1)); // clip
            but.setBackgroundTintList(ColorStateList.valueOf(Color.rgb((float) qual, (float) (1.0f - qual), 0f)));
            but.setText(String.format("Ch%d\n%4.0f", chi, noise2sig));
            chi++;
        }
    }

    private void onDataPacket(DataPacket msg) {
        //Log.v(TAG,"onDataPacket"+msg.toString());
        int t = utopiaServer.gettimeStamp();
        if (nsample < 0) {
            nsample = 0;
            dataTrackingStart = t;
            dataringbuffer.clear();
        }

        // add to the data ring-buffer
        dataringbuffer.addLast(msg);
        if ( msg.timeStamp - dataringbuffer.peekFirst().timeStamp > datawindow_ms  ){
            dataringbuffer.removeFirst();
        }

        nsample = nsample + msg.nsamples;
        float elapsed = (t - dataTrackingStart) / 1000.0f;
        text_state.setText(String.format("%d / %5.3fs = %5.3fHz", nsample, elapsed, nsample / elapsed));

        // pre-compute number samples in buffer
        int nsamples=0;
        for ( DataPacket dp : dataringbuffer ) nsamples += dp.nsamples;
        float [] vals = new float[nsamples];
        int chi=0;
        for ( LineView chplt : chLineView ){
            // get this channels data
            int ti=0;
            for ( DataPacket dp : dataringbuffer ){
                float [][] samples = dp.getSamples();
                for ( int i=0; i<samples.length; i++, ti++){
                    vals[ti]=samples[i][chi];
                }
            }
            // update the view's line
            chplt.setLine(vals);
            chi++;
        }
    }

    public static class LineView extends View {
        float yscale=20;
        private Paint mPaint = null;
        private Path mPath = null;

        public LineView(Context context, AttributeSet attrs){
            super(context,attrs);
            mPaint = new Paint();
            mPaint.setColor(Color.BLACK);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(3.0f);
        }

        public void setLine(float[] line){
            mPath = new Path();
            int width = getMeasuredWidth();
            int height = getMeasuredHeight();
            float yscale = height*1.0f/this.yscale;
            float yoffset = height*.5f;
            float xscale = width*1.0f/line.length;
            float xoffset = 0f;

            // update the path with this line

            // compute average over last few samples to center the lines
            float ycenter = 0;
            int N = (int) (line.length*.10f);
            for ( int i=line.length-N; i<line.length; i++) {
                float li=line[i];
                ycenter += line[i];
            }
            ycenter = ycenter / N;

            float x=0;
            int i=0, j=0;
            for ( float y : line ){
                y = y - ycenter;
                if ( x == 0 ) { // starting point
                    mPath.moveTo( x * xscale + xoffset, y * yscale + yoffset);
                } else {
                    mPath.lineTo(x * xscale + xoffset, y * yscale + yoffset);
                }
                x++;
            }
            invalidate();
        }
        public void onDraw(Canvas canvas){
            super.onDraw(canvas);
            if ( mPath != null )
                canvas.drawPath(mPath,mPaint);
        }
    }




    public String gethostIPs() {
        System.out.println(TAG + " Full list of Utopia-server addresses");
        java.util.Enumeration<java.net.NetworkInterface> ifcs = null;
        StringBuilder hostIDs = new StringBuilder();
        try {
            ifcs = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifcs.hasMoreElements()) {
                java.net.NetworkInterface ifc = ifcs.nextElement();
                if (ifc.isUp()) {
                    java.util.Enumeration<java.net.InetAddress> addrs = ifc.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        java.net.InetAddress tmp = addrs.nextElement();
                        if (tmp instanceof java.net.Inet4Address) {
                            hostIDs.append(", " + tmp);
                        }
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return hostIDs.toString();
    }
}
