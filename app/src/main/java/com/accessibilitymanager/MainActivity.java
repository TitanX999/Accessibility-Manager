package com.accessibilitymanager;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.ActionBar;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private SettingsValueChangeContentObserver mContentOb;
    List<AccessibilityServiceInfo> l, tmp;
    ListView listView;
    SharedPreferences sp;
    String settingValue, tmpSettingValue, daemon, top;
    PackageManager pm;
    boolean perm = false;
    private boolean listenerAdded = false;
    private static final String TAG = "MainActivity";

    // Custom content observer
    class SettingsValueChangeContentObserver extends ContentObserver {
        public SettingsValueChangeContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            // Update settingValue and compare with tmpsettingValue in APP. If different, it means the setting change came from outside the APP, so refresh the list on main interface. If same, it means this change was made by this APP, no need to process.
            settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue == null) settingValue = "";
            if (!settingValue.equals(tmpSettingValue))
                runOnUiThread(() -> {
                    int firstPosition = listView.getFirstVisiblePosition();
                    int lastPosition = listView.getLastVisiblePosition();
                    for (int i = firstPosition; i <= lastPosition; i++) {
                        View view = listView.getChildAt(i - firstPosition);
                        String[] packageName = Pattern.compile("/").split(tmp.get(i).getId());
                        boolean isChecked = settingValue.contains(packageName[0] + "/" + packageName[1]) || settingValue.contains(packageName[0] + "/" + packageName[0] + packageName[1]);
                        (view.findViewById(R.id.ib)).setVisibility(isChecked ? View.VISIBLE : View.INVISIBLE);
                        ((Switch) view.findViewById(R.id.s)).setChecked(isChecked);
                    }
                });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle("Accessibility Manager");
        }

        // Make navigation bar transparent for better UI
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }

        // Register Shizuku permission result listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkPermission()) {
            listenerAdded = true;
            Shizuku.addRequestPermissionResultListener(RL);
        }


        pm = getPackageManager();
        // Register setting change listener for real-time update of accessibility service status in APP
        mContentOb = new SettingsValueChangeContentObserver();
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, mContentOb);

        // Get installed accessibility service list on device, including both enabled and disabled ones
        l = ((AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE)).getInstalledAccessibilityServiceList();
        sp = getSharedPreferences("data", 0);

        // Read user setting "Hide from recent apps" and apply it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            ((ActivityManager) getSystemService(Service.ACTIVITY_SERVICE)).getAppTasks().get(0).setExcludeFromRecents(sp.getBoolean("hide", true));

        daemon = sp.getString("daemon", "");
        top = sp.getString("top", "");
        Sort();


        listView = findViewById(R.id.list);


        // Get currently enabled accessibility service list
        settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (settingValue == null) settingValue = "";
        tmpSettingValue = settingValue;


        // First-time use trigger


        if (sp.getBoolean("first", true)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Privacy Policy")
                    .setMessage("This app does not collect or record any of your information and does not contain any internet connectivity functions. Continuing to use means you agree to the above privacy policy.")
                    .setPositiveButton("OK", null).create().show();
            sp.edit().putBoolean("first", false).apply();
        }


        // If device has never opened accessibility settings page before, the setting value doesn't exist, and this APP cannot get accessibility service list. So need to check here, if never enabled before, need this APP to write 1 to this setting to enable it.
        if (Settings.Secure.getString(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED) != null) {
            runOnUiThread(() -> listView.setAdapter(new adapter(tmp)));
            for (int i = 0; i < l.size(); i++) {
                AccessibilityServiceInfo info = l.get(i);
                if (daemon.contains(info.getId())) {
                    StartForeGroundDaemon();
                }
            }

        } else {
            new MaterialAlertDialogBuilder(this).setMessage("Your device has not enabled accessibility services. You can choose to activate system's accessibility service function in System Settings - Accessibility - Open or close any service item, or grant this APP secure settings write permission to solve.")
                    .setNegativeButton("Root activation", (dialogInterface, i) -> {
                        Process p;
                        try {
                            p = Runtime.getRuntime().exec("su");
                            DataOutputStream o = new DataOutputStream(p.getOutputStream());
                            o.writeBytes("pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS\nexit\n");
                            o.flush();
                            o.close();
                            p.waitFor();
                            if (p.exitValue() == 0) {
                                Toast.makeText(MainActivity.this, "Activation successful", Toast.LENGTH_SHORT).show();
                            }
                        } catch (IOException | InterruptedException ignored) {
                            Toast.makeText(MainActivity.this, "Activation failed", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setPositiveButton("Copy command", (dialogInterface, i) -> {
                        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("c", "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS"));
                        Toast.makeText(MainActivity.this, "Command copied to clipboard", Toast.LENGTH_SHORT).show();
                    })
                    .setNeutralButton("Shizuku activation", (dialogInterface, i) -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) check();
                    })
                    .create().show();
            try {
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            } catch (Exception ignored) {
            }
        }
    }

    private void Sort() {
        tmp = new ArrayList<>(l);
        Collections.sort(tmp, (info1, info2) -> {
            String id = info1.getId();
            String id2 = info2.getId();

            // Get index positions of id and id2 in top
            int index1 = top.indexOf(id);
            int index2 = top.indexOf(id2);

            if (index1 != -1 && index2 != -1) {
                // If both in top, sort by appearance order
                return Integer.compare(index1, index2);
            } else if (index1 != -1) {
                // If only id in top, put it first
                return -1;
            } else if (index2 != -1) {
                // If only id2 in top, put it first
                return 1;
            }
            // If neither in top, keep their relative order
            return 0;
        });
    }

    private final Shizuku.OnRequestPermissionResultListener RL = (requestCode, grantResult) -> check();

    // Check Shizuku permission, function to request Shizuku permission
    private void check() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
            return;
        boolean b = true, c = false;
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                Shizuku.requestPermission(0);
            else c = true;
        } catch (Exception e) {
            if (checkSelfPermission("moe.shizuku.manager.permission.API_V23") == PackageManager.PERMISSION_GRANTED)
                c = true;
            if (e.getClass() == IllegalStateException.class) {
                b = false;
                Toast.makeText(this, "Shizuku not running", Toast.LENGTH_SHORT).show();
            }

        }
        if (b && c) {
            try {
                Process p = Shizuku.newProcess(new String[]{"sh"}, null, null);
                OutputStream out = p.getOutputStream();
                out.write(("pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS\nexit\n").getBytes());
                out.flush();
                out.close();
                p.waitFor();
                if (p.exitValue() == 0) {
                    Toast.makeText(this, "Activation successful", Toast.LENGTH_SHORT).show();
                }
            } catch (IOException | InterruptedException ioException) {
                Toast.makeText(this, "Activation failed", Toast.LENGTH_SHORT).show();
            }
        }

    }


    // Some cleanup work, unregister listeners, etc.
    @Override
    protected void onDestroy() {
        if (listenerAdded) Shizuku.removeRequestPermissionResultListener(RL);

        getContentResolver().unregisterContentObserver(mContentOb);
        super.onDestroy();
    }


    @Override // android.app.Activity
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.boot).setChecked(sp.getBoolean("boot", true));
        menu.findItem(R.id.toast).setChecked(sp.getBoolean("toast", true));
        menu.findItem(R.id.hide).setChecked(sp.getBoolean("hide", true));
        return super.onPrepareOptionsMenu(menu);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.arrange, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // This method only passes one MenuItem object, more concise
        int itemId = item.getItemId();

        // Your all if/else if logic remains exactly the same,
        // just changed variable name from menuItem to item
        if (itemId == R.id.boot) {
            sp.edit().putBoolean("boot", !item.isChecked()).apply();
            item.setChecked(!item.isChecked());
        } else if (itemId == R.id.toast) {
            sp.edit().putBoolean("toast", !item.isChecked()).apply();
            item.setChecked(!item.isChecked());
        } else if (itemId == R.id.hide) {
            sp.edit().putBoolean("hide", !item.isChecked()).apply();
            item.setChecked(!item.isChecked());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ((ActivityManager) getSystemService(Service.ACTIVITY_SERVICE)).getAppTasks().get(0).setExcludeFromRecents(sp.getBoolean("hide", true));
            }
        }

        // Finally, call super's method with same name
        return super.onOptionsItemSelected(item);
    }

    // This is for adapting the display of each setting item in the list
    public class adapter extends BaseAdapter {
        private final List<AccessibilityServiceInfo> list;


        public adapter(List<AccessibilityServiceInfo> list) {
            super();
            this.list = list;
        }

        public int getCount() {
            return list.size();
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @SuppressLint({"ViewHolder", "InflateParams"})
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item, null);
                holder = new ViewHolder();
                holder.texta = convertView.findViewById(R.id.a);
                holder.textb = convertView.findViewById(R.id.b);
                holder.imageView = convertView.findViewById(R.id.c);
                holder.sw = convertView.findViewById(R.id.s);
                holder.ib = convertView.findViewById(R.id.ib);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            AccessibilityServiceInfo info = list.get(position);
            String serviceName = info.getId();
            String[] packageName = Pattern.compile("/").split(serviceName);
            Drawable icon = null;
            String Packagelabel = null;
            String ServiceLabel = null;
            String Description = null;
            try {
                icon = pm.getApplicationIcon(packageName[0]);
                Packagelabel = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(packageName[0], PackageManager.GET_META_DATA)));
                ServiceLabel = pm.getServiceInfo(new ComponentName(packageName[0], packageName[0] + packageName[1]), PackageManager.MATCH_DEFAULT_ONLY).loadLabel(pm).toString();
                Description = info.loadDescription(pm);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            if (ServiceLabel == null) ServiceLabel = Packagelabel;
            holder.imageView.setImageDrawable(icon);
            holder.textb.setText(Packagelabel.equals(ServiceLabel) ? ServiceLabel : String.format("%s/%s", Packagelabel, ServiceLabel));
            holder.texta.setText(Description == null || Description.isEmpty() ? "This service has no description" : Description);


            holder.ib.setImageResource(daemon.contains(serviceName) ? R.drawable.lock1 : R.drawable.lock);
//            holder.sw.setEnabled(!daemon.contains(serviceName));
            holder.ib.setOnClickListener(view -> {
                if (checkPermission()) {
                    createPermissionDialog();
                    return;
                }
                daemon = daemon.contains(serviceName) ? daemon.replace(serviceName + ":", "") : serviceName + ":" + daemon;
                sp.edit().putString("daemon", daemon).apply();
                holder.ib.setImageResource(daemon.contains(serviceName) ? R.drawable.lock1 : R.drawable.lock);
//                    holder.sw.setEnabled(!daemon.contains(serviceName));
                StartForeGroundDaemon();
            });
            holder.sw.setChecked(settingValue.contains(packageName[0] + "/" + packageName[1]) || settingValue.contains(packageName[0] + "/" + packageName[0] + packageName[1]));
            holder.ib.setVisibility(holder.sw.isChecked() ? View.VISIBLE : View.INVISIBLE);
            holder.sw.setOnClickListener(view -> {
                if (checkPermission()) {
                    createPermissionDialog();
                    holder.sw.setChecked(!holder.sw.isChecked());
                } else {

                    String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    if (s == null) s = "";

                    if (holder.sw.isChecked()) {
                        tmpSettingValue = serviceName + ":" + s;
                        top = serviceName + ":" + top; // Insert to front
                        sp.edit().putString("top", top).apply();
                        Sort();
                    } else if (daemon.contains(serviceName) && !holder.sw.isChecked()) {
                        holder.sw.setChecked(true);
                        sp.edit().putString("top", top).apply();
                        Sort();
                    } else {
                        // First remove serviceName from top, then append it to end
                        tmpSettingValue = s.replace(serviceName + ":", "")
                                .replace(packageName[0] + "/" + packageName[0] + packageName[1] + ":", "")
                                .replace(serviceName, "")
                                .replace(packageName[0] + "/" + packageName[0] + packageName[1], "");

                        top = top.replace(serviceName + ":", ""); // Remove serviceName
                        top = top + ":" + serviceName; // Insert to end
                        sp.edit().putString("top", top).apply();
                        Sort();
                    }
                    Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, tmpSettingValue);
                    holder.ib.setVisibility(holder.sw.isChecked() ? View.VISIBLE : View.INVISIBLE);
                }
            });


            // Clicking blank area of an item will show detailed information of the service, below code parses various FLAGs, quite troublesome, but no other method.
            convertView.setOnClickListener(view -> {
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(MainActivity.this);
                int fb = info.feedbackType;
                String feedback = "";
                if ((fb & 32) != 0) feedback += "Braille feedback\n";
                if ((fb & 16) != 0) feedback += "Generic feedback\n";
                if ((fb & 8) != 0) feedback += "Visual feedback\n";
                if ((fb & 4) != 0) feedback += "Auditory (not spoken) feedback\n";
                if ((fb & 2) != 0) feedback += "Haptic feedback\n";
                if ((fb & 1) != 0) feedback += "Spoken feedback\n";
                if (feedback.isEmpty()) feedback = "None\n";


                int cap = info.getCapabilities();
                String capa = "";
                if ((cap & 32) != 0) capa += "Perform gestures\n";
                if ((cap & 16) != 0) capa += "Control display magnification\n";
                if ((cap & 8) != 0) capa += "Listen and intercept key events\n";
                if ((cap & 4) != 0)
                    capa += "Request enhanced web accessibility enhancements. For example, install scripts to make web content more accessible\n";
                if ((cap & 2) != 0) capa += "Request touch exploration mode, turning touch screen operations into mouse operations\n";
                if ((cap & 1) != 0) capa += "Read screen content\n";
                if (capa.isEmpty()) capa = "None\n";

                int eve = info.eventTypes;
                String event = "";
                if ((eve & 33554432) != 0) event += "Events for assistant currently reading user screen context\n";
                if ((eve & 16777216) != 0) event += "Events for clicking control context\n";
                if ((eve & 8388608) != 0) event += "Events for window changes\n";
                if ((eve & 4194304) != 0) event += "Events for user ending touch screen\n";
                if ((eve & 2097152) != 0) event += "Events for user starting touch screen\n";
                if ((eve & 1048576) != 0) event += "Events for ending gesture detection\n";
                if ((eve & 524288) != 0) event += "Events for starting gesture detection\n";
                if ((eve & 262144) != 0) event += "Events for traversing view text\n";
                if ((eve & 131072) != 0) event += "Events for clearing accessibility focus\n";
                if ((eve & 65536) != 0) event += "Events for gaining accessibility focus\n";
                if ((eve & 32768) != 0) event += "Events for application posting announcement\n";
                if ((eve & 16384) != 0) event += "Events for changing selected text\n";
                if ((eve & 8192) != 0) event += "Events for scrolling views\n";
                if ((eve & 4096) != 0) event += "Events for window content changes\n";
                if ((eve & 2048) != 0) event += "Events for ending touch exploration gesture\n";
                if ((eve & 1024) != 0) event += "Events for starting touch exploration gesture\n";
                if ((eve & 512) != 0) event += "Events for control ending text input\n";
                if ((eve & 256) != 0) event += "Events for control accepting text input\n";
                if ((eve & 128) != 0) event += "Events for notification status change\n";
                if ((eve & 64) != 0) event += "Events for window status change\n";
                if ((eve & 32) != 0) event += "Events for text box text change\n";
                if ((eve & 16) != 0) event += "Events for control gaining focus\n";
                if ((eve & 8) != 0) event += "Events for control being selected\n";
                if ((eve & 4) != 0) event += "Events for long pressing control\n";
                if ((eve & 2) != 0) event += "Events for clicking control\n";
                if (event.isEmpty()) event = "None\n";


                String range = info.packageNames == null ? "Global effect" : Arrays.toString(info.packageNames).replace("[", "").replace("]", "").replace(", ", "\n").replace(",", "\n");

                int fg = info.flags;
                String flag = "";
                if ((fg & 64) != 0) flag += "Access content of all interactive windows\n";
                if ((fg & 32) != 0) flag += "Listen and intercept key events\n";
                if ((fg & 16) != 0) flag += "Get IDs of all controls on screen view\n";
                if ((fg & 8) != 0) flag += "Enable web accessibility enhancement extensions\n";
                if ((fg & 4) != 0) flag += "Require system to enter touch exploration mode\n";
                if ((fg & 2) != 0) flag += "Query unimportant content in windows\n";
                if ((fg & 1) != 0) flag += "Default\n";
                if (flag.isEmpty()) flag = "None\n";


                try {
                    final ScrollView scrollView = new ScrollView(MainActivity.this);
                    final TextView textView = new TextView(MainActivity.this);
                    textView.setTextIsSelectable(true);
                    textView.setPadding(40, 20, 40, 20);
                    textView.setTextSize(18f);
                    textView.setAlpha(0.8f);
                    textView.setTextColor(Color.BLACK);
                    textView.setText(String.format("Service class name:\n%s\n\nSpecial capabilities:\n%s\nEffective range:\n%s\n\nFeedback methods:\n%s\nCaptured event types:\n%s\nSpecial flags:\n%s", serviceName, capa, range, feedback, event, flag));
                    scrollView.addView(textView);
                    if (info.getSettingsActivityName() != null && !info.getSettingsActivityName().isEmpty())
                        builder.setNegativeButton("Settings", (dialogInterface, i) -> {
                            try {
                                startActivity(new Intent().setComponent(new ComponentName(packageName[0], info.getSettingsActivityName())));
                            } catch (Exception ignored) {
                            }
                        });

                    builder
                            .setIcon(pm.getApplicationIcon(packageName[0]))
                            .setView(scrollView).setTitle("Service Detailed Information")
                            .setPositiveButton("Got it", null)
                            .create().show();
                } catch (Exception ignored) {
                }
            });
            return convertView;
        }

        private void createPermissionDialog() {
            String cmd = "pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
            new MaterialAlertDialogBuilder(MainActivity.this)
                    .setMessage("For Android 5.1 and lower devices, need to convert this APP to system app.\n\nFor Android 6.0 and higher devices, choose any one of three methods below:\n1. Connect computer USB debugging then execute following command in computer CMD:\nadb shell " + cmd + "\n\n2.Root activation.\n\n3.Shizuku activation.")
                    .setTitle("Secure settings write permission required")
                    .setPositiveButton("Copy command", (dialogInterface, i) -> {
                        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("c", "adb shell " + cmd));
                        Toast.makeText(MainActivity.this, "Command copied to clipboard", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Root activation", (dialoginterface, i) -> {
                        Process p;
                        try {
                            p = Runtime.getRuntime().exec("su");
                            DataOutputStream o = new DataOutputStream(p.getOutputStream());
                            o.writeBytes(cmd + "\nexit\n");
                            o.flush();
                            o.close();
                            p.waitFor();
                            if (p.exitValue() == 0) {
                                Toast.makeText(MainActivity.this, "Activation successful", Toast.LENGTH_SHORT).show();
                            }
                        } catch (IOException | InterruptedException ignored) {
                            Toast.makeText(MainActivity.this, "Activation failed", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNeutralButton("Shizuku activation", (dialogInterface, i) -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) check();
                    })
                    .create().show();
        }

        class ViewHolder {

            TextView texta;
            TextView textb;
            ImageView imageView;
            SwitchMaterial sw;
            ImageButton ib;
        }


    }

    // Check if APP can write secure settings
    boolean checkPermission() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            perm = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        else {
            try {
                // 1. Get PackageInfo
                PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_CONFIGURATIONS);

                // 2.【Key】Only use it after successfully getting packageInfo
                //    Also check if applicationInfo is null, to be absolutely safe
                if (packageInfo != null && packageInfo.applicationInfo != null) {
                    perm = (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                } else {
                    // If failed to get or applicationInfo is null, consider no permission
                    perm = false;
                }
            } catch (PackageManager.NameNotFoundException e) {
                perm = false;
                Log.e(TAG, "Could not find PackageInfo for our own package.", e);
            }
        }
        return !perm;
    }

    // Start foreground service for keep-alive!
    void StartForeGroundDaemon() {

        if (checkPermission()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).areNotificationsEnabled()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
            Toast.makeText(this, "Please grant notification permission", Toast.LENGTH_SHORT).show();
            return;
        }

        // Request to disable battery optimization
        if (Build.VERSION.SDK_INT >= 23 && !((PowerManager) getSystemService(Service.POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()))
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(new Intent(this, daemonService.class));
        else
            startService(new Intent(this, daemonService.class));

    }


}
