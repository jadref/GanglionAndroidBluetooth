package com.mymind.gangliontablet.usermanagementlocal;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.mymind.gangliontablet.R;
import com.mymind.gangliontablet.bluetooth.DeviceScanActivity;


public class LoginActivityLocal extends AppCompatActivity {

    private String id;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login_local);


        // Login Button
        final Button login_button = (Button) findViewById(R.id.button_login);
        login_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText inputID= (EditText) findViewById(R.id.text_ID);
                id = inputID.getText().toString();
                //The personal ID should be 3 digits long
                if(false) {
                    new AlertDialog.Builder(LoginActivityLocal.this)
                            .setTitle("Incomplete Input")
                            .setMessage("Please enter your 3 digit ID number")
                            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {

                                }
                            })
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .show();
                }
                else{

                    new AlertDialog.Builder(LoginActivityLocal.this)
                            .setTitle("Terms of use")
                            .setMessage(" TOS come here")
                            .setPositiveButton("Accept", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    Log.d("LoginActivityLocal", "ToS accepted");
                                    //Upon accepting save the User ID and change the user login status and start the openbci.ganglion.main activity
                                    SharedPreferences login_status = getSharedPreferences("Login_status", Context.MODE_PRIVATE);
                                    SharedPreferences.Editor editor = login_status.edit();
                                    editor.putBoolean("user_loggedIn", true);
                                    editor.putString("userID", id);
                                    editor.apply();

                                    //TODO
                                    final Intent MainActivity= new Intent(LoginActivityLocal.this, DeviceScanActivity.class);
                                    startActivity(MainActivity);
                                    finish();
                                }
                            })
                            .setNegativeButton("Decline", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    Log.d("LoginActivityLocal", "ToS declined");
                                    //Without accepting the Activity will finish
                                    finish();
                                }
                            })
                            //.setIcon(android.R.drawable.ic_dialog_alert)
                            .show();


                }
            }
        });

    }
}
