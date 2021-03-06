package com.plugin.gcm;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.util.Log;

import com.google.android.gms.gcm.GoogleCloudMessaging;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Random;

/*
 * Implementation of GCMBroadcastReceiver that hard-wires the intent service to be 
 * com.plugin.gcm.GcmntentService, instead of your_package.GcmIntentService 
 */
public class CordovaGCMBroadcastReceiver extends WakefulBroadcastReceiver {
    private static final String TAG = "GcmIntentService";

    private static final String STORAGE_FOLDER = "/pushnotification";

    @Override
    public void onReceive(Context context, Intent intent) {

        Log.d(TAG, "onHandleIntent - context: " + context);

        // Extract the payload from the message
        Bundle extras = intent.getExtras();
        GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(context);
        String messageType = gcm.getMessageType(intent);

        if (extras != null) {
            try {
                if (GoogleCloudMessaging.MESSAGE_TYPE_SEND_ERROR.equals(messageType)) {
                    JSONObject json = new JSONObject();

                    json.put("event", "error");
                    json.put("message", extras.toString());
                    PushPlugin.sendJavascript(json);
                } else if (GoogleCloudMessaging.MESSAGE_TYPE_DELETED.equals(messageType)) {
                    JSONObject json = new JSONObject();
                    json.put("event", "deleted");
                    json.put("message", extras.toString());
                    PushPlugin.sendJavascript(json);
                } else if (GoogleCloudMessaging.MESSAGE_TYPE_MESSAGE.equals(messageType)) {
                    // if we are in the foreground, just surface the payload, else post it to the statusbar
                    if (PushPlugin.isInForeground()) {
                        extras.putBoolean("foreground", true);
                        PushPlugin.sendExtras(extras);
                    } else {
                        extras.putBoolean("foreground", false);

                        // Send a notification if there is a message
//                        if (extras.getString("message") != null && extras.getString("message").length() != 0) {
                        createNotification(context, extras);
//                        }
                    }
                }
            } catch (JSONException exception) {
                Log.d(TAG, "JSON Exception was had!");
            }
        }
    }

    public void createNotification(Context context, Bundle extras) {
        int notId = 0;

        try {
            notId = Integer.parseInt(extras.getString("notId"));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Number format exception - Error parsing Notification ID: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Number format exception - Error parsing Notification ID" + e.getMessage());
        }
        if (notId == 0) {
            // no notId passed, so assume we want to show all notifications, so make it a random number
            notId = new Random().nextInt(100000);
            Log.d(TAG, "Generated random notId: " + notId);
        } else {
            Log.d(TAG, "Received notId: " + notId);
        }


        NotificationManager mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String appName = getAppName(context);

        Intent notificationIntent = new Intent(context, PushHandlerActivity.class);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        notificationIntent.putExtra("pushBundle", extras);

        PendingIntent contentIntent = PendingIntent.getActivity(context, notId, notificationIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        int defaults = Notification.DEFAULT_ALL;

        if (extras.getString("defaults") != null) {
            try {
                defaults = Integer.parseInt(extras.getString("defaults"));
            } catch (NumberFormatException ignore) {
            }
        }

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context)
                .setDefaults(defaults)
                .setSmallIcon(getSmallIcon(context, extras))
                .setWhen(System.currentTimeMillis())
                .setContentIntent(contentIntent)
                .setAutoCancel(true);


        if (!mergeWithConfiguration(context, mBuilder, extras)) {
            return;
        }

        String msgcnt = extras.getString("msgcnt");
        if (msgcnt != null) {
            mBuilder.setNumber(Integer.parseInt(msgcnt));
        }

        final Notification notification = mBuilder.build();
        final int largeIcon = getLargeIcon(context, extras);
        if (largeIcon > -1) {
            notification.contentView.setImageViewResource(android.R.id.icon, largeIcon);
        }

        mNotificationManager.notify(appName, notId, notification);
    }

    private boolean mergeWithConfiguration(Context context, NotificationCompat.Builder mBuilder, Bundle extras) {
        String message = extras.getString("message"), color = extras.getString("color"), title = extras.getString("title"), sound = extras.getString("sound");
        JSONObject config = PushPlugin.getConfiguration(context);
        if (color == null) {
            try {
                color = config.getString("color");
            } catch (JSONException e) {
                Log.d(TAG, "'color' is not provided in configuration!");
            }
        }

        if (title == null) {
            try {
                title = config.getString("title") != null ? config.getString("title") : getAppName(context);
            } catch (JSONException e) {
                Log.d(TAG, "'title' is not provided in configuration!");
                title = getAppName(context);
            }
        }

        if (sound == null) {
            try {
                sound = config.getString("sound");
                if (sound != null) {
                    Uri soundUri = getUriFromAsset(context, sound);
                    mBuilder.setSound(soundUri);
                    mBuilder.setDefaults(~Notification.DEFAULT_SOUND);
                }

            } catch (JSONException e) {
                Log.d(TAG, "'sound' is not provided in configuration!");
            }
        }

        if (message == null) {
            JSONArray payload = null;
            try {
                payload = config.getJSONArray("payload");
            } catch (JSONException e) {
                Log.d(TAG, "'payload' is not provided in configuration!");
            }

            if (payload != null && payload.length() > 0) {
                for (int i = 0; i < payload.length(); i++) {
                    JSONObject param = null;
                    try {
                        param = payload.getJSONObject(i);
                    } catch (JSONException e) {
                        Log.d(TAG, "'param' is not a valid object!");
                    }
                    if (param != null) {
                        String paramName = null;
                        try {
                            paramName = param.getString("name");
                        } catch (JSONException e) {
                            Log.d(TAG, "'paramName' is not a valid object!");
                        }

                        if (paramName != null && !paramName.isEmpty() && extras.getString(paramName) != null) {
                            message = extras.getString(paramName);
                            JSONArray replacements = null;
                            try {
                                replacements = param.getJSONArray("replacements");
                            } catch (JSONException e) {
                                Log.d(TAG, "'replacements' is not provided in configuration!");
                            }

                            if (replacements != null && replacements.length() >= 0) {
                                for (int j = 0; j < replacements.length(); j++) {
                                    JSONObject replacement = null;
                                    JSONArray placeholders = null;
                                    String strToReplace = null, strToReplaceWith = null;
                                    try {
                                        replacement = replacements.getJSONObject(j);
                                        strToReplace = replacement.getString("value");
                                        strToReplaceWith = replacement.getString("replacement");
                                        placeholders = param.getJSONArray("placeholders");
                                    } catch (JSONException e) {
                                        Log.d(TAG, "Invalid replacement!");
                                    }


                                    if (placeholders != null && placeholders.length() > 0 && strToReplace != null && strToReplaceWith != null) {
                                        for (int k = 0; k < placeholders.length(); k++) {
                                            try {
                                                JSONObject placeholder = placeholders.getJSONObject(k);
                                                String placeholderName = placeholder.getString("name");
                                                String placeHolderParam = placeholder.getString("replacementPayloadName");
                                                if (placeholderName != null && placeHolderParam != null && extras.getString(placeHolderParam) != null) {
                                                    strToReplaceWith = strToReplaceWith.replace(placeholderName, extras.getString(placeHolderParam));
                                                }
                                            } catch (JSONException e) {
                                                Log.d(TAG, "Invalid placeholder!");
                                            }
                                        }
                                    }

                                    message = message.replace(strToReplace, strToReplaceWith);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (message == null) {
            return false;
        } else {
            List<String> messages = PushPlugin.saveNotificationQueueEntry(context, message);
            int numberOfMsg = messages.size();
            int maxMsgNumber = 12;

            if (numberOfMsg == 1) {
                mBuilder.setContentText(messages.get(0));
            } else {
                message = "";
                for (int i = numberOfMsg - 1; i >= Math.max(numberOfMsg - maxMsgNumber, 0); i--) {
                    if (i == Math.max(numberOfMsg - maxMsgNumber, 0) && i > 0)
                        message += "...";
                    else
                        message += messages.get(i);

                    if (i != Math.max(numberOfMsg - maxMsgNumber, 0))
                        message += "\n";
                }

                mBuilder.setContentText("You have " + numberOfMsg + " notifications")
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message));
            }
        }

        mBuilder.setColor(getColor(color))
                .setContentTitle(title)
                .setTicker(title);

        return true;
    }


    /**
     * URI for an asset.
     *
     * @param path
     *      Asset path like file://...
     *
     * @return
     *      URI pointing to the given path
     */
    private Uri getUriFromAsset(Context context, String path) {
        File dir = context.getExternalCacheDir();

        if (dir == null) {
            Log.e(TAG, "Missing external cache dir");
            return Uri.EMPTY;
        }

        String resPath  = path.replaceFirst("file:/", "www");
        String fileName = resPath.substring(resPath.lastIndexOf('/') + 1);
        String storage  = dir.toString() + STORAGE_FOLDER;
        File file       = new File(storage, fileName);

        //noinspection ResultOfMethodCallIgnored
        new File(storage).mkdir();

        try {
            AssetManager assets = context.getAssets();
            FileOutputStream outStream = new FileOutputStream(file);
            InputStream inputStream = assets.open(resPath);

            copyFile(inputStream, outStream);

            outStream.flush();
            outStream.close();

            return Uri.fromFile(file);

        } catch (Exception e) {
            Log.e(TAG, "File not found: assets/" + resPath);
            e.printStackTrace();
        }

        return Uri.EMPTY;
    }

    /**
     * Copy content from input stream into output stream.
     *
     * @param in
     *      The input stream
     * @param out
     *      The output stream
     */
    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;

        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static String getAppName(Context context) {
        CharSequence appName = context.getPackageManager().getApplicationLabel(context.getApplicationInfo());
        return (String) appName;
    }

    private int getColor(String color) {
        int theColor = 0; // default, transparent
        if (color != null) {
            try {
                theColor = Color.parseColor(color);
            } catch (IllegalArgumentException ignore) {
            }
        }
        return theColor;
    }

    private int getSmallIcon(Context context, Bundle extras) {

        int icon = -1;

        // first try an iconname possible passed in the server payload
        final String iconNameFromServer = extras.getString("smallIcon");
        if (iconNameFromServer != null) {
            icon = getIconValue(context.getPackageName(), iconNameFromServer);
        }

        // try a custom included icon in our bundle named ic_stat_notify(.png)
        if (icon == -1) {
            icon = getIconValue(context.getPackageName(), "ic_stat_notify");
        }

        // fall back to the regular app icon
        if (icon == -1) {
            icon = context.getApplicationInfo().icon;
        }

        return icon;
    }

    private int getLargeIcon(Context context, Bundle extras) {

        int icon = -1;

        // first try an iconname possible passed in the server payload
        final String iconNameFromServer = extras.getString("largeIcon");
        if (iconNameFromServer != null) {
            icon = getIconValue(context.getPackageName(), iconNameFromServer);
        }

        // try a custom included icon in our bundle named ic_stat_notify(.png)
        if (icon == -1) {
            icon = getIconValue(context.getPackageName(), "ic_notify");
        }

        // fall back to the regular app icon
        if (icon == -1) {
            icon = context.getApplicationInfo().icon;
        }

        return icon;
    }

    private int getIconValue(String className, String iconName) {
        try {
            Class<?> clazz = Class.forName(className + ".R$drawable");
            return (Integer) clazz.getDeclaredField(iconName).get(Integer.class);
        } catch (Exception ignore) {
        }
        return -1;
    }
}
