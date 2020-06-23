package nl.ma.decoder;

import android.app.Activity;
import android.app.Service;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;


import java.io.File;
import java.io.IOException;
import java.time.chrono.MinguoChronology;
import java.util.LinkedList;
import java.util.List;

import nl.ma.decoder.GanglionAndroidBluetooth;
import nl.ma.utopiaserver.ServerUtopiaMessage;
import nl.ma.utopiaserver.UtopiaServer;

import nl.ma.decoder.ServiceMindaffectbci;
import nl.ma.utopiaserver.messages.DataPacket;
import nl.ma.utopiaserver.messages.SignalQuality;
import nl.ma.utopiaserver.messages.UtopiaMessage;

public class BootActivity extends Activity {

    private static final String TAG = BootActivity.class.getSimpleName();
    UtopiaServer utopiaServer;
    Thread serverThread =  null;
    GanglionAndroidBluetooth gab=null;
    Thread ganglionThread= null;
    Thread decoderThread = null;
    List<Button> chBut   = new LinkedList<Button>();
    List<View> chart     = new LinkedList<View>();
    TextView text_state = null;
    TextView device_ID = null;
    CountDownTimer chartUpdate=null;
    private int dataTrackingStart;
    private int nsample;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.acitivity_main);

        // TODO []: move this to a service?
        // TODO [x]: tell it where to save the logs...

        if ( utopiaServer == null ) {
            File external = getExternalFilesDir(null);
            String dataFile = null;
            if (external != null && external.canWrite()) {
                dataFile = external.getParent() + "/mindaffectBCI.txt";
            }
            utopiaServer = new UtopiaServer();
            utopiaServer.initialize(-1, dataFile);
            serverThread = utopiaServer.startServerThread(Thread.NORM_PRIORITY + 2, false);
        }

        if ( gab == null ) {
            gab = new GanglionAndroidBluetooth(this);
            gab.initialize(this);
            ganglionThread = new Thread(gab);
            ganglionThread.start();
        }

        // TODO [x]: start the decoder service..
        ServiceMindaffectbci.prepare(this.getApplication().getApplicationContext());
        ServiceMindaffectbci.start(this.getApplication().getApplicationContext(),"");

        // setup the links to the channel button and plots
        chBut.add((Button) findViewById(R.id.button));
        chBut.add((Button) findViewById(R.id.button2));
        chBut.add((Button) findViewById(R.id.button3));
        chBut.add((Button) findViewById(R.id.button4));
        chart.add(findViewById(R.id.view));
        chart.add(findViewById(R.id.view2));
        chart.add(findViewById(R.id.view3));
        chart.add(findViewById(R.id.view4));

        text_state = (TextView) findViewById(R.id.text_state);
        text_state.setText("disconnected - searching");
        device_ID = (TextView) findViewById(R.id.text_ID);
        device_ID.setText("searching");
    }


    public void onResume() {
        super.onResume();
        chartUpdate = new CountDownTimer(15000,250) {
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
        utopiaServer.flushInMessageQueue=false;
        // reset the sample tracking info
        nsample=-1;
    }

    public void onPause(){
        super.onPause();
        // stop updating the plots when not visible
        chartUpdate.cancel();
        utopiaServer.flushInMessageQueue=true;
    }

    public void onDestroy(){
        super.onDestroy();
        if ( isFinishing() ) {
            // only cleanup if we are actually being destroyed!, i.e. not on rotation.
            Log.v(TAG, "Killing client threads");
            if (ganglionThread != null) {
                ganglionThread.interrupt();
            }
            if (serverThread != null) {
                serverThread.interrupt();
            }
            // TODO[] does this work?  A: no!
            //ServiceMindaffectbci.stop();
        }
    }

    private void chartUpdateTick() {
        device_ID.setText(gab.getmBluetoothDeviceAddress());

        List<ServerUtopiaMessage> newmsgs = utopiaServer.popMessages();
        for ( ServerUtopiaMessage svrmsg : newmsgs ){
            UtopiaMessage msg = svrmsg.clientmsg;
            if ( msg.msgID() == SignalQuality.MSGID ){
                // update the signal-quality display
                onSignalQuality((SignalQuality)msg);
            } else if ( msg.msgID() == DataPacket.MSGID ){
                // update the raw signal display
                onDataPacket((DataPacket)msg);
            }
        }
    }

    private void onSignalQuality(SignalQuality msg) {
        int chi=0;
        Log.v(TAG,"SigQ:"+msg.toString());
        for ( Button but : chBut ) {
            if ( chi >= msg.signalQuality.length )
                break;
            float noise2sig = msg.signalQuality[chi];
            double qual = Math.log10(noise2sig)/2;  // n2s=50->1 n2s=10->.5 n2s=1->0
            qual = Math.max(0, Math.min(qual, 1)); // clip
            but.setBackgroundTintList(ColorStateList.valueOf(Color.rgb((float)qual,(float)(1.0f-qual),0f)));
            but.setText(String.format("Ch%d\n%4.0f",chi,qual));
            chi++;
        }
    }

    private void onDataPacket(DataPacket msg) {
        //Log.v(TAG,"onDataPacket"+msg.toString());
        if ( nsample < 0 ){
            nsample = 0;
            dataTrackingStart=utopiaServer.gettimeStamp();
        }
        nsample = nsample + msg.nsamples;
        float elapsed = (utopiaServer.gettimeStamp() - dataTrackingStart)/1000.0f;
        text_state.setText(String.format("%d / %5.3fs = %5.3fHz",nsample,elapsed,nsample/elapsed));

        //TODO[] draw a live plot?
    }
}
