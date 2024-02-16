package com.rhaker.reactnativesmsandroid;


import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.database.Cursor;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.lang.SecurityException;
import java.lang.String;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class RNSmsAndroidModule extends ReactContextBaseJavaModule {

    private static final String TAG = RNSmsAndroidModule.class.getSimpleName();

    private ReactApplicationContext reactContext;
    private static final int REQUEST_CODE = 5235;

    // set the activity - pulled in from Main
    public RNSmsAndroidModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    public String getName() {
      return "SmsAndroid";
    }

    @ReactMethod
    public void getSubscriptions(Promise promise) {
        ReactApplicationContext context = getReactApplicationContext();
    
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            promise.reject("PERMISSION_NOT_GRANTED", "READ_PHONE_STATE permission not granted");
            return;
        }
    
        SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
        List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();
    
        if (subscriptionInfoList != null && !subscriptionInfoList.isEmpty()) {
            WritableArray subscriptionDataArray = Arguments.createArray();
    
            for (SubscriptionInfo subscriptionInfo : subscriptionInfoList) {
                WritableMap subscriptionData = Arguments.createMap();
                int subscriptionId = subscriptionInfo.getSubscriptionId();
                CharSequence carrierName = subscriptionInfo.getCarrierName(); // Retrieving the carrier name
                
                subscriptionData.putInt("subscriptionId", subscriptionId);
                if (carrierName != null) {
                    subscriptionData.putString("carrierName", carrierName.toString());
                } else {
                    subscriptionData.putString("carrierName", "Unknown"); // Handle potential null carrier name
                }
    
                subscriptionDataArray.pushMap(subscriptionData);
            }
    
            promise.resolve(subscriptionDataArray);
        } else {
            promise.reject("NO_SUBSCRIPTIONS_FOUND", "No subscription information found");
        }
    }
    
    @ReactMethod
    public void send(ReadableMap options, final Callback callback) {

        String body = options.hasKey("body") ? options.getString("body") : null;
        ReadableArray recipients = options.hasKey("recipients") ? options.getArray("recipients") : null;
        Integer subscriptionId = options.hasKey("subscriptionId") ? options.getInt("subscriptionId") : null;
        
        // Attachment handling
        ReadableMap attachment = null;
        if (options.hasKey("attachment")) {
            attachment = options.getMap("attachment");
        }

        if ((attachment == null) && (recipients != null) && (recipients.size() == 1) && (body != null) && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)) {
            try {
                SmsManager smsManager;
                if (subscriptionId != null) { // Check for null
                    smsManager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
                } else {
                    smsManager = SmsManager.getDefault();
                }

                String recipient = recipients.getString(0); // Fix method call
                ArrayList<String> parts = smsManager.divideMessage(body);
                if (parts.size() > 1) {
                    // Assuming 'null' for sentIntent and deliveryIntent, you might need to specify these.
                    smsManager.sendMultipartTextMessage(recipient, null, parts, null, null);
                } else {
                    smsManager.sendTextMessage(recipient, null, body, null, null);
                }

                callback.invoke(null, "success");
            } catch (Exception e) {
                callback.invoke(e.getMessage(), "error");
                e.printStackTrace();
            }
        } else {
            // launch default sms package, user hits send
            Intent sendIntent;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                String defaultSmsPackageName = Telephony.Sms.getDefaultSmsPackage(reactContext);
                sendIntent = new Intent(Intent.ACTION_SEND);
                if (defaultSmsPackageName != null){
                    sendIntent.setPackage(defaultSmsPackageName);
                }
                sendIntent.setType("text/plain");
            } else {
                sendIntent = new Intent(Intent.ACTION_VIEW);
                sendIntent.setType("vnd.android-dir/mms-sms");
            }

            sendIntent.putExtra("sms_body", body);
            sendIntent.putExtra(sendIntent.EXTRA_TEXT, body);
            sendIntent.putExtra("exit_on_sent", true);

            if (attachment != null) {
                Uri attachmentUrl = Uri.parse(attachment.getString("url"));
                sendIntent.putExtra(Intent.EXTRA_STREAM, attachmentUrl);

                String type = attachment.getString("androidType");
                sendIntent.setType(type);
            }

            if (recipients != null && recipients.size() > 0) {
                String separator = android.os.Build.MANUFACTURER.equalsIgnoreCase("Samsung") ? "," : ";";
                StringBuilder recipientString = new StringBuilder();
                for (int i = 0; i < recipients.size(); i++) {
                    recipientString.append(recipients.getString(i));
                    if (i < recipients.size() - 1) {
                        recipientString.append(separator);
                    }
                }
                sendIntent.putExtra("address", recipientString.toString());
            }
            
            try {
                this.reactContext.startActivity(sendIntent);
                callback.invoke(null,"success");
            } catch (Exception e) {
                callback.invoke(null,"error");
                e.printStackTrace();
            }
        }
    }


    @ReactMethod
    public void list(String filter, final Callback errorCallback, final Callback successCallback) {
        try{
            JSONObject filterJ = new JSONObject(filter);
            String uri_filter = filterJ.has("box") ? filterJ.optString("box") : "inbox";
            int fread = filterJ.has("read") ? filterJ.optInt("read") : -1;
            int fid = filterJ.has("_id") ? filterJ.optInt("_id") : -1;
            String faddress = filterJ.optString("address");
            String fcontent = filterJ.optString("body");
            int indexFrom = filterJ.has("indexFrom") ? filterJ.optInt("indexFrom") : 0;
            int maxCount = filterJ.has("maxCount") ? filterJ.optInt("maxCount") : -1;
            Cursor cursor = getCurrentActivity().getContentResolver().query(Uri.parse("content://sms/"+uri_filter), null, "", null, null);
            int c = 0;
            JSONArray jsons = new JSONArray();
            while (cursor.moveToNext()) {
                boolean matchFilter = false;
                if (fid > -1)
                matchFilter = fid == cursor.getInt(cursor.getColumnIndex("_id"));
                else if (fread > -1)
                matchFilter = fread == cursor.getInt(cursor.getColumnIndex("read"));
                else if (faddress.length() > 0)
                matchFilter = faddress.equals(cursor.getString(cursor.getColumnIndex("address")).trim());
                else if (fcontent.length() > 0)
                matchFilter = fcontent.equals(cursor.getString(cursor.getColumnIndex("body")).trim());
                else {
                    matchFilter = true;
                }
                if (matchFilter)
                {
                    if (c >= indexFrom) {
                        if (maxCount>0 && c >= indexFrom + maxCount) break;
                        c++;
                        // Long dateTime = Long.parseLong(cursor.getString(cursor.getColumnIndex("date")));
                        // String message = cursor.getString(cursor.getColumnIndex("body"));
                        JSONObject json;
                        json = getJsonFromCursor(cursor);
                        jsons.put(json);

                    }
                }

            }
            cursor.close();
            try {
                successCallback.invoke(c, jsons.toString());
            } catch (Exception e) {
                errorCallback.invoke(e.getMessage());
            }
        } catch (JSONException e)
        {
            errorCallback.invoke(e.getMessage());
            return;
        }
    }

    private JSONObject getJsonFromCursor(Cursor cur) {
        JSONObject json = new JSONObject();

        int nCol = cur.getColumnCount();
        String[] keys = cur.getColumnNames();
        try
        {
            for (int j = 0; j < nCol; j++)
            switch (cur.getType(j)) {
                case 0:
                json.put(keys[j], null);
                break;
                case 1:
                json.put(keys[j], cur.getLong(j));
                break;
                case 2:
                json.put(keys[j], cur.getFloat(j));
                break;
                case 3:
                json.put(keys[j], cur.getString(j));
                break;
                case 4:
                json.put(keys[j], cur.getBlob(j));
            }
        }
        catch (Exception e)
        {
            return null;
        }

        return json;
    }
}
