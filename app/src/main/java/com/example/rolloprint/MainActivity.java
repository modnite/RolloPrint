package com.example.rolloprint;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private UsbPrintManager printManager;
    private JobQueueManager jobQueueManager;
    private AppUpdateManager appUpdateManager;
    private TextView tvLog;
    private ScrollView scrollViewLog;
    private Bitmap lastRenderedBitmap;

    private MaterialSwitch switchServer;
    private TextView tvServerStatus;
    private TextView tvQueueStatus;
    private TextView tvHeaderVersion;
    private PrintServerService printServerService;
    private SharedPreferences prefs;
    private boolean isServiceBound = false;
    private boolean isUpdatingSwitchProgrammatically = false;

    private final ActivityResultLauncher<Intent> pdfPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        renderAndPreview(uri);
                    }
                }
            }
    );

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PrintServerService.LocalBinder binder = (PrintServerService.LocalBinder) service;
            printServerService = binder.getService();
            isServiceBound = true;

            if (switchServer.isChecked()) {
                startIppServer();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            printServerService = null;
            isServiceBound = false;
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbPrintManager.ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        log("Permission GRANTED for Rollo. Re-triggering print...");
                        if (lastRenderedBitmap != null) {
                            jobQueueManager.addJob(lastRenderedBitmap, "Permission Retry Job");
                        }
                    } else {
                        log("Permission DENIED for Rollo.");
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("rollo_prefs", MODE_PRIVATE);

        // Enable edge-to-edge drawing so layout responds to system bars & display cutouts (camera punch hole)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_main);

        View mainView = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            float density = getResources().getDisplayMetrics().density;
            int basePaddingPx = (int) (16 * density);

            v.setPadding(
                    insets.left + basePaddingPx,
                    insets.top + basePaddingPx,
                    insets.right + basePaddingPx,
                    insets.bottom + basePaddingPx
            );
            return WindowInsetsCompat.CONSUMED;
        });

        tvLog = findViewById(R.id.tvLog);
        scrollViewLog = findViewById(R.id.scrollViewLog);
        Button btnSelect = findViewById(R.id.btnSelect);
        switchServer = findViewById(R.id.switchServer);
        tvServerStatus = findViewById(R.id.tvServerStatus);
        tvQueueStatus = findViewById(R.id.tvQueueStatus);
        Button btnClearQueue = findViewById(R.id.btnClearQueue);
        Button btnViewQueue = findViewById(R.id.btnViewQueue);
        Button btnDumpLogs = findViewById(R.id.btnDumpLogs);
        tvHeaderVersion = findViewById(R.id.tvHeaderVersion);

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        if (btnDumpLogs != null) {
            btnDumpLogs.setOnClickListener(v -> dumpActivityLogsToEtherpad());
        }

        printManager = new UsbPrintManager(this, text -> {
            log(text);
            return null;
        });

        TextView tvHardwareStatus = findViewById(R.id.tvHardwareStatus);

        printManager.setOnHardwareStateChanged(states -> {
            runOnUiThread(() -> {
                if (states.contains(HardwareState.HEAD_OPEN)) {
                    tvHardwareStatus.setText("● Hardware: Cover Open");
                    tvHardwareStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
                } else if (states.contains(HardwareState.OUT_OF_PAPER)) {
                    tvHardwareStatus.setText("● Hardware: Out of Paper (Red LED)");
                    tvHardwareStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                } else if (states.contains(HardwareState.READY)) {
                    tvHardwareStatus.setText("● Hardware: Ready (Green LED)");
                    tvHardwareStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
                } else if (states.contains(HardwareState.PRINTING)) {
                    tvHardwareStatus.setText("● Hardware: Printing...");
                    tvHardwareStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark));
                } else {
                    tvHardwareStatus.setText("● Hardware: Disconnected / Unknown");
                    tvHardwareStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
                }
            });
            return null;
        });

        jobQueueManager = new JobQueueManager(
                printManager,
                text -> {
                    log(text);
                    return null;
                },
                count -> {
                    runOnUiThread(() -> {
                        if (count == 0) {
                            tvQueueStatus.setText(R.string.queue_empty);
                        } else {
                            tvQueueStatus.setText("Queue: " + count + " job(s) waiting");
                        }
                    });
                    return null;
                }
        );

        btnClearQueue.setOnClickListener(v -> {
            jobQueueManager.clearQueue();
            printManager.clearHardwareQueue();
        });

        if (btnViewQueue != null) {
            btnViewQueue.setOnClickListener(v -> {
                QueueManagerDialogFragment queueDialog = QueueManagerDialogFragment.Companion.newInstance(
                        jobQueueManager,
                        job -> {
                            showPrintPreview(job.getBitmap());
                            return null;
                        }
                );
                queueDialog.show(getSupportFragmentManager(), "QueueManager");
            });
        }

        View layoutLogHeaderClickable = findViewById(R.id.layoutLogHeaderClickable);
        ImageView ivLogExpandArrow = findViewById(R.id.ivLogExpandArrow);

        layoutLogHeaderClickable.setOnClickListener(v -> {
            if (scrollViewLog.getVisibility() == View.VISIBLE) {
                scrollViewLog.setVisibility(View.GONE);
                ivLogExpandArrow.animate().rotation(0f).setDuration(200).start();
            } else {
                scrollViewLog.setVisibility(View.VISIBLE);
                ivLogExpandArrow.animate().rotation(180f).setDuration(200).start();
            }
        });

        btnSelect.setOnClickListener(v -> {
            log("[LOCAL] --- Direct TSPL Label Print ---");
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/pdf");
            pdfPickerLauncher.launch(intent);
        });

        switchServer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingSwitchProgrammatically) return;

            if (isChecked) {
                if (isServiceBound && printServerService != null) {
                    startIppServer();
                } else {
                    Intent intent = new Intent(this, PrintServerService.class);
                    intent.setAction(PrintServerService.ACTION_START);
                    try {
                        ContextCompat.startForegroundService(this, intent);
                        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
                    } catch (Exception e) {
                        log("ERROR starting IPP print server: " + e.getMessage());
                        isUpdatingSwitchProgrammatically = true;
                        switchServer.setChecked(false);
                        isUpdatingSwitchProgrammatically = false;
                    }
                }
            } else {
                if (isServiceBound && printServerService != null) {
                    printServerService.stopServer();
                }
                tvServerStatus.setText("Status: Disabled (Port 8631)");
            }
        });

        IntentFilter filter = new IntentFilter(UsbPrintManager.ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        
        String appVersion = "1.7.3";
        try {
            appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {}

        tvHeaderVersion.setText("v" + appVersion);

        log("RolloPrint v" + appVersion + " Loaded.");
        log("Ready to print 4x6 PDF labels.");

        // Immediately poll hardware status on launch so status badge updates in 0.1s
        printManager.runPrinterDiagnosticsAsync();

        appUpdateManager = new AppUpdateManager(
                this,
                appVersion,
                text -> {
                    log(text);
                    return null;
                },
                (latestTag, releaseNotes, apkUrl) -> {
                    runOnUiThread(() -> showUpdateAvailableDialog(latestTag, releaseNotes, apkUrl));
                    return null;
                }
        );
        appUpdateManager.startPeriodicCheck();

        // Proactively request all runtime permissions on first open
        checkAndRequestAllPermissions();

        // Check if app was launched via Share / Open PDF intent
        handleIncomingIntent(getIntent());
    }

    private void showSettingsDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        MaterialSwitch switchLocal = dialogView.findViewById(R.id.switchLocalPreviewDialog);
        MaterialSwitch switchNetwork = dialogView.findViewById(R.id.switchNetworkPreviewDialog);
        TextInputEditText etEtherpadUrl = dialogView.findViewById(R.id.etEtherpadUrl);
        TextInputEditText etEtherpadApiKey = dialogView.findViewById(R.id.etEtherpadApiKey);
        Button btnDiagnostics = dialogView.findViewById(R.id.btnDiagnostics);
        Button btnCheckUpdates = dialogView.findViewById(R.id.btnCheckUpdates);

        boolean showLocal = prefs.getBoolean("PREF_LOCAL_PREVIEW", true);
        boolean showNetwork = prefs.getBoolean("PREF_NETWORK_PREVIEW", false);
        String savedEtherpadUrl = prefs.getString("PREF_ETHERPAD_URL", "http://192.168.100.208:9001/p/notepad");
        String savedEtherpadApiKey = prefs.getString("PREF_ETHERPAD_API_KEY", "");

        switchLocal.setChecked(showLocal);
        switchNetwork.setChecked(showNetwork);
        if (etEtherpadUrl != null) {
            etEtherpadUrl.setText(savedEtherpadUrl);
        }
        if (etEtherpadApiKey != null) {
            etEtherpadApiKey.setText(savedEtherpadApiKey);
        }

        switchLocal.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("PREF_LOCAL_PREVIEW", isChecked).apply();
            log("[SETTINGS] Preview Local Prints set to: " + isChecked);
        });

        switchNetwork.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("PREF_NETWORK_PREVIEW", isChecked).apply();
            log("[SETTINGS] Preview Network Prints set to: " + isChecked);
        });

        if (btnDiagnostics != null) {
            btnDiagnostics.setOnClickListener(v -> {
                log("[DIAGNOSTIC] Running hardware status check...");
                printManager.runPrinterDiagnosticsAsync();
            });
        }

        if (btnCheckUpdates != null) {
            btnCheckUpdates.setOnClickListener(v -> {
                if (appUpdateManager != null) {
                    appUpdateManager.checkForUpdates(false);
                }
            });
        }

        new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setPositiveButton(R.string.done, (dialog, which) -> {
                    if (etEtherpadUrl != null && etEtherpadUrl.getText() != null) {
                        String newUrl = etEtherpadUrl.getText().toString().trim();
                        if (!newUrl.isEmpty()) {
                            prefs.edit().putString("PREF_ETHERPAD_URL", newUrl).apply();
                            log("[SETTINGS] Etherpad Pastebin URL set to: " + newUrl);
                        }
                    }
                    if (etEtherpadApiKey != null && etEtherpadApiKey.getText() != null) {
                        String newApiKey = etEtherpadApiKey.getText().toString().trim();
                        prefs.edit().putString("PREF_ETHERPAD_API_KEY", newApiKey).apply();
                    }
                })
                .show();
    }

    private void showUpdateAvailableDialog(String latestTag, String releaseNotes, String apkUrl) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("RolloPrint Update Available (v" + latestTag + ")")
                .setMessage(releaseNotes)
                .setPositiveButton("Update Now", (dialog, which) -> {
                    log("[UPDATE] Downloading RolloPrint v" + latestTag + "...");
                    if (appUpdateManager != null) {
                        appUpdateManager.downloadAndInstallApk(apkUrl, msg -> {
                            log(msg);
                            return null;
                        });
                    }
                })
                .setNegativeButton("Ignore", null)
                .show();
    }

    private void dumpActivityLogsToEtherpad() {
        String etherpadUrl = prefs.getString("PREF_ETHERPAD_URL", "http://192.168.100.208:9001/p/notepad");
        String apiKey = prefs.getString("PREF_ETHERPAD_API_KEY", "").trim();
        String logContent = tvLog.getText().toString();

        if (logContent.trim().isEmpty()) {
            log("[ETHERPAD] Activity log is empty. Nothing to dump.");
            return;
        }

        log("[ETHERPAD] Dumping activity log to " + etherpadUrl + "...");

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String cleanUrl = etherpadUrl.trim();
                if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                    cleanUrl = "http://" + cleanUrl;
                }

                URI uri = new URI(cleanUrl);
                String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
                int port = uri.getPort() != -1 ? uri.getPort() : 9001;
                String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
                String path = uri.getPath() != null ? uri.getPath() : "/p/notepad";
                String padId = path.startsWith("/p/") ? path.substring(3) : "notepad";

                if (!apiKey.isEmpty()) {
                    String apiUrl = scheme + "://" + host + ":" + port + "/api/1.2.14/setText";
                    URL url = new URL(apiUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                    String postData = "apikey=" + URLEncoder.encode(apiKey, "UTF-8") +
                            "&padID=" + URLEncoder.encode(padId, "UTF-8") +
                            "&text=" + URLEncoder.encode(logContent, "UTF-8");

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postData.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        log("[ETHERPAD] SUCCESS: Activity log dumped to Etherpad via API key (" + padId + ").");
                        return;
                    }
                }

                // Multipart Form-Data Import to /p/notepad/import (Works on all Etherpad instances without API key)
                String importUrl = scheme + "://" + host + ":" + port + "/p/" + padId + "/import";
                postMultipartFileToEtherpad(importUrl, padId, logContent);

            } catch (Exception e) {
                log("[ETHERPAD] Dump notice: " + e.getMessage());
            }
        });
    }

    private void postMultipartFileToEtherpad(String importUrl, String padId, String logContent) {
        try {
            String boundary = "---EtherpadBoundary" + System.currentTimeMillis();
            URL url = new URL(importUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream os = conn.getOutputStream()) {
                String header = "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"" + padId + ".txt\"\r\n" +
                        "Content-Type: text/plain\r\n\r\n";
                os.write(header.getBytes(StandardCharsets.UTF_8));
                os.write(logContent.getBytes(StandardCharsets.UTF_8));
                String footer = "\r\n--" + boundary + "--\r\n";
                os.write(footer.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200 || responseCode == 302 || responseCode == 303) {
                log("[ETHERPAD] SUCCESS: Activity log dumped to Etherpad pad '/p/" + padId + "'.");
            } else {
                log("[ETHERPAD] WARNING: Etherpad import returned HTTP " + responseCode);
            }
        } catch (Exception e) {
            log("[ETHERPAD] Direct import notice: " + e.getMessage());
        }
    }

    private void startIppServer() {
        if (printServerService != null) {
            printServerService.initializeServer(
                    printManager,
                    jobQueueManager,
                    text -> {
                        log(text);
                        return null;
                    },
                    (running, ip) -> {
                        runOnUiThread(() -> {
                            isUpdatingSwitchProgrammatically = true;
                            if (running && ip != null) {
                                tvServerStatus.setText("Status: Active on " + ip + ":8631 (Driverless IPP)");
                                if (!switchServer.isChecked()) switchServer.setChecked(true);
                            } else {
                                tvServerStatus.setText("Status: Disabled (Port 8631)");
                                if (switchServer.isChecked()) switchServer.setChecked(false);
                            }
                            isUpdatingSwitchProgrammatically = false;
                        });
                        return null;
                    },
                    bitmap -> {
                        runOnUiThread(() -> showPrintPreview(bitmap));
                        return null;
                    }
            );
        }
    }

    private void checkAndRequestAllPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            log("Prompting for initial app permissions...");
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), 200);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();

        Uri pdfUri = null;
        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pdfUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                pdfUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            pdfUri = intent.getData();
        }

        if (pdfUri != null) {
            log("[LOCAL] Received shared label via Share Sheet: " + pdfUri);
            renderAndPreview(pdfUri);
        }
    }

    private void log(String text) {
        runOnUiThread(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            tvLog.append("[" + timestamp + "] " + text + "\n");
            scrollViewLog.post(() -> scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void renderAndPreview(Uri uri) {
        Bitmap bitmap = printManager.renderPdfToBitmap(uri);
        if (bitmap != null) {
            lastRenderedBitmap = bitmap;
            boolean showPreview = prefs.getBoolean("PREF_LOCAL_PREVIEW", true);
            if (showPreview) {
                showPrintPreview(bitmap);
            } else {
                log("[LOCAL] Local preview disabled in settings. Adding to print queue...");
                jobQueueManager.addJob(bitmap, "Local Label");
            }
        }
    }

    private void showPrintPreview(Bitmap bitmap) {
        PrintPreviewDialogFragment previewDialog = PrintPreviewDialogFragment.Companion.newInstance(
                bitmap,
                () -> {
                    log("[LOCAL] User confirmed print. Adding to print queue...");
                    jobQueueManager.addJob(bitmap, "Local Label");
                }
        );
        previewDialog.show(getSupportFragmentManager(), "PrintPreview");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception e) { }
    }
}
