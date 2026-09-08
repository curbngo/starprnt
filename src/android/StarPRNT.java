package starprnt.cordova;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import com.starmicronics.stario.PortInfo;
import com.starmicronics.stario.StarIOPort;
import com.starmicronics.stario.StarIOPortException;
import com.starmicronics.stario.StarPrinterStatus;
import com.starmicronics.starioextension.ConnectionCallback;
import com.starmicronics.starioextension.IConnectionCallback;
import com.starmicronics.starioextension.StarIoExt;
import com.starmicronics.starioextension.StarIoExt.Emulation;
import com.starmicronics.starioextension.ICommandBuilder;
import com.starmicronics.starioextension.ICommandBuilder.CutPaperAction;
import com.starmicronics.starioextension.ICommandBuilder.CodePageType;
import com.starmicronics.starioextension.StarIoExtManager;
import com.starmicronics.starioextension.StarIoExtManagerListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.util.Base64;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * This class echoes a string called from JavaScript.
 */
public class StarPRNT extends CordovaPlugin {

    private static String TAG = "cordova.starprnt";
    private static int STAR_VENDOR_ID = 1305;

    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;

    private CordovaInterface cordova;
    private CallbackContext _callbackContext = null;
    private String _action = null;
    private JSONArray _args = null;

    private static class PendingCall {
        final String action;
        final JSONArray args;
        final CallbackContext callbackContext;

        PendingCall(String action, JSONArray args, CallbackContext callbackContext) {
            this.action = action;
            this.args = args;
            this.callbackContext = callbackContext;
        }
    }

    // Calls received while the Bluetooth permission prompt is up; replayed on grant
    private final ArrayList<PendingCall> pendingPermissionCalls = new ArrayList<PendingCall>();
    private boolean permissionRequestInFlight = false;

    private StarIoExtManager starIoExtManager;
    private String persistentPortName = null;
    private HashMap<String, Bitmap> bitmapCache = new HashMap<>();

    private String INTENT_ACTION_GRANT_USB;
    private enum UsbPermission { Unknown, Requested, Granted, Denied }
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private UsbDevice usbDevice;
    private BroadcastReceiver broadcastReceiver;
    private UsbManager usbManager;
    private IntentFilter intentFilter;
    private PendingIntent pendingIntent;

     private boolean hasPermissions(String[] permissions) {
        for (String permission: permissions) {
            if (!PermissionHelper.hasPermission(this, permission)) {
                Log.d(TAG, "missing permission "+permission);
                return false;
            }
        }
        return true;
    }

    private void reportBluetoothPermissionError(CallbackContext callbackContext, SecurityException e) {
        callbackContext.error("Missing bluetooth permissions: " + e.getMessage());
    }

    private void reportBluetoothPermissionDenied(CallbackContext callbackContext) {
        callbackContext.error("Necessary bluetooth permissions denied");
    }

    private boolean isBluetoothPortName(String portName) {
        return portName != null && portName.startsWith("BT:");
    }

    /** Runtime Bluetooth permissions are only required on Android 12 (API 31)+. */
    private boolean needsBluetoothRuntimePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    private boolean hasBluetoothPermissions() {
        if (!needsBluetoothRuntimePermission()) {
            return true;
        }
        return hasPermissions(getRequiredBluetoothPermissions());
    }

    /**
     * Verifies Bluetooth permissions at the point of use (e.g. background thread).
     * Never shows a prompt — returns false and reports to JS only if missing.
     */
    private boolean guardBluetoothPermissions(CallbackContext callbackContext) {
        if (hasBluetoothPermissions()) {
            return true;
        }
        reportBluetoothPermissionDenied(callbackContext);
        return false;
    }

    private boolean usesBluetoothForPersistentMode() {
        return persistentPortName != null && isBluetoothPortName(persistentPortName);
    }

    private boolean guardPersistentBluetoothPermissions(CallbackContext callbackContext) {
        if (!usesBluetoothForPersistentMode()) {
            return true;
        }
        return guardBluetoothPermissions(callbackContext);
    }

    private boolean requiresBluetoothPermissions(String action, JSONArray args) throws JSONException {
        if (action.equals("disconnect")) {
            return false;
        }
        if (action.equals("portDiscovery")) {
            String iface = args.getString(0);
            return iface.equals("Bluetooth") || iface.equals("All");
        }
        String portName = args.getString(0);
        if (portName.equals("null")) {
            return usesBluetoothForPersistentMode();
        }
        return isBluetoothPortName(portName);
    }

    private String[] getRequiredBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[] { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT };
        }
        // Android API 30 or lower
        return new String[] { Manifest.permission.BLUETOOTH };
    }

    /**
     * Queues the call and shows the system permission prompt. Caller must verify
     * {@link #hasBluetoothPermissions()} is false first — never call when already granted.
     */
    private void queueBluetoothPermissionRequest(String action, JSONArray args, CallbackContext callbackContext) {
        synchronized (pendingPermissionCalls) {
            pendingPermissionCalls.add(new PendingCall(action, args, callbackContext));
            if (!permissionRequestInFlight) {
                permissionRequestInFlight = true;
                Log.d(TAG, "requesting permissions");
                PermissionHelper.requestPermissions(
                        this, REQUEST_BLUETOOTH_PERMISSIONS, getRequiredBluetoothPermissions());
            }
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        if (requestCode != REQUEST_BLUETOOTH_PERMISSIONS) {
            return;
        }

        ArrayList<PendingCall> queuedCalls;
        synchronized (pendingPermissionCalls) {
            permissionRequestInFlight = false;
            queuedCalls = new ArrayList<PendingCall>(pendingPermissionCalls);
            pendingPermissionCalls.clear();
        }

        // An empty result means the prompt was interrupted; treat as denied
        boolean granted = grantResults.length > 0;
        for (int r : grantResults) {
            Log.d(TAG, "Permission results: " + r);
            if (r == PackageManager.PERMISSION_DENIED) {
                granted = false;
            }
        }

        for (PendingCall call : queuedCalls) {
            if (granted) {
                execute(call.action, call.args, call.callbackContext);
            } else {
                reportBluetoothPermissionDenied(call.callbackContext);
            }
        }
    }

    @Override
	public void initialize(CordovaInterface cordova, CordovaWebView webView) {
		super.initialize(cordova, webView);
		this.cordova = cordova;
		this.INTENT_ACTION_GRANT_USB = cordova.getActivity().getPackageName() + ".GRANT_USB";
        this.broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Broadcast onreceive" + intent.toString());

                if (INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    synchronized (this) {
                        usbDevice = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ?
                                UsbPermission.Granted :
                                UsbPermission.Denied;
                        if (usbPermission == UsbPermission.Granted && usbDevice != null) {
                            try {
                                execute(_action, _args, _callbackContext);
                            } catch (JSONException e) {
                                _callbackContext.error(e.getMessage());
                            }
                        } else {
                            _callbackContext.error("No permission granted to access USB device.");
                        }
                    }
                }
            }
        };
        this.usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
        this.pendingIntent = PendingIntent.getBroadcast(cordova.getContext(), 0, new Intent(INTENT_ACTION_GRANT_USB), PendingIntent.FLAG_MUTABLE);
        intentFilter = new IntentFilter(INTENT_ACTION_GRANT_USB);
        cordova.getActivity().registerReceiver(this.broadcastReceiver, intentFilter);
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        this.cordova.getActivity().registerReceiver(broadcastReceiver, intentFilter);
    }

    @Override
    public void onPause(boolean multitasking) {
        this.cordova.getActivity().unregisterReceiver(broadcastReceiver);
        super.onPause(multitasking);
    }


    /**
     * Executes the request and returns PluginResult.
     *
     * @param action            The action to execute.
     * @param args              JSONArray of arguments for the plugin.
     * @param callbackContext   The callback id used when calling back into JavaScript.
     * @return                  True if the action was valid, false otherwise.
     */
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        this._action = action;
        this._args = args;
        this._callbackContext = callbackContext;
        if (requiresBluetoothPermissions(action, args) && !hasBluetoothPermissions()) {
            queueBluetoothPermissionRequest(action, args, callbackContext);
            return true;
        }

        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getVendorId() == STAR_VENDOR_ID && !usbManager.hasPermission(device)) {
                this._action = action;
                this._args = args;
                this._callbackContext = callbackContext;
                usbManager.requestPermission(device, pendingIntent);
                return true;
            }
        }

        if (action.equals("checkStatus")) {
            String portName = args.getString(0);
            String portSettings = getPortSettingsOption(portName, args.getString(1));
            this.checkStatus(portName, portSettings, callbackContext);
            return true;
        } else if (action.equals("portDiscovery")) {
            String port = args.getString(0);
            this.portDiscovery(port, callbackContext);
            return true;
        } else if (action.equals("printRasterReceipt")) {
            String portName = args.getString(0);
            String portSettings = getPortSettingsOption(portName, args.getString(1));
            Emulation emulation = getEmulation(args.getString(1));
            String printObj = args.getString(2);
            try {
                this.printRasterReceipt(portName, portSettings, emulation, printObj, callbackContext);
            } catch (IOException e) {
               // e.printStackTrace();
            }
            return true;
        } else if (action.equals("printBase64Image")) {
            String portName = args.getString(0);
            String portSettings = getPortSettingsOption(portName, args.getString(1));
            Emulation emulation = getEmulation(args.getString(1));
            String printObj = args.getString(2);
            this.printBase64Image(portName, portSettings, emulation, printObj, callbackContext);
            return true;

        } else if (action.equals("printRawText")) {
            String portName = args.getString(0);
            String portSettings = getPortSettingsOption(portName, args.getString(1));
            Emulation emulation = getEmulation(args.getString(1));
            String printObj = args.getString(2);

            this.printRawText(portName, portSettings, emulation, printObj, callbackContext);
            return true;
        } else if (action.equals("printRasterData")) {
            String portName = args.getString(0);
            String portSettings = getPortSettingsOption(portName, args.getString(1));
            Emulation emulation = getEmulation(args.getString(1));
            String printObj = args.getString(2);

            try {
                this.printRasterData(portName, portSettings, emulation, printObj, callbackContext);
            } catch (IOException e) {
                // e.printStackTrace();
            }
            return true;
        } else if (action.equals("print")) {
            String portName = args.getString(0);
            String portSettings = getPortSettingsOption(portName, args.getString(1));
            Emulation emulation = getEmulation(args.getString(1));
            JSONArray printCommands = args.getJSONArray(2);
            this.print(portName, portSettings, emulation, printCommands, callbackContext);
            return true;
        } else if (action.equals("openCashDrawer")) {
            String portName = args.getString(0);
            String portSettings = getPortSettingsOption(portName, args.getString(1));
            Emulation emulation = getEmulation(args.getString(1));
            this.openCashDrawer(portName, portSettings, emulation, callbackContext);
            return true;
        } else if (action.equals("connect")) {
            String portName = args.getString(0);
            String portSettings = getPortSettingsOption(portName, args.getString(1)); //get port settings using emulation parameter
            Boolean hasBarcodeReader = args.getBoolean(2);
            _callbackContext = callbackContext;
            this.connect(portName, portSettings, hasBarcodeReader, callbackContext);
            return true;
        } else if (action.equals("disconnect")) {
            this.disconnect(callbackContext);
            return true;
        }
        return false;
    }

    public void checkStatus(String portName, String portSettings, CallbackContext callbackContext) {

        final Context context = this.cordova.getActivity();
        final CallbackContext _callbackContext = callbackContext;

        final String _portName = portName;
        final String _portSettings = portSettings;

        cordova.getThreadPool()
                .execute(new Runnable() {
                    public void run() {
                        if (isBluetoothPortName(_portName) && !guardBluetoothPermissions(_callbackContext)) {
                            return;
                        }

                        StarIOPort port = null;
                        try {

                            port = StarIOPort.getPort(_portName, _portSettings, 10000, context);

                            // A sleep is used to get time for the socket to completely open
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e) {
                            }

                            StarPrinterStatus status;
                            Map<String, String> firmwareInformationMap = port.getFirmwareInformation();
                            status = port.retreiveStatus();


                            JSONObject json = new JSONObject();
                            try {
                                json.put("offline", status.offline);
                                json.put("coverOpen", status.coverOpen);
                                json.put("cutterError", status.cutterError);
                                json.put("receiptPaperEmpty", status.receiptPaperEmpty);
                                json.put("ModelName", firmwareInformationMap.get("ModelName"));
                                json.put("FirmwareVersion", firmwareInformationMap.get("FirmwareVersion"));
                            } catch (JSONException ex) {

                            } finally {
                                _callbackContext.success(json);
                            }


                        } catch (StarIOPortException e) {
                            _callbackContext.error("Failed to connect to printer :" + e.getMessage());
                        } catch (SecurityException e) {
                            reportBluetoothPermissionError(_callbackContext, e);
                        } finally {

                            if (port != null) {
                                try {

                                    StarIOPort.releasePort(port);
                                } catch (StarIOPortException e) {
                                    _callbackContext.error("Failed to connect to printer" + e.getMessage());
                                }
                            }

                        }

                    }
                });
    }


    private void portDiscovery(String strInterface, CallbackContext callbackContext) {

        Log.d(TAG, "port-disc");

        final CallbackContext _callbackContext = callbackContext;
        final String _strInterface = strInterface;

        cordova.getThreadPool()
                .execute(new Runnable() {
                    public void run() {
                        boolean shouldContinue = true;
                        JSONArray result = new JSONArray();
                        try {
                            if (_strInterface.equals("LAN")) {
                                result = getPortDiscovery("LAN");
                            } else if (_strInterface.equals("Bluetooth")) {
                                if (guardBluetoothPermissions(_callbackContext)) {
                                    result = getPortDiscovery("Bluetooth");
                                } else {
                                    shouldContinue = false;
                                }
                            } else if (_strInterface.equals("USB")) {
                                result = getPortDiscovery("USB");
                            } else if (guardBluetoothPermissions(_callbackContext)) {
                                result = getPortDiscovery("All");
                            } else {
                                shouldContinue = false;
                            }
                        } catch (StarIOPortException exception) {
                            _callbackContext.error(exception.getMessage());
                        } catch (SecurityException exception) {
                            shouldContinue = false;
                            Log.d(TAG, "no-perms");
                            reportBluetoothPermissionError(_callbackContext, exception);
                        } catch (JSONException e) {

                        } finally {
                            if (shouldContinue) {
                                Log.d("Discovered ports", result.toString());
                                _callbackContext.success(result);
                            }
                        }
                    }
                });
    }


    private JSONArray getPortDiscovery(String interfaceName) throws StarIOPortException, JSONException {
        List<PortInfo> BTPortList;
        List<PortInfo> TCPPortList;
        List<PortInfo> USBPortList;

        final Context context = this.cordova.getActivity();
        final ArrayList<PortInfo> arrayDiscovery = new ArrayList<PortInfo>();

        JSONArray arrayPorts = new JSONArray();


        if (interfaceName.equals("Bluetooth") || interfaceName.equals("All")) {
            BTPortList = StarIOPort.searchPrinter("BT:");

            for (PortInfo portInfo : BTPortList) {
                arrayDiscovery.add(portInfo);
            }
        }
        if (interfaceName.equals("LAN") || interfaceName.equals("All")) {
            TCPPortList = StarIOPort.searchPrinter("TCP:");

            for (PortInfo portInfo : TCPPortList) {
                arrayDiscovery.add(portInfo);
            }
        }
        if (interfaceName.equals("USB") || interfaceName.equals("All")) {
            try {
                USBPortList = StarIOPort.searchPrinter("USB:", context);
            }catch (StarIOPortException e) {
                USBPortList = new ArrayList<PortInfo>();
            }
            for (PortInfo portInfo : USBPortList) {
                arrayDiscovery.add(portInfo);
            }
        }

        for (PortInfo discovery : arrayDiscovery) {
            String portName;

            JSONObject port = new JSONObject();
            if (discovery.getPortName().startsWith("BT:"))
                port.put("portName", "BT:" +  discovery.getMacAddress());
            else port.put("portName", discovery.getPortName());

            if (!discovery.getMacAddress().equals("")) {

                port.put("macAddress", discovery.getMacAddress());

                    if (discovery.getPortName().startsWith("BT:")) {
                        port.put("modelName", discovery.getPortName());
                    }else if (!discovery.getModelName().equals("")){
                        port.put("modelName", discovery.getModelName());
                }
            } else if (interfaceName.equals("USB") || interfaceName.equals("All")) {
                if (!discovery.getModelName().equals("")) {
                    port.put("modelName", discovery.getModelName());
                }
                if (!discovery.getUSBSerialNumber().equals(" SN:")) {
                    port.put("USBSerialNumber", discovery.getUSBSerialNumber());
                }
            }

            if (port.has("modelName")) {
                port.put("emulation", getEmulationFromModel(port.getString("modelName")));
            }

            arrayPorts.put(port);
        }

        return arrayPorts;
    }

    private Emulation getEmulation(String emulation){

        if(emulation.equals("StarPRNT")) return Emulation.StarPRNT;
        else if (emulation.equals("StarPRNTL")) return Emulation.StarPRNTL;
        else if (emulation.equals("StarLine")) return Emulation.StarLine;
        else if (emulation.equals("StarGraphic")) return Emulation.StarGraphic;
        else if (emulation.equals("EscPos")) return Emulation.EscPos;
        else if (emulation.equals("EscPosMobile")) return Emulation.EscPosMobile;
        else if (emulation.equals("StarDotImpact")) return Emulation.StarDotImpact;
        else return Emulation.StarLine;
    };

    private String getEmulationFromModel(String modelName){
        if (modelName == null) return "StarGraphic";
        String m = modelName.toUpperCase();
        if (m.contains("654") || m.contains("650") || m.contains("700II") || m.contains("800II") || m.contains("FVP10"))
            return "StarLine";
        if (m.contains("TSP100IV") || m.contains("TSP143IV") || m.contains("MCL21") || m.contains("MC-LABEL") || m.contains("MCLABEL") || m.contains("LABEL2"))
            return "StarPRNT";
        return "StarGraphic";
    }

    private String getPortSettingsOption(String portName, String emulation) { // generate the portsettings depending on the emulation type

        String portSettings = "";

        if (emulation.equals("EscPosMobile")) portSettings += "mini";
        else if (emulation.equals("EscPos")) portSettings += "escpos";
        else if ((emulation.equals("StarPRNT") || emulation.equals("StarPRNTL")) && portName != null && portName.toUpperCase().startsWith("BT:")) {
            portSettings += "Portable";
            portSettings += ";l";
        } else portSettings += "";
        return portSettings;
    }

    private void connect(final CallbackContext callbackContext) {
        if (starIoExtManager == null) {
            callbackContext.error("Printer manager not initialized");
            return;
        }
        starIoExtManager.connect(new IConnectionCallback() {
            @Override
            public void onConnected(ConnectResult connectResult) {
                if (connectResult == ConnectResult.Success || connectResult == ConnectResult.AlreadyConnected) {
                    PluginResult result = new PluginResult(PluginResult.Status.OK, "Printer Connected");
                    result.setKeepCallback(true);
                    callbackContext.sendPluginResult(result);
                } else {
                    callbackContext.error("Error Connecting to the printer");
                }
            }

            @Override
            public void onDisconnected() {
                //Do nothing
            }
        });
    }

    private void connect(String portName, String portSettings, Boolean hasBarcodeReader, CallbackContext callbackContext) {
        final Context context = this.cordova.getActivity();
        final CallbackContext _callbackContext = callbackContext;

        if (starIoExtManager != null && starIoExtManager.getPort() != null) {
            starIoExtManager.disconnect((ConnectionCallback) null);
        }

        persistentPortName = portName;

        try {
            starIoExtManager = new StarIoExtManager(
                    hasBarcodeReader ? StarIoExtManager.Type.WithBarcodeReader : StarIoExtManager.Type.Standard,
                    portName, portSettings, 10000, context);
            starIoExtManager.setListener(starIoExtManagerListener);
        } catch (SecurityException e) {
            starIoExtManager = null;
            persistentPortName = null;
            reportBluetoothPermissionError(callbackContext, e);
            return;
        }

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                connect(_callbackContext);
            }
        });
    }
    private void disconnect(CallbackContext callbackContext) {

        final Context context = this.cordova.getActivity();

        final CallbackContext _callbackContext = callbackContext;


        cordova.getThreadPool()
                .execute(new Runnable() {
                    public void run() {

                        if(starIoExtManager != null &&  starIoExtManager.getPort() != null){
                            starIoExtManager.disconnect(new IConnectionCallback() {
                                @Override
                                public void onConnected(ConnectResult connectResult) {
                                    // nothing
                                }

                                @Override
                                public void onDisconnected() {
                                    sendEvent("printerOffline", null);
                                    starIoExtManager.setListener(null); //remove the listener?
                                    persistentPortName = null;
                                    _callbackContext.success("Printer Disconnected!");
                                }
                            });
                        }else{
                            persistentPortName = null;
                            _callbackContext.success("No printers connected");
                        }

                    }
                });
    }



    private void printRawText(final String portName, String portSettings, Emulation emulation, String printObj, CallbackContext callbackContext) throws JSONException {

        final Context context = this.cordova.getActivity();
        final String _portName = portName;
        final String _portSettings = portSettings;
        final Emulation _emulation = emulation;
        final JSONObject print = new JSONObject(printObj);
        final String text = print.optString("text");
        final Boolean cutReceipt = (print.has("cutReceipt") ? print.getBoolean("cutReceipt"): true);
        final Boolean openCashDrawer = (print.has("openCashDrawer")) ? print.getBoolean("openCashDrawer") : true;
        final CallbackContext _callbackContext = callbackContext;

        cordova.getThreadPool()
                .execute(new Runnable() {
                    public void run() {

                        ICommandBuilder builder = StarIoExt.createCommandBuilder(_emulation);

                        builder.beginDocument();

                        builder.append(createCpUTF8(text));

                        if(cutReceipt){
                            builder.appendCutPaper(CutPaperAction.PartialCutWithFeed);
                        }

                        if(openCashDrawer){
                            builder.appendPeripheral(ICommandBuilder.PeripheralChannel.No1); // Kick cash drawer No1
                            builder.appendPeripheral(ICommandBuilder.PeripheralChannel.No2); // Kick cash drawer No2
                        }

                        builder.endDocument();

                        byte[] commands = builder.getCommands();

                        if(_portName == "null"){ // use StarIOExtManager
                            if (guardPersistentBluetoothPermissions(_callbackContext)) {
                                sendCommand(commands, starIoExtManager.getPort(), _callbackContext);
                            }

                        }else{//use StarIOPort
                        sendCommand(context, _portName, _portSettings, commands, _callbackContext);
                        }
                    }
                });
    }
    private void printRasterReceipt(String portName, String portSettings, Emulation emulation, String printObj, CallbackContext callbackContext) throws IOException, JSONException {

        final Context context = this.cordova.getActivity();
        final ContentResolver contentResolver = context.getContentResolver();
        final String _portName = portName;
        final String _portSettings = portSettings;
        final Emulation _emulation = emulation;
        final JSONObject print = new JSONObject(printObj);
        final String text = print.getString("text");
        final String poweredBy = (print.has("poweredBy")) ? print.getString("poweredBy") : null;
        final String headerImage = (print.has("headerImage")) ? print.getString("headerImage") : null;
        final String rightText = (print.has("rightText")) ? print.getString("rightText") : null;
        final int headerImageWidth = (print.has("headerImageWidth")) ? print.getInt("headerImageWidth") : 576;
        final String headerText = (print.has("headerText")) ? print.getString("headerText") : null;
        final int headerFontSize = (print.has("headerFontSize")) ? print.getInt("headerFontSize") : 25;
        final int fontSize = (print.has("fontSize")) ? print.getInt("fontSize") : 25;
        final int paperWidth = (print.has("paperWidth")) ? print.getInt("paperWidth"): 576;
        final Boolean cutReceipt = (print.has("cutReceipt") ? print.getBoolean("cutReceipt"): true);
        final Boolean openCashDrawer = (print.has("openCashDrawer")) ? print.getBoolean("openCashDrawer") : true;
        final CallbackContext _callbackContext = callbackContext;
        final String footerBase64Image = (print.has("footerBase64Image")) ? print.getString("footerBase64Image") : null;
        final String qrCode = (print.has("appendQrCode") ? print.getString("appendQrCode"): null);
        final int footerBase64ImageWidth = (print.has("footerBase64ImageWidth")) ? print.getInt("footerBase64ImageWidth") : 576;

        cordova.getThreadPool()
                .execute(new Runnable() {
                    public void run() {

                        Bitmap image;

                        String typeface = "DEFAULT";
                        String m_typeface = "MONOSPACE";
                        Charset encoding = Charset.forName("US-ASCII");

                        ICommandBuilder builder = StarIoExt.createCommandBuilder(_emulation);

                        builder.beginDocument();
                        Bitmap padding = createBitmapFromText("\n", 25, new JSONObject());
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = 5;

                        if(headerImage != null && !headerImage.isEmpty()){
                            Uri imageUri = null;
                            Bitmap header_logo_bitmap = null;
                            try {
                                imageUri =  Uri.parse(headerImage);
                                header_logo_bitmap = MediaStore.Images.Media.getBitmap(contentResolver, imageUri);
                            } catch (IOException e) {
                                _callbackContext.error(e.getMessage());
                            }
                            builder.appendBitmapWithAbsolutePosition(header_logo_bitmap, false, headerImageWidth, true, ((paperWidth - headerImageWidth) / 2));
                            builder.appendBitmap(padding, false);
                        }

                        if(headerText != null && !headerText.isEmpty()){
                            JSONObject header_text_config = new JSONObject();
                            try{
                                header_text_config.put("paperWidth", paperWidth);
                                header_text_config.put("typeface", typeface);
                                header_text_config.put("alignment", "Center");
                            } catch (JSONException e) {
                                _callbackContext.error(e.getMessage());
                            }
                            Bitmap header_text = createBitmapFromText(headerText, headerFontSize, header_text_config);
                            builder.appendBitmap(header_text, false);
                        }

                        JSONObject text_config = new JSONObject();
                        try{
                            text_config.put("paperWidth", paperWidth);
                            text_config.put("typeface", m_typeface);
                            text_config.put("alignment", "Normal");
                        } catch (JSONException e) {
                            _callbackContext.error(e.getMessage());
                        }
                        image = createBitmapFromText(text, fontSize, text_config);

                        builder.appendBitmap(image, false);
                        
                        if(footerBase64Image != null && !footerBase64Image.isEmpty()){
                            byte[] footerbase64converted=Base64.decode(footerBase64Image,Base64.DEFAULT);                        
                            Bitmap footer_bitmap = BitmapFactory.decodeByteArray(footerbase64converted,0,footerbase64converted.length);
                            builder.appendBitmap(footer_bitmap, false, paperWidth, true);
                        }


                        try {
                            
                            if (print.has("appendQrCode")){
                                ICommandBuilder.QrCodeModel qrCodeModel =  (print.has("QrCodeModel") ? getQrCodeModel(print.getString("QrCodeModel")): getQrCodeModel("No2"));
                                ICommandBuilder.QrCodeLevel qrCodeLevel = (print.has("QrCodeLevel") ? getQrCodeLevel(print.getString("QrCodeLevel")): getQrCodeLevel("H"));
                                int cell = (print.has("cell") ? print.getInt("cell"): 4);
                                int position = (print.has("absolutePosition") ? print.getInt("absolutePosition") : 0);
                                builder.appendQrCodeWithAbsolutePosition(print.getString("appendQrCode").getBytes(encoding), qrCodeModel, qrCodeLevel, cell, position);
                            } else if (print.has("appendBarcode")){
                                ICommandBuilder.BarcodeSymbology barcodeSymbology = (print.has("BarcodeSymbology") ? getBarcodeSymbology(print.getString("BarcodeSymbology")): getBarcodeSymbology("Code128"));
                                ICommandBuilder.BarcodeWidth barcodeWidth = (print.has("BarcodeWidth") ? getBarcodeWidth(print.getString("BarcodeWidth")): getBarcodeWidth("Mode2"));
                                int height = (print.has("height") ? print.getInt("height"): 40);
                                Boolean hri = (print.has("hri") ? print.getBoolean("hri"): true);
                                int position = (print.has("absolutePosition") ? print.getInt("absolutePosition"): 0);
                                builder.appendBarcodeWithAbsolutePosition(print.getString("appendBarcode").getBytes(encoding), barcodeSymbology, barcodeWidth, height, hri, position);
                            }

                        } catch (JSONException e) {
                            _callbackContext.error(e.getMessage());
                        }
                        if (poweredBy != null && !poweredBy.isEmpty()){
                            JSONObject powered_config = new JSONObject();
                            try{
                                powered_config.put("paperWidth", paperWidth);
                                powered_config.put("typeface", typeface);
                                powered_config.put("alignment", "Center");
                            } catch (JSONException e) {
                                _callbackContext.error(e.getMessage());
                            }
                            Bitmap poweredByImage = createBitmapFromText(poweredBy, fontSize, powered_config);
                            builder.appendBitmap(poweredByImage, false);
                        }


                        if(cutReceipt){
                            builder.appendCutPaper(CutPaperAction.PartialCutWithFeed);
                        }

                        if(openCashDrawer){
                            builder.appendPeripheral(ICommandBuilder.PeripheralChannel.No1); // Kick cash drawer No1
                            builder.appendPeripheral(ICommandBuilder.PeripheralChannel.No2); // Kick cash drawer No2
                        }

                        builder.endDocument();

                        byte[] commands = builder.getCommands();


                        if(_portName == "null"){ // use StarIOExtManager
                            if (guardPersistentBluetoothPermissions(_callbackContext)) {
                                sendCommand(commands, starIoExtManager.getPort(), _callbackContext);
                            }

                        }else{//use StarIOPort
                            sendCommand(context, _portName, _portSettings, commands, _callbackContext);
                        }

                    }
                });
    }
    private void printBase64Image(String portName, String portSettings, Emulation emulation, String printObj, CallbackContext callbackContext) throws JSONException {

        final Context context = this.cordova.getActivity();
        final String _portName = portName;
        final String _portSettings = portSettings;
        final Emulation _emulation = emulation;
        final JSONObject print = new JSONObject(printObj);
        final int width = (print.has("width")) ? print.getInt("width") : 576;
        final Boolean cutReceipt = (print.has("cutReceipt") ? print.getBoolean("cutReceipt"): true);
        final Boolean openCashDrawer = (print.has("openCashDrawer")) ? print.getBoolean("openCashDrawer") : true;
        final CallbackContext _callbackContext = callbackContext;
        final String base64Image = print.getString("base64Image");

        cordova.getThreadPool()
                .execute(new Runnable() {
                    public void run() {

                        Typeface typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
                        ICommandBuilder builder = StarIoExt.createCommandBuilder(_emulation);

                        builder.beginDocument();
                        byte[] base64converted=Base64.decode(base64Image,Base64.DEFAULT);                        
                        Bitmap bitmap = BitmapFactory.decodeByteArray(base64converted,0,base64converted.length);
                        builder.appendBitmap(bitmap, false, width, true);

                        if(cutReceipt){
                            builder.appendCutPaper(CutPaperAction.PartialCutWithFeed);
                        }

                        if(openCashDrawer){
                            builder.appendPeripheral(ICommandBuilder.PeripheralChannel.No1); // Kick cash drawer No1
                            builder.appendPeripheral(ICommandBuilder.PeripheralChannel.No2); // Kick cash drawer No2
                        }

                        builder.endDocument();
                        byte[] commands = builder.getCommands();

                        if(_portName == "null"){ // use StarIOExtManager
                            if (guardPersistentBluetoothPermissions(_callbackContext)) {
                                sendCommand(commands, starIoExtManager.getPort(), _callbackContext);
                            }

                        }else{//use StarIOPort
                            sendCommand(context, _portName, _portSettings, commands, _callbackContext);
                        }
                    }
                });
    }
    private void print(String portName, String portSettings, Emulation emulation, JSONArray printCommands, CallbackContext callbackContext) throws JSONException {

        final Context context = this.cordova.getActivity();
        final String _portName = portName;
        final String _portSettings = portSettings;
        final Emulation _emulation = emulation;
        final JSONArray _printCommands = printCommands;

        final CallbackContext _callbackContext = callbackContext;

        cordova.getThreadPool()
                .execute(new Runnable() {
                    public void run() {

                        ICommandBuilder builder = StarIoExt.createCommandBuilder(_emulation);

                        builder.beginDocument();

                        appendCommands(builder, _printCommands, context);

                        builder.endDocument();

                        byte[] commands = builder.getCommands();

                        if(_portName == "null"){ // use StarIOExtManager
                            if (guardPersistentBluetoothPermissions(_callbackContext)) {
                                sendCommand(commands, starIoExtManager.getPort(), _callbackContext);
                            }

                        }else{//use StarIOPort
                            sendCommand(context, _portName, _portSettings, commands, _callbackContext);
                        }
                    }
                });
    }

    private void printRasterData(String portName, String portSettings, Emulation emulation, String printObj, CallbackContext callbackContext) throws IOException, JSONException {

        final Context context = this.cordova.getActivity();
        final ContentResolver contentResolver = context.getContentResolver();
        final String _portName = portName;
        final String _portSettings = portSettings;
        final Emulation _emulation = emulation;
        final JSONObject print = new JSONObject(printObj);
        final String uriString = print.optString("uri");
        final int width = (print.has("width")) ? print.getInt("width") : 576;
        final Boolean cutReceipt = (print.has("cutReceipt") ? print.getBoolean("cutReceipt"): true);
        final Boolean openCashDrawer = (print.has("openCashDrawer")) ? print.getBoolean("openCashDrawer") : true;
        final CallbackContext _callbackContext = callbackContext;

        cordova.getThreadPool()
                .execute(new Runnable() {
                    public void run() {

                        Uri imageUri = null;
                        Bitmap bitmap = null;
                        try {
                            imageUri =  Uri.parse(uriString);
                            bitmap = MediaStore.Images.Media.getBitmap(contentResolver, imageUri);
                        } catch (IOException e) {
                            _callbackContext.error(e.getMessage());
                        }

                        ICommandBuilder builder = StarIoExt.createCommandBuilder(_emulation);

                        builder.beginDocument();

                        builder.appendBitmap(bitmap, true, width, true);

                        if(cutReceipt){
                            builder.appendCutPaper(CutPaperAction.PartialCutWithFeed);
                        }

                        if(openCashDrawer){
                            builder.appendPeripheral(ICommandBuilder.PeripheralChannel.No1); // Kick cash drawer No1
                            builder.appendPeripheral(ICommandBuilder.PeripheralChannel.No2); // Kick cash drawer No2
                        }


                        builder.endDocument();

                        byte[] commands = builder.getCommands();

                        if(_portName == "null"){ // use StarIOExtManager
                            if (guardPersistentBluetoothPermissions(_callbackContext)) {
                                sendCommand(commands, starIoExtManager.getPort(), _callbackContext);
                            }

                        }else{//use StarIOPort
                            sendCommand(context, _portName, _portSettings, commands, _callbackContext);
                        }

                    }
                });
    }
    private void openCashDrawer(String portName, String portSettings, Emulation emulation, CallbackContext callbackContext) throws JSONException {
        final Context context = this.cordova.getActivity();
        final String _portName = portName;
        final String _portSettings = portSettings;
        final Emulation _emulation = emulation;
        final CallbackContext _callbackContext = callbackContext;

        cordova.getThreadPool()
                .execute(new Runnable() {
                    public void run() {

                        ICommandBuilder builder = StarIoExt.createCommandBuilder(_emulation);

                        builder.beginDocument();

                        builder.appendPeripheral(ICommandBuilder.PeripheralChannel.No1);
                        builder.appendPeripheral(ICommandBuilder.PeripheralChannel.No2);

                        builder.endDocument();

                        byte[] commands = builder.getCommands();

                        if(_portName == "null"){ // use StarIOExtManager
                            if (guardPersistentBluetoothPermissions(_callbackContext)) {
                                sendCommand(commands, starIoExtManager.getPort(), _callbackContext);
                            }

                        }else{//use StarIOPort
                            sendCommand(context, _portName, _portSettings, commands, _callbackContext);
                        }

                    }
                });


    }

    private boolean sendCommand(byte[] commands, StarIOPort port, CallbackContext callbackContext) {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
        }
        if (port == null) {
            callbackContext.error("Unable to Open Port, Please Connect to the printer before sending commands");
            return false;
        }

        try {
			/*
			 * Using Begin / End Checked Block method When sending large amounts of raster data,
			 * adjust the value in the timeout in the "StarIOPort.getPort" in order to prevent
			 * "timeout" of the "endCheckedBlock method" while a printing.
			 *
			 * If receipt print is success but timeout error occurs(Show message which is "There
			 * was no response of the printer within the timeout period." ), need to change value
			 * of timeout more longer in "StarIOPort.getPort" method.
			 * (e.g.) 10000 -> 30000
			 */
            StarPrinterStatus status = port.beginCheckedBlock();

            if (status.offline) {
                throw new StarIOPortException("A printer is offline");
            }

            port.writePort(commands, 0, commands.length);
            port.setEndCheckedBlockTimeoutMillis(30000);
            status = port.endCheckedBlock();

            if (status.coverOpen) {
                callbackContext.error("Cover open");
                return false;
            } else if (status.receiptPaperEmpty) {
                callbackContext.error("Empty paper");
                return false;
            } else if (status.offline) {
                callbackContext.error("Printer offline");
                return false;
            }
            callbackContext.success("Success!");
            return true;
        } catch (StarIOPortException e) {
            callbackContext.error(e.getMessage());
            return false;
        } catch (SecurityException e) {
            reportBluetoothPermissionError(callbackContext, e);
            return false;
        }
    }
    private boolean sendCommand(Context context, String portName, String portSettings, byte[] commands, CallbackContext callbackContext) {
        if (isBluetoothPortName(portName) && !guardBluetoothPermissions(callbackContext)) {
            return false;
        }

        StarIOPort port = null;
        try {
			/*
			 * using StarIOPort3.1.jar (support USB Port) Android OS Version: upper 2.2
			 */
            port = StarIOPort.getPort(portName, portSettings, 10000, context);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }

			/*
			 * Using Begin / End Checked Block method When sending large amounts of raster data,
			 * adjust the value in the timeout in the "StarIOPort.getPort" in order to prevent
			 * "timeout" of the "endCheckedBlock method" while a printing.
			 *
			 * If receipt print is success but timeout error occurs(Show message which is "There
			 * was no response of the printer within the timeout period." ), need to change value
			 * of timeout more longer in "StarIOPort.getPort" method.
			 * (e.g.) 10000 -> 30000
			 */
            StarPrinterStatus status = port.beginCheckedBlock();

            if (status.offline) {
                callbackContext.error("The printer is offline");
                return false;
            }

            port.writePort(commands, 0, commands.length);
            port.setEndCheckedBlockTimeoutMillis(30000);
            status = port.endCheckedBlock();

            if (status.coverOpen) {
                callbackContext.error("Cover open");
                return false;
            } else if (status.receiptPaperEmpty) {
                callbackContext.error("Empty paper");
                return false;
            } else if (status.offline) {
                callbackContext.error("Printer offline");
                return false;
            }
            callbackContext.success("Success!");
            return true;
        } catch (StarIOPortException e) {
            callbackContext.error(e.getMessage());
            return false;
        } catch (SecurityException e) {
            reportBluetoothPermissionError(callbackContext, e);
            return false;
        } finally {
            if (port != null) {
                try {
                    StarIOPort.releasePort(port);
                } catch (StarIOPortException e) {
                }
            }
        }
    }

    private void appendCommands(ICommandBuilder builder, JSONArray printCommands, Context context) {
        Charset encoding = Charset.forName("US-ASCII");
        try {
            for (int i = 0; i < printCommands.length(); i++) {
                JSONObject command = (JSONObject) printCommands.get(i);
                if(command.has("appendCharacterSpace")) builder.appendCharacterSpace(command.getInt("appendCharacterSpace"));
                else if (command.has("appendEncoding")) encoding = getEncoding(command.getString("appendEncoding"));
                else if (command.has("appendCodePage")) builder.appendCodePage(getCodePageType(command.getString("appendCodePage")));
                else if (command.has("append")) builder.append(command.getString("append").getBytes(encoding));
                else if (command.has("appendRaw")) builder.append(command.getString("appendRaw").getBytes(encoding));
                else if (command.has("appendEmphasis")) builder.appendEmphasis(command.getString("appendEmphasis").getBytes(encoding));
                else if (command.has("enableEmphasis")) builder.appendEmphasis(command.getBoolean("enableEmphasis"));
                else if (command.has("appendInvert")) builder.appendInvert(command.getString("appendInvert").getBytes(encoding));
                else if (command.has("enableInvert")) builder.appendInvert(command.getBoolean("enableInvert"));
                else if (command.has("appendUnderline")) builder.appendUnderLine(command.getString("appendUnderline").getBytes(encoding));
                else if (command.has("enableUnderline")) builder.appendUnderLine(command.getBoolean("enableUnderline"));
                else if (command.has("appendInternational")) builder.appendInternational(getInternational(command.getString("appendInternational")));
                else if (command.has("appendLineFeed")) builder.appendLineFeed(command.getInt("appendLineFeed"));
                else if (command.has("appendUnitFeed")) builder.appendUnitFeed(command.getInt("appendUnitFeed"));
                else if (command.has("appendLineSpace")) builder.appendLineSpace(command.getInt("appendLineSpace"));
                else if (command.has("appendFontStyle")) builder.appendFontStyle(getFontStyle(command.getString("appendFontStyle")));
                else if (command.has("appendCutPaper")) {
                    Object cutPaper = command.get("appendCutPaper");
                    String cutPaperAction = cutPaper instanceof String ? (String) cutPaper : "PartialCutWithFeed";
                    builder.appendCutPaper(getCutPaperAction(cutPaperAction));
                }
                else if (command.has("openCashDrawer")) builder.appendPeripheral(getPeripheralChannel(command.getInt("openCashDrawer")));
                else if (command.has("appendBlackMark")) builder.appendBlackMark(getBlackMarkType(command.getString("appendBlackMark")));
                else if (command.has("appendBytes")) {
                    JSONArray bytesArray = command.getJSONArray("appendBytes");
                    if (bytesArray == null ) bytesArray = new JSONArray();
                    byte[] byteData = new byte[bytesArray.length()+1];
                    for(int j=0; j < bytesArray.length(); j++){
                        byteData[j] = (byte)bytesArray.getInt(j);
                    }
                    builder.append(byteData);
                }else if (command.has("appendRawBytes")) {
                        JSONArray rawBytesArray = command.getJSONArray("appendRawBytes");
                        if (rawBytesArray == null ) rawBytesArray = new JSONArray();
                        byte[] rawByteData = new byte[rawBytesArray.length()+1];
                        for(int j=0; j < rawBytesArray.length(); j++){
                            rawByteData[j] = (byte)rawBytesArray.getInt(j);
                        }
                        builder.appendRaw(rawByteData);
                }else if (command.has("appendAbsolutePosition")) {
                    if(command.has("data")) builder.appendAbsolutePosition((command.getString("data").getBytes(encoding)), command.getInt("appendAbsolutePosition"));
                    else builder.appendAbsolutePosition(command.getInt("appendAbsolutePosition"));
                } else if (command.has("appendAlignment")) {
                    if(command.has("data")) builder.appendAlignment((command.getString("data").getBytes(encoding)), getAlignment(command.getString("appendAlignment")));
                    else builder.appendAlignment(getAlignment(command.getString("appendAlignment")));
                } else if (command.has("appendHorizontalTabPosition")) {
                    JSONArray tabPositionsArray = command.getJSONArray("appendHorizontalTabPosition");
                    if (tabPositionsArray == null ) tabPositionsArray = new JSONArray();
                    int[] tabPositions = new int[tabPositionsArray.length()];
                    for(int j=0; j < tabPositionsArray.length(); j++){
                        tabPositions[j] = tabPositionsArray.optInt(j);
                    }
                    builder.appendHorizontalTabPosition(tabPositions);
                } else if (command.has("appendLogo")){
                    ICommandBuilder.LogoSize logoSize = (command.has("logoSize") ? getLogoSize(command.getString("logoSize")): getLogoSize("Normal"));
                    builder.appendLogo(logoSize, command.getInt("appendLogo"));
                } else if (command.has("appendBarcode")){
                    ICommandBuilder.BarcodeSymbology barcodeSymbology =  (command.has("BarcodeSymbology") ? getBarcodeSymbology(command.getString("BarcodeSymbology")): getBarcodeSymbology("Code128"));
                    ICommandBuilder.BarcodeWidth barcodeWidth = (command.has("BarcodeWidth") ? getBarcodeWidth(command.getString("BarcodeWidth")): getBarcodeWidth("Mode2"));
                    int height = (command.has("height") ? command.getInt("height"): 40);
                    Boolean hri = (command.has("hri") ? command.getBoolean("hri"): true);
                    if(command.has("absolutePosition")){
                        int position =  command.getInt("absolutePosition");
                        builder.appendBarcodeWithAbsolutePosition(command.getString("appendBarcode").getBytes(encoding), barcodeSymbology, barcodeWidth, height, hri, position);
                    }else if(command.has("alignment")){
                        ICommandBuilder.AlignmentPosition alignmentPosition = getAlignment(command.getString("alignment"));
                        builder.appendBarcodeWithAlignment(command.getString("appendBarcode").getBytes(encoding), barcodeSymbology, barcodeWidth, height, hri, alignmentPosition);
                    }else builder.appendBarcode(command.getString("appendBarcode").getBytes(encoding), barcodeSymbology, barcodeWidth, height, hri);
                } else if (command.has("appendMultiple")){
                    int width = (command.has("width") ? command.getInt("width"): 1);
                    int height = (command.has("height") ? command.getInt("height"): 1);
                    builder.appendMultiple(command.getString("appendMultiple").getBytes(encoding), width, height);
                } else if (command.has("enableMultiple")){
                    int width = (command.has("width") ? command.getInt("width"): 1);
                    int height = (command.has("height") ? command.getInt("height"): 1);
                    Boolean enableMultiple = command.getBoolean("enableMultiple");
                    if(enableMultiple) builder.appendMultiple(width, height);
                    else builder.appendMultiple(1,1); // Reset to default when false sent
                } else if (command.has("appendQrCode")){
                    ICommandBuilder.QrCodeModel qrCodeModel =  (command.has("QrCodeModel") ? getQrCodeModel(command.getString("QrCodeModel")): getQrCodeModel("No2"));
                    ICommandBuilder.QrCodeLevel qrCodeLevel = (command.has("QrCodeLevel") ? getQrCodeLevel(command.getString("QrCodeLevel")): getQrCodeLevel("H"));
                    int cell = command.has("cell") ? command.getInt("cell") :
                               command.has("cellSize") ? command.getInt("cellSize") : 4;
                    if(command.has("absolutePosition")){
                        int position =  command.getInt("absolutePosition");
                        builder.appendQrCodeWithAbsolutePosition(command.getString("appendQrCode").getBytes(encoding), qrCodeModel, qrCodeLevel, cell, position);
                    }else if(command.has("alignment")){
                        ICommandBuilder.AlignmentPosition alignmentPosition = getAlignment(command.getString("alignment"));
                        builder.appendQrCodeWithAlignment(command.getString("appendQrCode").getBytes(encoding), qrCodeModel, qrCodeLevel, cell, alignmentPosition);
                    }else builder.appendQrCode(command.getString("appendQrCode").getBytes(encoding), qrCodeModel, qrCodeLevel, cell);
                } else if (command.has("appendBitmap")){
                    String uriString = command.getString("appendBitmap");
                    if (uriString != null) {
                        getAndCacheImage(uriString, command, builder, context);
                    }
                } else if (command.has("appendBitmapBase64")) {
                    String base64Image = command.getString("appendBitmapBase64");
                    if (base64Image != null && !base64Image.isEmpty()) {
                        try {
                            byte[] decoded = Base64.decode(base64Image, Base64.DEFAULT);
                            Bitmap bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                            if (bitmap != null) {
                                processBitmap(bitmap, command, builder);
                            } else {
                                Log.e(TAG, "appendBitmapBase64: failed to decode bitmap");
                            }
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "appendBitmapBase64 decode error: " + e.getMessage());
                        }
                    }
                } else if (command.has("text")){
                    Bitmap image = createBitmapFromTextField(command);
                    if(image != null){
                        builder.appendBitmap(image, false);
                    }
                } else if (command.has("textArray")){
                    JSONArray textArray = command.getJSONArray("textArray");
                    if(textArray != null){
                        Bitmap image = createBitmapFromTextArray(textArray, true);
                        if(image != null){
                            builder.appendBitmap(image, false);
                        }
                    }
                } else if (command.has("drawLine")){
                    int thickness = command.getInt("drawLine");
                    int position = command.has("position") ? command.getInt("position") : 0;
                    int width = command.has("width") ? command.getInt("width") : 576;
                    int marginTop = command.has("marginTop") ? command.getInt("marginTop") : 11;
                    int marginBottom = command.has("marginBottom") ? command.getInt("marginBottom") : 11;
                    builder.appendBitmap(drawLine(position, width, thickness, marginTop, marginBottom), false);
                }
            }

        } catch (JSONException e) {

        }

    };

    //ICommandBuilder Constant Functions
    private ICommandBuilder.InternationalType getInternational(String international){
        if(international.equals("UK")) return ICommandBuilder.InternationalType.UK;
        else if (international.equals("USA")) return ICommandBuilder.InternationalType.USA;
        else if (international.equals("France")) return ICommandBuilder.InternationalType.France;
        else if (international.equals("Germany")) return ICommandBuilder.InternationalType.Germany;
        else if (international.equals("Denmark")) return ICommandBuilder.InternationalType.Denmark;
        else if (international.equals("Sweden")) return ICommandBuilder.InternationalType.Sweden;
        else if (international.equals("Italy")) return ICommandBuilder.InternationalType.Italy;
        else if (international.equals("Spain")) return ICommandBuilder.InternationalType.Spain;
        else if (international.equals("Japan")) return ICommandBuilder.InternationalType.Japan;
        else if (international.equals("Norway")) return ICommandBuilder.InternationalType.Norway;
        else if (international.equals("Denmark2")) return ICommandBuilder.InternationalType.Denmark2;
        else if (international.equals("Spain2")) return ICommandBuilder.InternationalType.Spain2;
        else if (international.equals("LatinAmerica")) return ICommandBuilder.InternationalType.LatinAmerica;
        else if (international.equals("Korea")) return ICommandBuilder.InternationalType.Korea;
        else if (international.equals("Ireland")) return ICommandBuilder.InternationalType.Ireland;
        else if (international.equals("Legal")) return ICommandBuilder.InternationalType.Legal;
        else return ICommandBuilder.InternationalType.USA;
    };

    private ICommandBuilder.AlignmentPosition getAlignment(String alignment){
        if(alignment.equals("Left")) return ICommandBuilder.AlignmentPosition.Left;
        else if(alignment.equals("Center")) return ICommandBuilder.AlignmentPosition.Center;
        else if(alignment.equals("Right")) return ICommandBuilder.AlignmentPosition.Right;
        else return ICommandBuilder.AlignmentPosition.Left;
    }

    private Layout.Alignment getLayoutAlignment(String alignment){
        if(alignment.equals("Opposite")) return Layout.Alignment.ALIGN_OPPOSITE;
        else if(alignment.equals("Center")) return Layout.Alignment.ALIGN_CENTER;
        else return Layout.Alignment.ALIGN_NORMAL;
    }

    private ICommandBuilder.BarcodeSymbology getBarcodeSymbology(String barcodeSymbology){
        if(barcodeSymbology.equals("Code128")) return ICommandBuilder.BarcodeSymbology.Code128;
        else if (barcodeSymbology.equals("Code39")) return ICommandBuilder.BarcodeSymbology.Code39;
        else if (barcodeSymbology.equals("Code93")) return ICommandBuilder.BarcodeSymbology.Code93;
        else if (barcodeSymbology.equals("ITF")) return ICommandBuilder.BarcodeSymbology.ITF;
        else if (barcodeSymbology.equals("JAN8")) return ICommandBuilder.BarcodeSymbology.JAN8;
        else if (barcodeSymbology.equals("JAN13")) return ICommandBuilder.BarcodeSymbology.JAN13;
        else if (barcodeSymbology.equals("NW7")) return ICommandBuilder.BarcodeSymbology.NW7;
        else if (barcodeSymbology.equals("UPCA")) return ICommandBuilder.BarcodeSymbology.UPCA;
        else if (barcodeSymbology.equals("UPCE")) return ICommandBuilder.BarcodeSymbology.UPCE;
        else return ICommandBuilder.BarcodeSymbology.Code128;
    }
    private ICommandBuilder.BarcodeWidth getBarcodeWidth (String barcodeWidth){
        if(barcodeWidth.equals("Mode1")) return ICommandBuilder.BarcodeWidth.Mode1;
        if(barcodeWidth.equals("Mode2")) return ICommandBuilder.BarcodeWidth.Mode2;
        if(barcodeWidth.equals("Mode3")) return ICommandBuilder.BarcodeWidth.Mode3;
        if(barcodeWidth.equals("Mode4")) return ICommandBuilder.BarcodeWidth.Mode4;
        if(barcodeWidth.equals("Mode5")) return ICommandBuilder.BarcodeWidth.Mode5;
        if(barcodeWidth.equals("Mode6")) return ICommandBuilder.BarcodeWidth.Mode6;
        if(barcodeWidth.equals("Mode7")) return ICommandBuilder.BarcodeWidth.Mode7;
        if(barcodeWidth.equals("Mode8")) return ICommandBuilder.BarcodeWidth.Mode8;
        if(barcodeWidth.equals("Mode9")) return ICommandBuilder.BarcodeWidth.Mode9;
        return ICommandBuilder.BarcodeWidth.Mode2;
    }
    private ICommandBuilder.FontStyleType getFontStyle(String fontStyle){
        if(fontStyle.equals("A")) return ICommandBuilder.FontStyleType.A;
        if(fontStyle.equals("B")) return ICommandBuilder.FontStyleType.B;
        return ICommandBuilder.FontStyleType.A;
    }
    private ICommandBuilder.LogoSize getLogoSize(String logoSize){
        if(logoSize.equals("Normal")) return ICommandBuilder.LogoSize.Normal;
        else if(logoSize.equals("DoubleWidth")) return ICommandBuilder.LogoSize.DoubleWidth;
        else if(logoSize.equals("DoubleHeight")) return ICommandBuilder.LogoSize.DoubleHeight;
        else if(logoSize.equals("DoubleWidthDoubleHeight")) return ICommandBuilder.LogoSize.DoubleWidthDoubleHeight;
        else return ICommandBuilder.LogoSize.Normal;
    }

    private ICommandBuilder.CutPaperAction getCutPaperAction(String cutPaperAction){
        if(cutPaperAction.equals("FullCut")) return CutPaperAction.FullCut;
        else if(cutPaperAction.equals("FullCutWithFeed")) return CutPaperAction.FullCutWithFeed;
        else if(cutPaperAction.equals("PartialCut")) return CutPaperAction.PartialCut;
        else if(cutPaperAction.equals("PartialCutWithFeed")) return CutPaperAction.PartialCutWithFeed;
        else return CutPaperAction.PartialCutWithFeed;
    }
    private ICommandBuilder.PeripheralChannel getPeripheralChannel(int peripheralChannel){
        if(peripheralChannel == 1) return ICommandBuilder.PeripheralChannel.No1;
        else if(peripheralChannel == 2) return ICommandBuilder.PeripheralChannel.No2;
        else return ICommandBuilder.PeripheralChannel.No1;
    }
    private ICommandBuilder.QrCodeModel getQrCodeModel (String qrCodeModel){
        if(qrCodeModel.equals("No1")) return ICommandBuilder.QrCodeModel.No1;
        else if(qrCodeModel.equals("No2")) return ICommandBuilder.QrCodeModel.No2;
        else return ICommandBuilder.QrCodeModel.No1;
    }
    private ICommandBuilder.QrCodeLevel getQrCodeLevel (String qrCodeLevel){
        if(qrCodeLevel.equals("H")) return ICommandBuilder.QrCodeLevel.H;
        else if(qrCodeLevel.equals("L")) return ICommandBuilder.QrCodeLevel.L;
        else if(qrCodeLevel.equals("M")) return ICommandBuilder.QrCodeLevel.M;
        else if(qrCodeLevel.equals("Q")) return ICommandBuilder.QrCodeLevel.Q;
        else return ICommandBuilder.QrCodeLevel.H;
    }
    private ICommandBuilder.BitmapConverterRotation getConverterRotation (String converterRotation){
        if(converterRotation.equals("Normal")) return ICommandBuilder.BitmapConverterRotation.Normal;
        else if(converterRotation.equals("Left90")) return ICommandBuilder.BitmapConverterRotation.Left90;
        else if(converterRotation.equals("Right90")) return ICommandBuilder.BitmapConverterRotation.Right90;
        else if(converterRotation.equals("Rotate180")) return ICommandBuilder.BitmapConverterRotation.Rotate180;
        else return ICommandBuilder.BitmapConverterRotation.Normal;
    }
    private ICommandBuilder.BlackMarkType getBlackMarkType(String blackMarkType){
        if(blackMarkType.equals("Valid")) return ICommandBuilder.BlackMarkType.Valid;
        else if(blackMarkType.equals("Invalid")) return ICommandBuilder.BlackMarkType.Invalid;
        else if(blackMarkType.equals("ValidWithDetection")) return ICommandBuilder.BlackMarkType.ValidWithDetection;
        else return ICommandBuilder.BlackMarkType.Valid;
    }
    private ICommandBuilder.CodePageType getCodePageType(String codePageType){
        if (codePageType.equals("CP437")) return CodePageType.CP437;
        else if (codePageType.equals("CP737")) return CodePageType.CP737;
        else if (codePageType.equals("CP772")) return CodePageType.CP772;
        else if (codePageType.equals("CP774")) return CodePageType.CP774;
        else if (codePageType.equals("CP851")) return CodePageType.CP851;
        else if (codePageType.equals("CP852")) return CodePageType.CP852;
        else if (codePageType.equals("CP855")) return CodePageType.CP855;
        else if (codePageType.equals("CP857")) return CodePageType.CP857;
        else if (codePageType.equals("CP858")) return CodePageType.CP858;
        else if (codePageType.equals("CP860")) return CodePageType.CP860;
        else if (codePageType.equals("CP861")) return CodePageType.CP861;
        else if (codePageType.equals("CP862")) return CodePageType.CP862;
        else if (codePageType.equals("CP863")) return CodePageType.CP863;
        else if (codePageType.equals("CP864")) return CodePageType.CP864;
        else if (codePageType.equals("CP865")) return CodePageType.CP866;
        else if (codePageType.equals("CP869")) return CodePageType.CP869;
        else if (codePageType.equals("CP874")) return CodePageType.CP874;
        else if (codePageType.equals("CP928")) return CodePageType.CP928;
        else if (codePageType.equals("CP932")) return CodePageType.CP932;
        else if (codePageType.equals("CP999")) return CodePageType.CP999;
        else if (codePageType.equals("CP1001")) return CodePageType.CP1001;
        else if (codePageType.equals("CP1250")) return CodePageType.CP1250;
        else if (codePageType.equals("CP1251")) return CodePageType.CP1251;
        else if (codePageType.equals("CP1252")) return CodePageType.CP1252;
        else if (codePageType.equals("CP2001")) return CodePageType.CP2001;
        else if (codePageType.equals("CP3001")) return CodePageType.CP3001;
        else if (codePageType.equals("CP3002")) return CodePageType.CP3002;
        else if (codePageType.equals("CP3011")) return CodePageType.CP3011;
        else if (codePageType.equals("CP3012")) return CodePageType.CP3012;
        else if (codePageType.equals("CP3021")) return CodePageType.CP3021;
        else if (codePageType.equals("CP3041")) return CodePageType.CP3041;
        else if (codePageType.equals("CP3840")) return CodePageType.CP3840;
        else if (codePageType.equals("CP3841")) return CodePageType.CP3841;
        else if (codePageType.equals("CP3843")) return CodePageType.CP3843;
        else if (codePageType.equals("CP3845")) return CodePageType.CP3845;
        else if (codePageType.equals("CP3846")) return CodePageType.CP3846;
        else if (codePageType.equals("CP3847")) return CodePageType.CP3847;
        else if (codePageType.equals("CP3848")) return CodePageType.CP3848;
        else if (codePageType.equals("UTF8")) return CodePageType.UTF8;
        else if (codePageType.equals("Blank")) return CodePageType.Blank;
        else return CodePageType.CP998;
    }

    private int getTypefaceStyle(String style){
        if(style.equals("BOLD")) return Typeface.BOLD;
        else if(style.equals("BOLD_ITALIC")) return Typeface.BOLD_ITALIC;
        else if(style.equals("ITALIC")) return Typeface.ITALIC;
        else return Typeface.NORMAL;
    }

    private Typeface getTypeface(String style){
        if(style.equals("SERIF")) return Typeface.SERIF;
        if(style.equals("SANS_SERIF")) return Typeface.SANS_SERIF;
        else if(style.equals("MONOSPACE")) return Typeface.MONOSPACE;
        else if(style.equals("DEFAULT_BOLD")) return Typeface.DEFAULT_BOLD;
        else return Typeface.DEFAULT;
    }


    //Helper functions

    private Charset getEncoding(String encoding){

        if (encoding.equals("US-ASCII")) return Charset.forName("US-ASCII"); //English
        else if (encoding.equals("Windows-1252")){
            try {
                return Charset.forName("Windows-1252"); //French, German, Portuguese, Spanish
            }
            catch (UnsupportedCharsetException e) { //not supported using UTF-8 Instead
                return Charset.forName("UTF-8");
            }
        }
        else if (encoding.equals("Shift-JIS")) {
            try {
                return Charset.forName("Shift-JIS"); //Japanese
            }
            catch (UnsupportedCharsetException e) { //not supported using UTF-8 Instead
                return Charset.forName("UTF-8");
            }
        }
        else if (encoding.equals("Windows-1251")){
            try {
                return Charset.forName("Windows-1251"); //Russian
            }
            catch (UnsupportedCharsetException e) { //not supported using UTF-8 Instead
                return Charset.forName("UTF-8");
            }
        }
        else if (encoding.equals("GB2312")) {
            try {
                return Charset.forName("GB2312"); // Simplified Chinese
            }
            catch (UnsupportedCharsetException e) { //not supported using UTF-8 Instead
                return Charset.forName("UTF-8");
            }
        }
        else if (encoding.equals("Big5")) {
            try {
                return Charset.forName("Big5"); // Traditional Chinese
            }
            catch (UnsupportedCharsetException e) { //not supported using UTF-8 Instead
                return Charset.forName("UTF-8");
            }
        }
        else if (encoding.equals("UTF-8")) return Charset.forName("UTF-8"); // UTF-8
        else return Charset.forName("US-ASCII");
    }

    private byte[] createCpUTF8(String inputText) {
        byte[] byteBuffer = null;

        try {
            byteBuffer = inputText.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            byteBuffer = inputText.getBytes();
        }

        return byteBuffer;
    }


    private byte[] convertFromListByteArrayTobyteArray(List<byte[]> ByteArray) {
        int dataLength = 0;
        for (int i = 0; i < ByteArray.size(); i++) {
            dataLength += ByteArray.get(i).length;
        }

        int distPosition = 0;
        byte[] byteArray = new byte[dataLength];
        for (int i = 0; i < ByteArray.size(); i++) {
            System.arraycopy(ByteArray.get(i), 0, byteArray, distPosition, ByteArray.get(i).length);
            distPosition += ByteArray.get(i).length;
        }

        return byteArray;
    }

    /**
     * Create a new plugin result and send it back to JavaScript
     *
     * @param dataType event type
     */
    private void sendEvent(String dataType, String info) {
        if (this._callbackContext != null) {
            JSONObject status = new JSONObject();
            try {
                status.put("dataType", dataType);
                if (info != null) status.put("data", info);
            }catch (JSONException ex ) {  };
            PluginResult result = new PluginResult(PluginResult.Status.OK, status);
            result.setKeepCallback(true);
            this._callbackContext.sendPluginResult(result);
        }
    }
    private StarIoExtManagerListener starIoExtManagerListener = new StarIoExtManagerListener() {
        @Override
        public void onPrinterImpossible() {
            sendEvent("printerImpossible", null);
        }

        @Override
        public void onPrinterOnline() {
            sendEvent("printerOnline", null);
        }

        @Override
        public void onPrinterOffline() {
           sendEvent("printerOffline", null);
        }

        @Override
        public void onPrinterPaperReady() {
            sendEvent("printerPaperReady", null);
        }

        @Override
        public void onPrinterPaperNearEmpty() {
            sendEvent("printerPaperNearEmpty", null);
        }

        @Override
        public void onPrinterPaperEmpty() {
            sendEvent("printerPaperEmpty", null);
        }

        @Override
        public void onPrinterCoverOpen() {
            sendEvent("printerCoverOpen", null);
        }

        @Override
        public void onPrinterCoverClose() {
            sendEvent("printerCoverClose", null);
        }

        //Cash Drawer events
        @Override
        public void onCashDrawerOpen() {
            sendEvent("cashDrawerOpen", null);
        }

        @Override
        public void onCashDrawerClose() {
            sendEvent("cashDrawerClose", null);
        }

        @Override
        public void onBarcodeReaderImpossible() {
            sendEvent("barcodeReaderImpossible", null);
        }

        @Override
        public void onBarcodeReaderConnect() {
            sendEvent("barcodeReaderConnect", null);
        }

        @Override
        public void onBarcodeReaderDisconnect() {
            sendEvent("barcodeReaderDisconnect", null);
        }

        @Override
        public void onBarcodeDataReceive(byte[] data) {
            sendEvent("barcodeDataReceive", new String(data));
        }

    };

    private Bitmap createBitmapFromText(String printText, int textSize, JSONObject config) {
        int printWidth = 576;
        int typefaceStyle = Typeface.NORMAL;
        Typeface typeface = null;
        String alignment = "Normal";
        Boolean inverted = false;
        int paddingTop = 0;
        int paddingRight = 0;
        int paddingBottom = 0;
        int paddingLeft = 0;
        boolean doubleHeight = false;
        try{
            if(config.has("width") ){
                printWidth = config.getInt("width");
            }
            if(config.has("typefaceStyle") ){
                typefaceStyle = getTypefaceStyle(config.getString("typefaceStyle"));
            }
            if(config.has("typeface") ){
                typeface = Typeface.create(getTypeface(config.getString("typeface")), typefaceStyle);
            }
            if(config.has("alignment") ){
                alignment = config.getString("alignment");
            }
            if(config.has("inverted") ){
                inverted = config.getBoolean("inverted");
            }
            if(config.has("paddingTop") ){
                paddingTop = config.getInt("paddingTop");
            }
            if(config.has("paddingRight") ){
                paddingRight = config.getInt("paddingRight");
            }
            if(config.has("paddingBottom") ){
                paddingBottom = config.getInt("paddingBottom");
            }
            if(config.has("paddingLeft") ){
                paddingLeft = config.getInt("paddingLeft");
            }
            if (config.has("doubleHeight")) {
                doubleHeight = config.getBoolean("doubleHeight");
            }
        } catch (JSONException e) {
            _callbackContext.error(e.getMessage());
            return null;
        }
        if(typeface == null){
            typeface = Typeface.create(Typeface.DEFAULT, typefaceStyle);
        }

        if (doubleHeight) {
            textSize *= 2;
        }

        Paint paint = new Paint();
        Bitmap bitmap;
        Canvas canvas;

        paint.setTextSize(textSize);
        paint.setTypeface(typeface);
        if (doubleHeight) {
            printWidth *= 2;
            paddingLeft *= 2;
            paddingRight *= 2;
        }

        int maxTextWidth = printWidth - (paddingLeft + paddingRight);
        paint.getTextBounds(printText, 0, printText.length(), new Rect());

        TextPaint textPaint = new TextPaint(paint);
        if(inverted){
            textPaint.setColor(Color.WHITE);
        }
        android.text.StaticLayout staticLayout = new StaticLayout(printText, textPaint, maxTextWidth, getLayoutAlignment(alignment), 1, 0, false);

        // Calculate the required width and height of the text
        int textWidth = staticLayout.getWidth();
        int textHeight = staticLayout.getHeight();

        // Calculate the final width and height of the bitmap
        int width = textWidth + paddingLeft + paddingRight;
        int height = textHeight + paddingTop + paddingBottom;
        // Create bitmap
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        float x = paddingLeft;
        float y = paddingTop;
        // Create canvas
        canvas = new Canvas(bitmap);
        canvas.drawColor(inverted ? Color.BLACK : Color.WHITE);
        canvas.translate(x, y);
        staticLayout.draw(canvas);

        if (doubleHeight) {
            int newWidth = width / 2;

            Bitmap resizedBitmap = Bitmap.createBitmap(newWidth, height, bitmap.getConfig());

            Canvas c = new Canvas(resizedBitmap);

            Rect srcRect = new Rect(0, 0, width, height);
            Rect destRect = new Rect(0, 0, newWidth, height);
            c.drawBitmap(bitmap, srcRect, destRect, null);

            return resizedBitmap;
        }

        return bitmap;
    }

    private Bitmap createBitmapFromTextArray(JSONArray data, Boolean isHorizontal) {
        final ArrayList<Bitmap> bitmaps = new ArrayList<Bitmap>();
        Bitmap bitmap;
        try {
            for (int i = 0; i < data.length(); i++) {
                JSONObject field = (JSONObject) data.get(i);
                bitmap = null;
                String text = null;
                try {
                    text = field.getString("text");
                } catch (JSONException e) {
                }
                if (text != null) {
                    bitmap = createBitmapFromTextField(field);
                } else if(field.has("textArray")) {
                    try {
                        JSONArray textArray = field.getJSONArray("textArray");
                        bitmap = createBitmapFromTextArray(textArray, !isHorizontal);
                    } catch (JSONException e) {
                    }
                    if(bitmap != null){
                        int paperWidth = field.has("width") ? field.getInt("width") : 576;
                        if(field.has("absolutePosition") || field.has("alignment")){
                            int position = 0;
                            if(field.has("absolutePosition")){
                                position = field.getInt("absolutePosition");
                            } else if(field.has("alignment")) {
                                String alignment = field.getString("alignment");
                                if(alignment.equals("Opposite")){
                                    position = paperWidth - bitmap.getWidth();
                                } else if(alignment.equals("Center")){
                                    position = (paperWidth - bitmap.getWidth()) / 2;
                                }
                            }
                            if(position > 0){
                                bitmap = marginLeft(bitmap, position);
                            }
                        }
                    }
                }
                if(bitmap != null){
                    bitmaps.add(bitmap);
                }
            }
        } catch (JSONException e) {

        }
        if(bitmaps.size() > 0){
            return combineBitmaps(bitmaps, isHorizontal);
        }
        return null;
    }

    private Bitmap createBitmapFromTextField(JSONObject field) {
        Bitmap bitmap = null;
        try {
            String text = field.getString("text");
            int fontSize = field.has("fontSize") ? field.getInt("fontSize") : 25;
            bitmap = createBitmapFromText(text, fontSize, field);
        } catch (JSONException e) {
            Log.d("createBitmapFromTextField error", field.toString());
        }
        return bitmap;
    }

    private Bitmap combineBitmaps(ArrayList<Bitmap> bitmaps, Boolean isHorizontal) {
		int w = 0, h = 0;
		for (int i = 0; i < bitmaps.size(); i++) {
            if(i == 0 || isHorizontal){
			    w += bitmaps.get(i).getWidth();
            } else if(!isHorizontal && bitmaps.get(i).getWidth() > w) {
			    w = bitmaps.get(i).getWidth();
            }
            if(i == 0 || !isHorizontal){
			    h += bitmaps.get(i).getHeight();
            } else if(isHorizontal && bitmaps.get(i).getHeight() > h) {
			    h = bitmaps.get(i).getHeight();
            }
		}
		Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		int pos = 0;
		for (int i = 0; i < bitmaps.size(); i++) {
			Log.d("HTML", "Combine: "+i+"/"+bitmaps.size()+1);
			if(isHorizontal){
                canvas.drawBitmap(bitmaps.get(i), pos, 0f, null);
            } else {
                canvas.drawBitmap(bitmaps.get(i), 0f, pos, null);
            }
            pos += isHorizontal ? bitmaps.get(i).getWidth() : bitmaps.get(i).getHeight();
		}
		return bitmap;
	}

    private Bitmap marginLeft(Bitmap bitmap, int margin) {
        Bitmap padded = Bitmap.createBitmap(bitmap.getWidth() + margin, bitmap.getHeight(), bitmap.getConfig());
        Canvas canvas = new Canvas(padded);
        canvas.drawColor(bitmap.getPixel(1, 1));
        canvas.drawBitmap(bitmap, margin, 0f, null);
        return padded;
    }

    private Bitmap drawLine(float position, int width, int thickness, int margin_top, int margin_bottom) {
        Bitmap bitmap = Bitmap.createBitmap(width, thickness + margin_top + margin_bottom, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setStrokeWidth(thickness);
        p.setColor(Color.BLACK);
        Canvas c = new Canvas(bitmap);
        c.drawLine(position, margin_top, position + width, margin_top, p);
        return bitmap;
    }

    private void getAndCacheImage(String imageUrl, JSONObject command, ICommandBuilder builder, Context context) {
        if (isBitmapCached(imageUrl)) {
            // Image is already cached, use it
            processBitmap(getCachedBitmap(imageUrl), command, builder);
        } else {
            if(imageUrl.startsWith("http://") || imageUrl.startsWith("https://")){
                Request request = new Request.Builder()
                    .url(imageUrl) // Replace imageUrl with the URL of the image
                    .build();

                OkHttpClient client = new OkHttpClient();

                try {
                    Response response = client.newCall(request).execute();
                    
                    if (response.isSuccessful()) {
                        // Process the successful response here
                        // Retrieve the bitmap from the response body
                        Bitmap bitmap = BitmapFactory.decodeStream(response.body().byteStream());

                        // Cache the bitmap
                        cacheBitmap(imageUrl, bitmap);

                        // Process the bitmap further if needed
                        processBitmap(bitmap, command, builder);
                    } else {
                        // Handle other response codes if needed
                        Log.e("StarPRNT", "Failed to fetch image: " + response.code());
                    }
                } catch (IOException e) {
                    // Handle I/O exceptions
                    Log.e("StarPRNT", "Error fetching image: " + e.getMessage());
                }
            } else {
                ContentResolver contentResolver = context.getContentResolver();
                try {
                    Uri imageUri =  Uri.parse(imageUrl);
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(contentResolver, imageUri);
                    if(bitmap != null){
                        cacheBitmap(imageUrl, bitmap);
                        processBitmap(bitmap, command, builder);
                    }
                } catch (IOException e) {
                    Log.e("StarPRNT", "Error parsing bitmap: " + e.getMessage()); // Log the error message
                }
            }
        }
    }

    private boolean isBitmapCached(String imageUrl) {
        return bitmapCache.containsKey(imageUrl);
    }

    private Bitmap getCachedBitmap(String imageUrl) {
        return bitmapCache.get(imageUrl);
    }

    private void cacheBitmap(String imageUrl, Bitmap bitmap) {
        bitmapCache.put(imageUrl, bitmap);
    }

        // Process the bitmap based on command parameters
    private void processBitmap(Bitmap bitmap, JSONObject command, ICommandBuilder builder) {
        try {
            if (command.has("absolutePosition")) {
                int position = command.getInt("absolutePosition");
                builder.appendBitmapWithAbsolutePosition(bitmap, command.optBoolean("diffusion", true), command.optInt("width", 576), command.optBoolean("bothScale", true),
                        getConverterRotation(command.optString("rotation", "Normal")), position);
            } else if (command.has("alignment")) {
                ICommandBuilder.AlignmentPosition alignmentPosition = getAlignment(command.getString("alignment"));
                builder.appendBitmapWithAlignment(bitmap, command.optBoolean("diffusion", true), command.optInt("width", 576), command.optBoolean("bothScale", true),
                        getConverterRotation(command.optString("rotation", "Normal")), alignmentPosition);
            } else {
                builder.appendBitmap(bitmap, command.optBoolean("diffusion", true), command.optInt("width", 576), command.optBoolean("bothScale", true),
                        getConverterRotation(command.optString("rotation", "Normal")));
            }
        } catch (Exception e) {
            Log.e("StarPRNT", "Error processing bitmap: " + e.getMessage()); // Log the error message
            // Handle exceptions or errors in processing
        }
    }

}