/*
 * Copyright (C) 2013 The Android Open Source Project
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

 // TODO []: This is horrible !  needs to be refactored into smaller bits, e.g. per-service classes.  Ganglion class, Decoder Class etc.



package nl.ma.decoder;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;

import static android.bluetooth.BluetoothGattCharacteristic.FORMAT_UINT8;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE;
import static android.bluetooth.BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
import static android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
import static android.bluetooth.BluetoothGattDescriptor.PERMISSION_READ;
import static android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE;
import static android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY;
import static android.support.v4.app.ActivityCompat.requestPermissions;

import nl.ma.utopiaserver.ClientException;
import nl.ma.utopiaserver.UtopiaClient;
import nl.ma.utopiaserver.messages.DataPacket;
import nl.ma.utopiaserver.messages.PredictedTargetDist;
import nl.ma.utopiaserver.messages.PredictedTargetProb;
import nl.ma.utopiaserver.messages.RawMessage;
import nl.ma.utopiaserver.messages.Selection;
import nl.ma.utopiaserver.messages.StimulusEvent;
import nl.ma.utopiaserver.messages.OutputScore;
import nl.ma.utopiaserver.messages.UtopiaMessage;

public class GanglionAndroidBluetooth implements Runnable {

    private final static String TAG = GanglionAndroidBluetooth.class.getSimpleName();
    private final Context mContext;

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    // list of reg-exps for devices we would connect to
    private String[] validNameRegexps = new String[]{"[Gg]anglion.*","[Pp]resentation.*","mindaffect.*","[sS]core[oO]utput.*"};

    public enum BLESTATE { IDLE, CONNECTING, CONNECTED, WRITING, READING, DISCOVERING, DISCOVERED, DISCONNECTING, DISCONNECTED, NEW, IGNORED };
    private BLESTATE mBluetoothState = BLESTATE.IDLE;
    private final ReentrantLock mBluetoothLock = new ReentrantLock();
    private BluetoothGatt mBluetoothGatt; // currently connected GATT
    private BluetoothLeScanner mBluetoothLeScanner;

    // Gatt devices we scan for and maintain a list of connections to..
    private GANGLIONSTATE mGanglionConnectionState = GANGLIONSTATE.DISCONNECTED;
    private String mGanglionBluetoothDeviceAddress;
    private BluetoothGatt mGanglionBluetoothGatt;
    private List<BluetoothGatt> mOutputBluetoothGatt;
    private List<BluetoothGatt> mPresentationBluetoothGatt;
    private BluetoothGatt mScoreOutputBluetoothGatt;

    // Server for services we provide, i.e. DECODER, OUTPUT
    private BluetoothGattServer mBluetoothGattServer;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    private List<BluetoothDevice> mRegisteredDecoderDevices = new ArrayList<>();
    private PredictedTargetProb mPredictedTargetProb = new PredictedTargetProb(-1,-1,1.0f);
    private PredictedTargetDist mPredictedTargetDist = new PredictedTargetDist(-1,new int[]{},new float []{});
    private Selection mSelection = new Selection(-1,0);
    private List<BluetoothDevice> mRegisteredOutputDevices;
    private ByteBuffer tmp = ByteBuffer.allocateDirect(8192);

    // N.B. use concurrent version as may modify from mulitple threads!
    ConcurrentHashMap<BluetoothDevice, BLESTATE> knowDevices = new ConcurrentHashMap<>();

    public static enum GANGLIONSTATE  { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, DISCOVERING, DISCOVERED, REGISTERING, REGISTERED, READING };

    public  static final int PERMISSIONS_MULTIPLE_REQUEST = 123;

    public final static UUID UUID_CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    //Ganglion Service/Characteristics UUIDs (SIMBLEE Chip Defaults)
    public final static String DEVICE_NAME_GANGLION = "GANGLION";
    public final static UUID UUID_GANGLION_SERVICE = UUID.fromString("0000fe84-0000-1000-8000-00805f9b34fb");
    public final static UUID UUID_GANGLION_RECEIVE = UUID.fromString("2d30c082-f39f-4ce6-923f-3484ea480596");
    public final static UUID UUID_GANGLION_SEND = UUID.fromString("2d30c083-f39f-4ce6-923f-3484ea480596");
    public final static UUID UUID_GANGLION_DISCONNECT = UUID.fromString("2d30c084-f39f-4ce6-923f-3484ea480596");

    // mindaffectBCI Service/Characteristic UUIDs
    public final static UUID UUID_PRESENTATION_SERVICE = UUID.fromString("d3560000-b9ff-11ea-b3de-0242ac130004");
    public final static UUID UUID_PRESENTATION_STIMULUSSTATE = UUID.fromString("d3560001-b9ff-11ea-b3de-0242ac130004");

    public final static UUID UUID_DECODER_SERVICE = UUID.fromString("d3560100-b9ff-11ea-b3de-0242ac130004");
    public final static UUID UUID_DECODER_PREDICTEDTARGETPROB = UUID.fromString("d3560101-b9ff-11ea-b3de-0242ac130004");
    public final static UUID UUID_DECODER_PREDICTEDTARGETDIST = UUID.fromString("d3560102-b9ff-11ea-b3de-0242ac130004");
    public final static UUID UUID_DECODER_SELECTION = UUID.fromString("d3560103-b9ff-11ea-b3de-0242ac130004");

    //public final static UUID UUID_OUTPUT_SERVICE = UUID.fromString("d3560200-b9ff-11ea-b3de-0242ac130004");

    public final static UUID UUID_SCOREOUTPUT_SERVICE = UUID.fromString("d3560300-b9ff-11ea-b3de-0242ac130004");
    public final static UUID UUID_SCOREOUTPUT_OUTPUTSCORE = UUID.fromString("d3560301-b9ff-11ea-b3de-0242ac130004");
    public final static UUID UUID_SCOREOUTPUT_CURRENTMODEL = UUID.fromString("d3560302-b9ff-11ea-b3de-0242ac130004");
    public final static UUID UUID_SCOREOUTPUT_CURRENTSOSFILTER = UUID.fromString("d3560303-b9ff-11ea-b3de-0242ac130004");

    //Device names

    private static int last_id;
    private static int[] lastChannelData = {0, 0, 0, 0};
    private static int[] lastAccelerometer = {0, 0, 0};
    private static int[] lastImpedance = {0, 0, 0, 0, 0};
    private static int[] sample_id = {0, 0};
    private static int[][] fullData = {{0, 0, 0, 0}, {0, 0, 0, 0}};
    private static int packetID;
    private static int lostPackets = 0;
    private static int totalPackets = 0;

    private static int counter = 0;
    private static boolean accSampleComplete = false;

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                if( mBluetoothState != BLESTATE.CONNECTING){
                    Log.v(TAG,"Huh! got connection when not in connecting state!");
                }
                mBluetoothState = BLESTATE.CONNECTED;
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery");
                knowDevices.put(gatt.getDevice(),BLESTATE.DISCOVERING);
                mBluetoothState = BLESTATE.DISCOVERING;
                gatt.discoverServices();
                if ( mGanglionBluetoothGatt == null ) {
                    mGanglionConnectionState = GANGLIONSTATE.DISCOVERING;
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // TODO[]: Check that the gatt we're trying to connect to is the one that disconnected!
                if ( mBluetoothState != BLESTATE.DISCONNECTING ) {
                    Log.i(TAG, "Disconnected while in another state");
                }
                mBluetoothState = BLESTATE.IDLE;
                Log.i(TAG, "Disconnected from GATT server.");
                if ( gatt == mGanglionBluetoothGatt ) {
                    // only update state if ganglion connection state changed.
                    mGanglionConnectionState = GANGLIONSTATE.DISCONNECTED;
                }
                knowDevices.put(gatt.getDevice(),BLESTATE.DISCONNECTED);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if( mBluetoothState != BLESTATE.DISCOVERING ){
                Log.v(TAG,"Huh!, discovered not in discovering state?");
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mBluetoothState = BLESTATE.DISCOVERED;
                Log.v(TAG, "GattServer Services Discovered");
                // register to notify on the SEND and RECEIVE characteristics
                setupServicesAndNotifications(gatt);
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
                mBluetoothState = BLESTATE.IDLE;
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, final int status) {
            synchronized (mBluetoothState) {
                if (mBluetoothState != BLESTATE.WRITING) {
                    Log.v(TAG, "Warning: on write when not writing?");
                }
                BluetoothGattCharacteristic parentCharacteristic = descriptor.getCharacteristic();
                // do work after notify is setup
                UUID gattServiceUUID = parentCharacteristic.getService().getUuid();
                mBluetoothState = BLESTATE.IDLE;
                if (UUID_GANGLION_SERVICE.equals(gattServiceUUID)) {
                    onGanglionRegistered();
                } else if (UUID_PRESENTATION_SERVICE.equals(gattServiceUUID)) {
                    onPresentationRegistered();
                } else if (UUID_SCOREOUTPUT_SERVICE.equals(gattServiceUUID)) {
                    onScoreOutputRegistered();
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            Log.w(TAG, "Written to: " + characteristic.getUuid() + " Status: " + (gatt.GATT_SUCCESS == status));
            if ( mBluetoothState != BLESTATE.WRITING ){
                Log.v(TAG,"Warning: on write when not writing?");
            }
            mBluetoothState=BLESTATE.IDLE;
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if ( mBluetoothState != BLESTATE.READING ){
                Log.v(TAG,"Warning: on write when not writing?");
            }
            mBluetoothState = BLESTATE.IDLE;
            Log.v(TAG, "Characteristic read");
            UUID characteristicUUID = characteristic.getUuid();
            if (gatt == mGanglionBluetoothGatt && status == BluetoothGatt.GATT_SUCCESS && UUID_GANGLION_RECEIVE.equals(characteristicUUID) ) {
                mGanglionConnectionState = GANGLIONSTATE.READING;
                actionDataAvailable(characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            //Log.v(TAG,"Characteristic changed");
            UUID characteristicUUID = characteristic.getUuid();
            if (gatt == mGanglionBluetoothGatt && UUID_GANGLION_RECEIVE.equals(characteristicUUID)) {
                mGanglionConnectionState = GANGLIONSTATE.READING;
                actionDataAvailable(characteristic);
            } else if ( UUID_PRESENTATION_STIMULUSSTATE.equals(characteristicUUID)){
                actionStimulusStateAvailable(characteristic);
            } else if ( UUID_SCOREOUTPUT_OUTPUTSCORE.equals(characteristicUUID)){
                actionOutputScoreAvailable(characteristic);
            }
        }
    };

    // keep list of devices we've seen so we don't connect multiple times...
    private final ScanCallback mLeScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            Log.wtf(TAG, "Scan Result" + result.toString());
            BluetoothDevice device = result.getDevice();
            if (knowDevices.containsKey(device)) {
                // skip known devices
                return;
            }
            // filter for devices we care about.
            String devname = device.getName();
            boolean validName = false;
            if (devname != null){
                for (String regexp : validNameRegexps) {
                    if (devname.matches(regexp)) {
                        validName = true;
                        break;
                    }
                }
            }
            if ( validName ) {
                // register this device as new device
                knowDevices.put(device, BLESTATE.NEW);
            } else {
                knowDevices.put(device, BLESTATE.IGNORED);
            }
        }
    };

    void setupServicesAndNotifications(BluetoothGatt gatt) {
        if( mBluetoothState != BLESTATE.DISCOVERED ) {
            Log.v(TAG,"Warning: service setup when not discovered?");
        }
        // register to notify on the data received characteristic
        boolean keepconnection=false;
        for (BluetoothGattService gattService : gatt.getServices()) {
            UUID gattServiceUUID = gattService.getUuid();
            // skip non ganglion services
            if (UUID_GANGLION_SERVICE.equals(gattServiceUUID)) {
                // setup the rest of the services
                onGanglionDiscovered(gatt, gattService);
                keepconnection=true;
            } else if (UUID_PRESENTATION_SERVICE.equals(gattServiceUUID)){
                onPresentationDiscovered(gatt, gattService);
                keepconnection=true;
            } else if (UUID_SCOREOUTPUT_SERVICE.equals(gattServiceUUID)){
                onScoreOutputDiscovered(gatt, gattService);
                keepconnection=true;
            }
        }
        if ( keepconnection == false ){
            // disconnect from a device which has nothing of interest to us
            gatt.disconnect();
            knowDevices.put(gatt.getDevice(),BLESTATE.DISCONNECTED);
            mBluetoothState = BLESTATE.IDLE;
        }
    }

    private void onScoreOutputDiscovered(BluetoothGatt gatt, BluetoothGattService gattService) {
        if ( mScoreOutputBluetoothGatt == gatt ){
            Log.v(TAG,"Got scan result for device already connected to!");
            return;
        }
        if ( mScoreOutputBluetoothGatt != null ){
            Log.v(TAG,"Already connected to score output module!");
            mScoreOutputBluetoothGatt.disconnect();
        }
        // register as device we're connected to
        mScoreOutputBluetoothGatt=gatt;
        // find the RECEIVE characteristic and register for change notifications
        for (BluetoothGattCharacteristic gattCharacteristic : gattService.getCharacteristics()) {
            UUID uuid = gattCharacteristic.getUuid();
            if (UUID_SCOREOUTPUT_OUTPUTSCORE.equals(uuid)) {
                // register for notifications on this characteristic
                setCharacteristicNotification(gatt, gattCharacteristic,true);
            }
        }
    }

    private void onPresentationDiscovered(BluetoothGatt gatt, BluetoothGattService gattService) {
        // TODO: check if already connected!

        // register as device we're connected to
        mPresentationBluetoothGatt.add(gatt);
        // find the RECEIVE characteristic and register for change notifications
        for (BluetoothGattCharacteristic gattCharacteristic : gattService.getCharacteristics()) {
            UUID uuid = gattCharacteristic.getUuid();
            //if this is the read attribute for Cyton/Ganglion, register for notify service
            if (UUID_PRESENTATION_STIMULUSSTATE.equals(uuid)) {
                setCharacteristicNotification(gatt, gattCharacteristic, true);
            }
        }
    }

    void onGanglionDiscovered(BluetoothGatt gatt, BluetoothGattService gattService){
        if ( mGanglionBluetoothGatt == gatt ){
            Log.v(TAG,"Got scan result for device already connected to!");
            return;
        }
        if ( mGanglionBluetoothGatt != null ){
            Log.v(TAG,"Already connected to score output module!");
            mGanglionBluetoothGatt.disconnect();
        }
        mGanglionBluetoothGatt = gatt;
        mGanglionBluetoothDeviceAddress = gatt.getDevice().getName() + " (" + gatt.getDevice().getAddress() +")";

        // request high connection priority so we don't drop too many samples
        if ( mBluetoothState != BLESTATE.DISCOVERED ){
            Log.v(TAG,"Warning: trying to setup ganglion when not in discovered state");
        }
        mBluetoothState = BLESTATE.WRITING;
        gatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH);
        mGanglionConnectionState = GANGLIONSTATE.REGISTERING;

        // find the RECEIVE characteristic and register for change notifications
        for (BluetoothGattCharacteristic gattCharacteristic : gattService.getCharacteristics()) {
            UUID uuid = gattCharacteristic.getUuid();
            if (UUID_GANGLION_RECEIVE.equals(uuid)) {
                // setup to get notify on this characteristic
                setCharacteristicNotification(gatt, gattCharacteristic, true);
            }
        }


        // stop scanning when got the ganglion..
        // reset to less intense scanning mode -- to free up the BLE stack.

        Log.v(TAG, "Stopping scanning");
        mBluetoothLeScanner.stopScan(mLeScanCallback);
        mBluetoothLeScanner.flushPendingScanResults(mLeScanCallback);
        ScanFilter.Builder sfb = new ScanFilter.Builder();
        ScanSettings.Builder ssb = new ScanSettings.Builder();
        ssb.setScanMode(ScanSettings.SCAN_MODE_OPPORTUNISTIC);
        ssb.setReportDelay(0);
        mBluetoothLeScanner.startScan(null, ssb.build(), mLeScanCallback);

    }

    private void onScoreOutputRegistered() {
        // post score output registered.
    }

    private void onPresentationRegistered() {
        // post registration work? write enabled?
    }

    private void onGanglionRegistered() {
        // setup the rest of the services
        mGanglionConnectionState = GANGLIONSTATE.REGISTERED;
        sendData(true);
    }



    //------------------------ BLE connection management....

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public GanglionAndroidBluetooth(Context ctx) {
        mContext = ctx;
    }

    public boolean initialize() {
        return initialize(null);
    }
    public boolean initialize(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (activity != null && mContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_DENIED) {
                requestPermissions(activity, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSIONS_MULTIPLE_REQUEST);
            }
        }

            // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        if (!mBluetoothAdapter.isEnabled()) {
            Log.v(TAG, "Enabling BLE adapter");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            mContext.startActivity(enableBtIntent);//, REQUEST_ENABLE_BT);
        }

        return true;
    }

    public boolean scanAndAutoconnect() {
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "BluetoothAdapter not initialized.");
            return false;
        }
        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (mBluetoothLeScanner == null) {
            Log.w(TAG, "BluetoothScanner not initialized.");
            return false;
        }

        mGanglionConnectionState = GANGLIONSTATE.SCANNING;
        //mBluetoothLeScanner.startScan( mLeScanCallback );
        // scan only for ganglions & connect to first one  we  find.
        ScanFilter.Builder sfb = new ScanFilter.Builder();
        //sfb.setServiceUuid(new ParcelUuid(UUID_GANGLION_SERVICE));
        ScanSettings.Builder ssb = new ScanSettings.Builder();
        ssb.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);//SCAN_MODE_BALANCED);//
        ssb.setReportDelay(0);
        mBluetoothLeScanner.startScan(Collections.singletonList(sfb.build()), ssb.build(), mLeScanCallback);
        return true;
    }


    private BluetoothGattService createService() {
        BluetoothGattService service = new BluetoothGattService(UUID_DECODER_SERVICE, SERVICE_TYPE_PRIMARY);

        // PredictedTargetProbability characteristic (read-only, supports subscriptions)
        BluetoothGattCharacteristic ptp = new BluetoothGattCharacteristic(UUID_DECODER_PREDICTEDTARGETPROB, PROPERTY_READ | PROPERTY_NOTIFY, PERMISSION_READ);
        BluetoothGattDescriptor ptpConfig = new BluetoothGattDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG, PERMISSION_READ | PERMISSION_WRITE);
        ptp.addDescriptor(ptpConfig);
        service.addCharacteristic(ptp);

        // PredictedTargetProbability characteristic (read-only, supports subscriptions)
        BluetoothGattCharacteristic ptd = new BluetoothGattCharacteristic(UUID_DECODER_PREDICTEDTARGETDIST, PROPERTY_READ | PROPERTY_NOTIFY, PERMISSION_READ);
        BluetoothGattDescriptor ptdConfig = new BluetoothGattDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG, PERMISSION_READ | PERMISSION_WRITE);
        ptd.addDescriptor(ptdConfig);
        service.addCharacteristic(ptd);

        // Selection characteristic (read-only, supports subscriptions)
        BluetoothGattCharacteristic sel = new BluetoothGattCharacteristic(UUID_DECODER_SELECTION, PROPERTY_READ | PROPERTY_NOTIFY | PROPERTY_WRITE, PERMISSION_READ);
        BluetoothGattDescriptor selConfig = new BluetoothGattDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG, PERMISSION_READ | PERMISSION_WRITE);
        sel.addDescriptor(selConfig);
        service.addCharacteristic(sel);

        return service;
    }


    private boolean initServices() {
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "BluetoothAdapter not initialized.");
            return false;
        }

        mBluetoothGattServer = mBluetoothManager.openGattServer(mContext, mGattServerCallback);
        if ( mBluetoothGattServer == null ) {
            Log.e(TAG,"Couldn't allocate a BLE gatt server!");
            return false;
        }
        BluetoothGattService magattservice = createService();
        if( magattservice == null ) {
            Log.e(TAG,"Couldn't create the gatt service!");
            return false;
        }
        mBluetoothGattServer.addService(magattservice);

        // Advertise that we provide a DECODER service!
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(UUID_DECODER_SERVICE))
                .build();
        // Starts advertising.
        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        mBluetoothLeAdvertiser.startAdvertising(settings, data, mAdvertiseCallback);

        return true;
    }

    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "LE Advertise Started.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "LE Advertise Failed: " + errorCode);
        }
    };

    private final BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

        // respond to read request
        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device,
                                                int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device,requestId,offset,characteristic);
            if (UUID_DECODER_PREDICTEDTARGETDIST.equals(characteristic.getUuid())) {
                mPredictedTargetDist.serialize(tmp);
                mBluetoothGattServer.sendResponse(device, requestId, GATT_SUCCESS, tmp.position(), tmp.array());
            } else if (UUID_DECODER_PREDICTEDTARGETPROB.equals(characteristic.getUuid())) {
                mPredictedTargetProb.serialize(tmp);
                mBluetoothGattServer.sendResponse(device, requestId, GATT_SUCCESS, tmp.position(), tmp.array());
            } else if (UUID_DECODER_SELECTION.equals(characteristic.getUuid())) {
                mBluetoothGattServer.sendResponse(device, requestId, GATT_SUCCESS, 0, new byte[]{(byte)mSelection.objID});
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
            if (UUID_DECODER_SELECTION.equals(characteristic.getUuid())) {
                mSelection.objID = characteristic.getIntValue(FORMAT_UINT8,0);
                onSelectionWrite();
            }
        }

        // reply to notification request
        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device,
                                             int requestId, BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            // Q: how does it work when register on one characteristic but not others?
            if (UUID_CLIENT_CHARACTERISTIC_CONFIG.equals(descriptor.getUuid())) {
                if (Arrays.equals(ENABLE_NOTIFICATION_VALUE, value)) {
                    mRegisteredDecoderDevices.add(device);
                } else if (Arrays.equals(DISABLE_NOTIFICATION_VALUE, value)) {
                    mRegisteredDecoderDevices.remove(device);
                }
                if (responseNeeded && mBluetoothGattServer != null ) {
                    mBluetoothGattServer.sendResponse(device, requestId, GATT_SUCCESS, 0, null);
                }
            }
        }
    };

    private void notifyPredictedTargetProb(PredictedTargetProb ptp) {
        if ( mBluetoothGattServer == null ) return;
        BluetoothGattCharacteristic characteristic = mBluetoothGattServer
                .getService(UUID_DECODER_SERVICE)
                .getCharacteristic(UUID_DECODER_PREDICTEDTARGETPROB);

        ptp.serialize(tmp);
        characteristic.setValue(tmp.array());
        for (BluetoothDevice device : mRegisteredDecoderDevices) {
            mBluetoothGattServer.notifyCharacteristicChanged(device, characteristic, false);
        }
        this.mPredictedTargetProb=ptp;
    }

    private void notifyPredictedTargetDist(PredictedTargetDist ptd) {
        BluetoothGattCharacteristic characteristic = mBluetoothGattServer
                .getService(UUID_DECODER_SERVICE)
                .getCharacteristic(UUID_DECODER_PREDICTEDTARGETDIST);

        // TODO[]: pack the distribution message over multiple BLE packets!
        ptd.serialize(tmp);
        characteristic.setValue(tmp.array());
        for (BluetoothDevice device : mRegisteredDecoderDevices) {
            mBluetoothGattServer.notifyCharacteristicChanged(device, characteristic, false);
        }
        this.mPredictedTargetDist=ptd;
    }

    private void notifySelection(Selection sel) {
        BluetoothGattCharacteristic characteristic = mBluetoothGattServer
                .getService(UUID_DECODER_SERVICE)
                .getCharacteristic(UUID_DECODER_SELECTION);

        characteristic.setValue(new byte[]{(byte)sel.objID});
        for (BluetoothDevice device : mRegisteredDecoderDevices) {
            mBluetoothGattServer.notifyCharacteristicChanged(device, characteristic, false);
        }
        mSelection=sel;
    }

    private void onSelectionWrite() {
        // write a BLE selection to the rest of the system
        if (utopiaClient == null || !utopiaClient.isConnected()) {
            System.out.println("Warning: UtopiaClient isn't connected");
            return;
        }
        try{
            utopiaClient.sendMessage(mSelection);
        } catch ( IOException ex ){
            Log.v(TAG,"Error writing selection info");
        }
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     * @return Return true if the connection is initiated successfully. The connection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }

        Log.v(TAG, "Connecting to GATT Server on the Device");
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        if ( mBluetoothState != BLESTATE.IDLE ){
            Log.e(TAG,"Error: trying to connect when not idle");
        }
        mBluetoothState = BLESTATE.CONNECTING;
        mBluetoothGatt = device.connectGatt(this.mContext, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
        Log.d(TAG, "Trying to create a new connection.");
        return true;
    }


    public Thread startBackgroundThread() {
        // spawn data acq background thread..
        Thread thread = new Thread(this);
        thread.start();
        return thread;
    }

    @Override
    public void run() {
        initialize(null);
        initServices();
        scanAndAutoconnect();
        utopiaAutoConnect();

        boolean running=true;
        while (running) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                running=false;
            }
            if ( Thread.currentThread().isInterrupted() ){
                running=false;
            }

            if ( mBluetoothState == BLESTATE.IDLE) {
                // connect to new devices to check if they provide services we should know about
                for ( BluetoothDevice d : knowDevices.keySet() ){
                    if ( knowDevices.get(d) == BLESTATE.NEW) {
                        connect(d.getAddress());
                        break;
                    }
                }
            }

            // server notify on outputs from us.
            try {
                List<UtopiaMessage> msgs = utopiaClient.getNewMessages();
                for ( UtopiaMessage msg : msgs ){
                    if ( msg.msgID() == PredictedTargetProb.MSGID ){
                        notifyPredictedTargetProb((PredictedTargetProb)msg);
                    } else if ( msg.msgID() == PredictedTargetDist.MSGID ){
                        notifyPredictedTargetDist((PredictedTargetDist)msg);
                    } else if ( msg.msgID() == Selection.MSGID ){
                        notifySelection((Selection)msg);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // shutdown cleanly?
        close();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        mGanglionBluetoothGatt.disconnect();
        mGanglionBluetoothGatt.close();
        mGanglionBluetoothGatt = null;
        if ( mScoreOutputBluetoothGatt != null ) {
            mScoreOutputBluetoothGatt.disconnect();
            mScoreOutputBluetoothGatt.close();
        }
        for ( BluetoothGatt gatt : mPresentationBluetoothGatt ){
            gatt.disconnect();
            gatt.close();
        }
        for ( BluetoothGatt gatt : mOutputBluetoothGatt ){
            gatt.disconnect();
            gatt.close();
        }
        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
        mBluetoothGattServer.close();
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void writeCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        //pre-prepared characteristic to write to
        if (mBluetoothAdapter == null || gatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        Log.w(TAG, "Writing to " + characteristic.getUuid());

        if ( mBluetoothState != BLESTATE.IDLE ){
            Log.v(TAG, "Warning: switching state when not idle!");
        }

        knowDevices.put(gatt.getDevice(),BLESTATE.WRITING);
        mBluetoothState = BLESTATE.WRITING;
        gatt.writeCharacteristic(characteristic);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled        If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGatt gatt,
                                              BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        Log.w(TAG, characteristic.getUuid() + " - Notify:" + enabled);
        if (mBluetoothAdapter == null || mGanglionBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        Log.v(TAG, "Set notify on");
        if ( mBluetoothState != BLESTATE.IDLE ){
            Log.v(TAG, "Warning: switching state when not idle!");
        }
        mBluetoothState = BLESTATE.WRITING;
        knowDevices.put(gatt.getDevice(),BLESTATE.WRITING);
        gatt.setCharacteristicNotification(characteristic, enabled);

        Log.v(TAG, "set descriptor");
        BluetoothGattDescriptor descriptor =
               characteristic.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG);
        descriptor.setValue(ENABLE_NOTIFICATION_VALUE);
        gatt.writeDescriptor(descriptor);
    }

    public String getmGanglionBluetoothDeviceAddress() {
        return mGanglionBluetoothDeviceAddress;
    }

    // --------------------------  Connection to utopia
    UtopiaClient utopiaClient = null;

    public boolean utopiaAutoConnect()  {
        if (utopiaClient == null) {
            utopiaClient = new UtopiaClient();
        }
        if (!utopiaClient.isConnected()) {
            try {
                utopiaClient.connect("localhost");
            } catch ( IOException ex) {
                System.out.println("Error autoconnecting!");
            }
        }
        return utopiaClient.isConnected();
    }

    // conversion factors
    static final int PACKETSIZE = 10;
    static final float SCALE_FACTOR_EEG = (float) (1e6f / (1.2 * 8388607.0 * 1.5 * 51.0)); //uV/count
    static final float SCALE_FACTOR_AUX = 0.032f;
    // logging tracking
    int t0 = -1, nSamp = 0, nBlk = 0, log_time = -1;
    DataPacket datapacket = null;

    private void fillAndSendDataPacket(int[] sample) throws IOException {
        if ( nSamp == 0 && t0==-1 ){
            t0 = utopiaClient.gettimeStamp();
            log_time = t0;
        }
        if (utopiaClient == null || !utopiaClient.isConnected()) {
            System.out.println("Warning: UtopiaClient isn't connected");
            return;
        }
        if (datapacket == null) {
            datapacket = new DataPacket(-1, PACKETSIZE, sample.length);
            datapacket.nsamples = 0; // set number valid samples in the packet
        }
        if (sample.length != datapacket.nchannels) {
            System.out.println("Warning: number channels changed?");
            datapacket = new DataPacket(-1, PACKETSIZE, sample.length);
            datapacket.nsamples = 0; // set number valid samples in the packet
        }

        // add into the data packet
        int packet_nsamples = datapacket.nchannels * datapacket.nsamples;
        for (int i = 0; i < sample.length; i++, packet_nsamples++) {
            datapacket.samples[packet_nsamples] = ((float)sample[i]) * SCALE_FACTOR_EEG;
        }
        datapacket.nsamples = datapacket.nsamples + 1;

        // send to server if no room for more samples
        if (datapacket.nsamples * datapacket.nchannels > datapacket.samples.length - datapacket.nchannels) {
            datapacket.settimeStamp(utopiaClient.gettimeStamp()); // ensure valid timestamp
            utopiaClient.sendMessage(datapacket);
            //Log.v(TAG,datapacket.toString());
            nBlk += 1;
            nSamp += datapacket.nsamples;
            // reset the sample count in the sending packet
            datapacket.nsamples = 0;
        }

        // progress logging
        int ts = utopiaClient.gettimeStamp();
        if (ts > log_time || log_time == -1) {
            log_time = ts + 2000;
            int elapsed = ts - t0;
            System.out.println(nSamp + " " + nBlk + " " + ((int)(elapsed / 1000.0f)) + " " + (nSamp *1000.0f/ elapsed) + " (samp,blk,s,hz)");
            logPacketLoss();
        }
    }

    // ---------------------------- control of the ganglion
    public void sendData(boolean send) {
        //b starts the stream, s stops it
        final byte[] mCommands = {'b', 's'};
        char cmd;

        //if send is true, start the stream, if false send stop char
        if (send) {
            cmd = (char) mCommands[0];
        } else {
            cmd = (char) mCommands[1];
        }

        Log.v(TAG, "Sending Command : " + cmd);
        BluetoothGattCharacteristic mGanglionSend = mGanglionBluetoothGatt.getService(UUID_GANGLION_SERVICE).getCharacteristic(UUID_GANGLION_SEND);
        mGanglionSend.setValue(new byte[]{(byte) cmd});
        writeCharacteristic(mGanglionBluetoothGatt, mGanglionSend);
    }

    public void toggleAccelerometer(boolean send) {
        //n activates the onboard Accelerometer, N deactivates it
        //when the Accelerometer is activated, the board will switch to 18 bit compression
        final byte[] mCommands = {'n', 'N'};
        char cmd;

        if (send) {
            cmd = (char) mCommands[0];
        } else {
            cmd = (char) mCommands[1];
        }

        BluetoothGattCharacteristic mGanglionSend = mGanglionBluetoothGatt.getService(UUID_GANGLION_SERVICE).getCharacteristic(UUID_GANGLION_SEND);
        Log.v(TAG, "Sending Command : " + cmd);
        mGanglionSend.setValue(new byte[]{(byte) cmd});
        writeCharacteristic(mGanglionBluetoothGatt, mGanglionSend);
    }

    public void impedanceCheck(boolean send) {
        //z starts the stream, Z stops it
        final byte[] mCommands = {'z', 'Z'};
        char cmd;

        //if send is true, start the impedance check, if false send stop char
        if (send) {
            cmd = (char) mCommands[0];
        } else {
            cmd = (char) mCommands[1];
        }

        Log.v(TAG, "Sending Command : " + cmd);
        BluetoothGattCharacteristic mGanglionSend = mGanglionBluetoothGatt.getService(UUID_GANGLION_SERVICE).getCharacteristic(UUID_GANGLION_SEND);
        mGanglionSend.setValue(new byte[]{(byte) cmd});
        writeCharacteristic(mGanglionBluetoothGatt, mGanglionSend);
    }

    // --------------------------------- Charastertic processing
    private void actionOutputScoreAvailable(BluetoothGattCharacteristic characteristic) {
        // TODO[]: send to the UtopiaHUB
        final byte[] data = characteristic.getValue();
        StringBuilder str = new StringBuilder();
        for ( int i = 0; i < data.length; i++ ) str.append(data[i]);
        Log.v(TAG, "Got OutputScore: " + str.toString());

        try {
            try {
                UtopiaMessage msg = RawMessage.deserialize(ByteBuffer.wrap(data)).decodePayload();
                utopiaClient.sendMessage(msg);
            } catch (ClientException e) {
                e.printStackTrace();
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String exceptionAsString = sw.toString();
                utopiaClient.sendMessage(new nl.ma.utopiaserver.messages.Log(utopiaClient.gettimeStamp(),"Payload decoding error: "+sw.toString()));
            }
            StringBuilder sb = new StringBuilder(); for(byte b: data) sb.append(String.format("%02x", b));
            utopiaClient.sendMessage(new nl.ma.utopiaserver.messages.Log(utopiaClient.gettimeStamp(),"OutputScore: 0x"+sb.toString()));
        } catch ( IOException ex ){
            Log.v(TAG,"Exception sending stimulus state message");
        }
    }

    private void actionStimulusStateAvailable(BluetoothGattCharacteristic characteristic) {
        // TODO[X]: send to the UtopiaHUB
        final byte[] data = characteristic.getValue();
        StringBuilder str = new StringBuilder();
        for ( int i=0; i< data.length; i++ ) str.append(data[i]);
        Log.v(TAG, "Got StimulusState: " + str.toString());
        try {
            UtopiaMessage msg = RawMessage.deserialize(ByteBuffer.wrap(data)).decodePayload();
            utopiaClient.sendMessage(msg);
        } catch (ClientException e) {
            Log.v(TAG,"error decoding stimulus event payload");
        } catch ( IOException ex ){
            Log.v(TAG,"Exception sending stimulus state message");
        }
    }

    private void actionDataAvailable(final BluetoothGattCharacteristic characteristic) {
        if (!UUID_GANGLION_RECEIVE.equals(characteristic.getUuid())) {
            return;
        }

        final byte[] data = characteristic.getValue();
        if (data == null || data.length == 0) {
            return;
        }

        // ***WARNING*** parseData eventually puts the new data in the member variables:
        //      packetID, sample_id, fullData, lastAccelerometer, lastimpedence
        parseData(data);

        if (packetID == 0) {
            // single sample
            processSample(sample_id[0], fullData[0]);

        } else if (packetID >= 1 && packetID <= 100) {
            // pair samples
            processSample(sample_id[0], fullData[0]);
            processSample(sample_id[1], fullData[1]);
            if (accSampleComplete) {
                processAccSample(sample_id[0], lastAccelerometer);
            }

        } else if (packetID >= 101 && packetID <= 200) {
            // pair samples
            processSample(sample_id[0], fullData[0]);
            processSample(sample_id[1], fullData[1]);

        } else if (packetID >= 201 && packetID <= 205) {
            //only send the impedance data when all channels are available
            if (packetID == 205) {
                processImpedanceSample(sample_id[0], lastImpedance);
            }
        } else {
        }
    }

    private void processSample(int sample_id, int[] sample) {
        try {
            fillAndSendDataPacket(sample);
        } catch (IOException e) {
            Log.v(TAG,"Error writing data to utopia-hub");
            System.out.println(sampleToString(sample_id, sample));
            e.printStackTrace();
        }
    }

    private void processAccSample(int sample_id, int[] sample) {
        System.out.println("Acc:" + sampleToString(sample_id, sample));
    }

    private void processImpedanceSample(int sample_id, int[] sample) {
        System.out.println("Imp:" + sampleToString(sample_id, sample));
    }

    private String sampleToString(int sample_id, int[] sample) {
        StringBuilder str = new StringBuilder("i:" + sample_id + " v[" + sample.length + "]: {");
        for (int c : sample) {
            str.append(c).append(",");
        }
        str.append("}");
        return str.toString();
    }

    //------------------------------  DATA PARSING -----------------
    /* TODO[]: move the raw data decoding to a separate class */
    private static boolean parseData(byte[] data) {

        //Convert from -128 - 127 to 0 - 255
        if (data[0] < 0) {
            packetID = data[0] + 256;
        } else {
            packetID = data[0];
        }
        //separate the data
        byte[] payload = Arrays.copyOfRange(data, 1, data.length);
        //  Log.wtf(TAG," PacketID " + packetID);
        //   Log.wtf(TAG," Byte 1: " + payload[0] +" Byte 2: " + payload[1] + " Byte 3: " + payload[2] +" Byte 4: " + payload[3] +
        //           " Byte 5: " + payload[4] +" Byte 6: " + payload[5] +" Byte 7: " + payload[6] +" Byte 8: " + payload[7] +
        //            " Byte 9: " + payload[8] +" Byte 10: " + payload[9] + " Byte 11: " + payload[10] +" Byte 12: " + payload[11] +
        //            " Byte 13: " + payload[12] +" Byte 14: " + payload[13] +" Byte 15: " + payload[14] +" Byte 16: " + payload[15] +
        //           " Byte 17: " + payload[16] +" Byte 18: " + payload[17] +" Byte 19: " + payload[18]);
        //Boolean receiving_ASCII;
        if (packetID == 0) {
            //receiving_ASCII = false;
            parseRaw(packetID, payload);
            updatePacketsCount(packetID);
            // 18-bit compression with Accelerometer
        } else if (packetID >= 1 && packetID <= 100) {
            //receiving_ASCII = false;
            parse18bit(packetID, payload);
            updatePacketsCount(packetID);
            //19-bit compression without Accelerometer
        } else if (packetID >= 101 && packetID <= 200) {
            //receiving_ASCII = false;
            //packetID-100 for sampleID calculation
            parse19bit(packetID - 100, payload);
            updatePacketsCount(packetID);
            //Impedance Channel
        } else if (packetID >= 201 && packetID <= 205) {
            // receiving_ASCII = false;
            parseImpedance(packetID, payload);
            //Part of ASCII
        } else if (packetID == 206) {
            String byteString = new String(payload);
            Log.wtf(TAG, "Packet 206 : " + byteString + "\n");
            //receiving_ASCII = true;
            //End of ASCII message
        } else if (packetID == 207) {
            String byteString = new String(payload);
            Log.wtf(TAG, "Packet 207 : " + byteString + "\n");
            //receiving_ASCII = false;
        } else {
            Log.wtf(TAG, "Warning: unknown packet type : " + packetID);
            return false;
        }
        return true;
    }

    private static void parseImpedance(int packetID, byte[] payload) {

        //Dealing with Impedance check
        if (payload[payload.length - 1] != 'Z') {
            Log.e(TAG, "Wrong format for impedance check, should be ASCII ending with 'Z\\n'");
        }

        //Remove the stop characters
        byte[] bytes = Arrays.copyOfRange(payload, 0, payload.length - 2);
        //convert from ASCII to actual value in kOhm
        String Impedance = new String(bytes);
        switch (packetID) {
            case 201:
                lastImpedance[0] = Integer.parseInt(Impedance);
                break;
            case 202:
                lastImpedance[1] = Integer.parseInt(Impedance);
                break;
            case 203:
                lastImpedance[2] = Integer.parseInt(Impedance);
                break;
            case 204:
                lastImpedance[3] = Integer.parseInt(Impedance);
                break;
            case 205:
                lastImpedance[4] = Integer.parseInt(Impedance);
                break;
            default:
                break;
        }

    }

    private static void parseRaw(int packetID, byte[] payload) {
        //Dealing with "Raw uncompressed" - 24 Bit
        if (payload.length != 19) {
            Log.e(TAG, "Wrong size, for Raw data " + payload.length + " instead of 19 bytes");
            return;
        }
        // 4 channels of 24bits - 4*3=12 Bytes of 19 Bytes used
        //Take values one by one
        for (int i=0, ci = 0; ci < 4; ci++, i+=3) {
            lastChannelData[ci] = conv24bitsToInt(Arrays.copyOfRange(payload, i, i + 3));
            fullData[0][ci] = lastChannelData[ci];
            fullData[1][ci] = 0;
        }
        // Log.wtf(TAG,"RAW\n");
        // Log.wtf(TAG, "Channel 1 " + fullData[0][0] + " Channel 2 " + fullData[0][1] + " Channel 3 " + fullData[0][2] + " Channel 4 " + fullData[0][3]);
        sample_id[0] = 1;
        sample_id[1] = 0;
    }

    private static void parse19bit(int packetID, byte[] payload) {
        //Dealing with "19-bit compression without Accelerometer"

        if (payload.length != 19) {
            Log.e(TAG, "Wrong size, for 19-bit compression data " + payload.length + " instead of 19 bytes");
            return;
        }
        //should get 2 by 4 arrays of uncompressed data
        int[][] deltas = decompressDeltas19Bit(payload);
        // the sample_id will be shifted
        int delta_id = 0;

        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 4; j++) {
                fullData[i][j] = lastChannelData[j] - deltas[i][j];
                lastChannelData[j] = fullData[i][j];
            }
            //convert from packet to sample id
            sample_id[delta_id] = packetID * 2 + delta_id;
            //19bit packets hold deltas between two samples
            delta_id++;
        }
        // Log.wtf(TAG, "Channel 1 " + fullData[0][0] + " Channel 2 " + fullData[0][1] + " Channel 3 " + fullData[0][2] + " Channel 4 " + fullData[0][3]);
        // Log.wtf(TAG, "Channel 1 " + fullData[1][0] + " Channel 2 " + fullData[1][1] + " Channel 3 " + fullData[1][2] + " Channel 4 " + fullData[1][3]);
    }

    private static void parse18bit(int packetID, byte[] payload) {

        // Dealing with "18-bit compression without Accelerometer" """
        if (payload.length != 19) {
            Log.e(TAG, "Wrong size, for Raw data " + payload.length + " instead of 19 bytes");
            return;
        }

        //the last byte of every packetID ending with x1,x2,x3 contain the x,y,z accelerometer data
        // accelerometer X
        if (packetID % 10 == 1) {
            lastAccelerometer[0] = payload[18];
            counter = 1;
        }
        // accelerometer Y
        else if (packetID % 10 == 2) {
            lastAccelerometer[1] = payload[18];
            counter++;
        }
        // accelerometer Z
        else if (packetID % 10 == 3) {
            lastAccelerometer[2] = payload[18];
            //if no packets are lost, the accel data can be sent
            if (counter == 2) {
                accSampleComplete = true;
            }

        } else {
            //prevent sending incorrect samples due to packet loss
            accSampleComplete = false;
            counter = 0;
        }

        //Sending all 19 bytes to save performance by not copying the array
        //byte[] payload18bit= Arrays.copyOfRange(payload, 1, payload.length-1);
        int[][] deltas = decompressDeltas18Bit(payload);
        // the sample_id will be shifted
        int delta_id = 0;

        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 4; j++) {
                fullData[i][j] = lastChannelData[j] - deltas[i][j];
                lastChannelData[j] = fullData[i][j];
            }
            //convert from packet to sample id (2 samples per packet)
            sample_id[delta_id] = packetID * 2 + delta_id;
            //18bit packets hold deltas between two samples
            delta_id++;
        }
        // Log.wtf(TAG, "Channel 1 " + fullData[0][0] + " Channel 2 " + fullData[0][1] + " Channel 3 " + fullData[0][2] + " Channel 4 " + fullData[0][3]);
        //  Log.wtf(TAG, "Channel 1 " + fullData[1][0] + " Channel 2 " + fullData[1][1] + " Channel 3 " + fullData[1][2] + " Channel 4 " + fullData[1][3]);
    }

    private static void updatePacketsCount(int packetID) {
        // Update last packet ID and dropped packets
        totalPackets++;
        int packets_dropped;
        if (last_id == -1) {
            last_id = packetID;
            return;
        }
        // ID loops every 101 packets
        if (packetID > last_id) {
            packets_dropped = packetID - last_id - 1;
        } else {
            packets_dropped = packetID + 101 - last_id - 1;
        }
        last_id = packetID;
        if (packets_dropped > 0) {
            if (packets_dropped == 100)
                return;
            Log.e(TAG, "Warning: dropped " + packets_dropped + " packets.");
            lostPackets = lostPackets + packets_dropped;
        }
    }

    public static void logPacketLoss() {
        int packetNumber = lostPackets + totalPackets;
        double packetLoss = (double) lostPackets * 100 / packetNumber;
        Log.wtf(TAG, "Lost: " + lostPackets + " out of " + packetNumber + " % of loss= " + packetLoss);
    }

    private static int[][] decompressDeltas18Bit(byte[] payload) {
        /*  Called to when a compressed packet is received.
        payload: the data portion of the sample. So 19 bytes.
        return {Array} - An array of deltas of shape 2x4 (2 samples per packet and 4 channels per sample.) */
        if (payload.length != 19) {
            Log.e(TAG, "Input should be 19 bytes long.");
        }

        int[][] receivedDeltas = {{0, 0, 0, 0},
                {0, 0, 0, 0}};

        //Sample 1 - Channel 1
        int[] miniBuf = {
                ((payload[0] & 0xFF) >>> 6),
                (((payload[0] & 0x3F) << 2) & 0xFF) | ((payload[1] & 0xFF) >>> 6),
                (((payload[1] & 0x3F) << 2) & 0xFF) | ((payload[2] & 0xFF) >>> 6)
        };
        receivedDeltas[0][0] = conv18bitToInt32(miniBuf);

        //Sample 1 - Channel 2
        miniBuf = new int[]{
                (payload[2] & 0x3F) >>> 4,
                (payload[2] << 4 & 0xFF) | ((payload[3] & 0xFF) >>> 4),
                (payload[3] << 4 & 0xFF) | ((payload[4] & 0xFF) >>> 4)
        };
        receivedDeltas[0][1] = conv18bitToInt32(miniBuf);

        //Sample 1 - Channel 3
        miniBuf = new int[]{
                ((payload[4] & 0x0F) >>> 2),
                (payload[4] << 6 & 0xFF) | ((payload[5] & 0xFF) >>> 2),
                (payload[5] << 6 & 0xFF) | ((payload[6] & 0xFF) >>> 2)
        };
        receivedDeltas[0][2] = conv18bitToInt32(miniBuf);

        //Sample 1 - Channel 4
        miniBuf = new int[]{
                (payload[6] & 0x03),
                (payload[7] & 0xFF),
                (payload[8] & 0xFF)
        };
        receivedDeltas[0][3] = conv18bitToInt32(miniBuf);

        //Sample 2 - Channel 1
        miniBuf = new int[]{
                ((payload[9] & 0xFF) >>> 6),
                (((payload[9] & 0x3F) << 2) & 0xFF) | ((payload[10] & 0xFF) >>> 6),
                (((payload[10] & 0x3F) << 2) & 0xFF) | ((payload[11] & 0xFF) >>> 6)
        };
        receivedDeltas[1][0] = conv18bitToInt32(miniBuf);

        //Sample 2 - Channel 2
        miniBuf = new int[]{
                (payload[11] & 0x3F) >>> 4,
                (payload[11] << 4 & 0xFF) | ((payload[12] & 0xFF) >>> 4),
                (payload[12] << 4 & 0xFF) | ((payload[13] & 0xFF) >>> 4)
        };
        receivedDeltas[1][1] = conv18bitToInt32(miniBuf);

        // Sample 2 - Channel 3
        miniBuf = new int[]{
                ((payload[13] & 0x0F) >>> 2),
                (payload[13] << 6 & 0xFF) | ((payload[14] & 0xFF) >>> 2),
                (payload[14] << 6 & 0xFF) | ((payload[15] & 0xFF) >>> 2)
        };
        receivedDeltas[1][2] = conv18bitToInt32(miniBuf);

        // Sample 2 - Channel 4
        miniBuf = new int[]{
                (payload[15] & 0x03),
                (payload[16] & 0xFF),
                (payload[17] & 0xFF)
        };
        receivedDeltas[1][3] = conv18bitToInt32(miniBuf);

        return receivedDeltas;
    }

    private static int[][] decompressDeltas19Bit(byte[] payload) {
     /*  Called to when a compressed packet is received.
        payload: the data portion of the sample. So 19 bytes.
        return {Array} - An array of deltas of shape 2x4 (2 samples per packet and 4 channels per sample.) */
        if (payload.length != 19) {
            Log.e(TAG, "Input should be 19 bytes long.");
        }

        int[][] receivedDeltas = {{0, 0, 0, 0},
                {0, 0, 0, 0}};

        //Sample 1 - Channel 1
        int[] miniBuf = {
                ((payload[0] & 0xFF) >>> 5),
                (((payload[0] & 0x1F) << 3) & 0xFF) | ((payload[1] & 0xFF) >>> 5),
                (((payload[1] & 0x1F) << 3) & 0xFF) | ((payload[2] & 0xFF) >>> 5)
        };
        receivedDeltas[0][0] = conv19bitToInt32(miniBuf);

        //Sample 1 - Channel 2
        miniBuf = new int[]{
                (payload[2] & 0x1F) >>> 2,
                (payload[2] << 6 & 0xFF) | ((payload[3] & 0xFF) >>> 2),
                (payload[3] << 6 & 0xFF) | ((payload[4] & 0xFF) >>> 2)
        };

        receivedDeltas[0][1] = conv19bitToInt32(miniBuf);

        //Sample 1 - Channel 3
        miniBuf = new int[]{
                ((payload[4] & 0x03) << 1 & 0xFF) | ((payload[5] & 0xFF) >>> 7),
                ((payload[5] & 0x7F) << 1 & 0xFF) | ((payload[6] & 0xFF) >>> 7),
                ((payload[6] & 0x7F) << 1 & 0xFF) | ((payload[7] & 0xFF) >>> 7)
        };
        receivedDeltas[0][2] = conv19bitToInt32(miniBuf);

        //Sample 1 - Channel 4
        miniBuf = new int[]{
                ((payload[7] & 0x7F) >>> 4),
                ((payload[7] & 0x0F) << 4 & 0xFF) | ((payload[8] & 0xFF) >>> 4),
                ((payload[8] & 0x0F) << 4 & 0xFF) | ((payload[9] & 0xFF) >>> 4)
        };
        receivedDeltas[0][3] = conv19bitToInt32(miniBuf);

        //Sample 2 - Channel 1
        miniBuf = new int[]{
                ((payload[9] & 0x0F) >>> 1),
                (payload[9] << 7 & 0xFF) | ((payload[10] & 0xFF) >>> 1),
                (payload[10] << 7 & 0xFF) | ((payload[11] & 0xFF) >>> 1)
        };
        receivedDeltas[1][0] = conv19bitToInt32(miniBuf);

        //Sample 2 - Channel 2
        miniBuf = new int[]{
                ((payload[11] & 0x01) << 2 & 0xFF) | ((payload[12] & 0xFF) >>> 6),
                (payload[12] << 2 & 0xFF) | ((payload[13] & 0xFF) >>> 6),
                (payload[13] << 2 & 0xFF) | ((payload[14] & 0xFF) >>> 6)
        };
        receivedDeltas[1][1] = conv19bitToInt32(miniBuf);

        // Sample 2 - Channel 3
        miniBuf = new int[]{
                ((payload[14] & 0x38) >>> 3),
                ((payload[14] & 0x07) << 5 & 0xFF) | ((payload[15] & 0xF8) >>> 3),
                ((payload[15] & 0x07) << 5 & 0xFF) | ((payload[16] & 0xF8) >>> 3)
        };
        receivedDeltas[1][2] = conv19bitToInt32(miniBuf);

        // Sample 2 - Channel 4
        miniBuf = new int[]{
                (payload[16] & 0x07),
                (payload[17] & 0xFF),
                (payload[18] & 0xFF)};
        receivedDeltas[1][3] = conv19bitToInt32(miniBuf);

        return receivedDeltas;
    }

    private static int conv18bitToInt32(int[] threeByteBuffer) {
        // Convert 18bit data coded on 3 bytes to a proper integer (LSB bit 1 used as sign). """
        if (threeByteBuffer.length != 3) {
            Log.e(TAG, "Input should be 3 bytes long.");
            return -1;
        }
        int prefix = 0b0000000000000;
        //if LSB is 1, negative number, some hasty unsigned to signed conversion to do
        if ((threeByteBuffer[2] & 0x01) > 0) {
            prefix = 0b11111111111111;
            return ((prefix << 18) | (threeByteBuffer[0] << 16) | (threeByteBuffer[1] << 8) | threeByteBuffer[2]);
        } else {
            return ((prefix << 18) | (threeByteBuffer[0] << 16) | (threeByteBuffer[1] << 8) | threeByteBuffer[2]);
        }
    }

    private static int conv19bitToInt32(int[] threeByteBuffer) {
        // Convert 19bit data coded on 3 bytes to a proper integer (LSB bit 1 used as sign). """
        if (threeByteBuffer.length != 3) {
            Log.e(TAG, "Input should be 3 bytes long.");
            return -1;
        }
        int prefix = 0b0000000000000;
        //if LSB is 1, negative number, some hasty unsigned to signed conversion to do
        if ((threeByteBuffer[2] & 0x01) > 0) {
            prefix = 0b1111111111111;
            return ((prefix << 19) | (threeByteBuffer[0] << 16) | (threeByteBuffer[1] << 8) | threeByteBuffer[2]);
        } else {
            return ((prefix << 19) | (threeByteBuffer[0] << 16) | (threeByteBuffer[1] << 8) | threeByteBuffer[2]);
        }
    }

    private static int conv24bitsToInt(byte[] byteArray) {
        //Convert 24bit data coded on 3 bytes to a proper integer """
        if (byteArray.length != 3) {
            // Log.e(TAG, "Input should be 3 bytes long.");
            return -1;
        }

        int newInt = (((0xFF & byteArray[0]) << 16) | ((0xFF & byteArray[1]) << 8) | (0xFF & byteArray[2])
        );

        //If the MSB is 1 then the number is negative - fill up with 1s
        if ((newInt & 0x00800000) > 0) {
            newInt |= 0xFF000000;
        } else {
            newInt &= 0x00FFFFFF;
        }

        return newInt;
    }
}
