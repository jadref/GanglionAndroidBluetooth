package com.mymind.gangliontablet.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import com.mymind.gangliontablet.bluetooth.DeviceScanActivity;
import com.mymind.gangliontablet.usermanagementlocal.LoginActivityLocal;


public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //TODO secure shared preferences
        SharedPreferences pref = getSharedPreferences("Login_status", Context.MODE_PRIVATE);

        // if the shared preference does not exist the user is not logged in => send him to the loginAcitivty
        try {
            if (pref.getBoolean("user_loggedIn", false)) {
                Log.d("Splash Activity", "User is logged in");
                final Intent intent = new Intent(this, DeviceScanActivity.class);
                startActivity(intent);
                finish();

            } else {
                Log.d("Splash Activity", "User is not logged in");
                final Intent intent = new Intent(this, LoginActivityLocal.class);
                startActivity(intent);
                finish();

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
