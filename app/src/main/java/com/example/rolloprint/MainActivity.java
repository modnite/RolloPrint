package com.example.rolloprint;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private UsbPrintManager printManager;
    private NetworkPrintServer networkPrintServer;
    private TextView tvLog;
    private ScrollView scrollView;
    private Bitmap lastRenderedBitmap;

    private MaterialSwitch switchServer;
    private TextView tvServerStatus;
    private boolean isUpdatingSwitchProgrammatically = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbPrintManager.ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        log("Permission GRANTED for Rollo. Re-triggering print...");
                        if (lastRenderedBitmap != null) {
                            printManager.printBitmapAsync(lastRenderedBitmap);
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
        scrollView = findViewById(R.id.scrollView);
        Button btnSelect = findViewById(R.id.btnSelect);
        switchServer = findViewById(R.id.switchServer);
        tvServerStatus = findViewById(R.id.tvServerStatus);

        printManager = new UsbPrintManager(this, text -> {
            log(text);
            return null;
        });

        networkPrintServer = new NetworkPrintServer(
                this,
                printManager,
                text -> {
                    log(text);
                    return null;
                },
                (running, ip) -> {
                    runOnUiThread(() -> {
                        isUpdatingSwitchProgrammatically = true;
                        if (running && ip != null) {
                            tvServerStatus.setText("Status: Active on " + ip + ":9100 (mDNS Enabled)");
                            if (!switchServer.isChecked()) switchServer.setChecked(true);
                        } else {
                            tvServerStatus.setText("Status: Disabled (Port 9100)");
                            if (switchServer.isChecked()) switchServer.setChecked(false);
                        }
                        isUpdatingSwitchProgrammatically = false;
                    });
                    return null;
                }
        );

        btnSelect.setOnClickListener(v -> {
            log("--- Direct TSPL Label Print ---");
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/pdf");
            startActivityForResult(intent, 101);
        });

        switchServer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingSwitchProgrammatically) return;

            if (isChecked) {
                requestNotificationPermissionIfNeeded();
                log("Starting Office Network Print Server on port 9100...");
                networkPrintServer.start();
            } else {
                log("Stopping Office Network Print Server...");
                networkPrintServer.stop();
                tvServerStatus.setText("Status: Disabled (Port 9100)");
            }
        });

        IntentFilter filter = new IntentFilter(UsbPrintManager.ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        
        log("Rollo X1038 Utility Loaded.");
        log("Ready to print PDF labels.");

        // Check if app was launched via Share / Open PDF intent
        handleIncomingIntent(getIntent());
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102);
            }
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
            log("Received shared label: " + pdfUri);
            renderAndPreview(pdfUri);
        }
    }

    private void log(String text) {
        runOnUiThread(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            tvLog.append("[" + timestamp + "] " + text + "\n");
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                renderAndPreview(uri);
            }
        }
    }

    private void renderAndPreview(Uri uri) {
        Bitmap bitmap = printManager.renderPdfToBitmap(uri);
        if (bitmap != null) {
            lastRenderedBitmap = bitmap;
            showPrintPreview(bitmap);
        }
    }

    private void showPrintPreview(Bitmap bitmap) {
        PrintPreviewDialogFragment previewDialog = PrintPreviewDialogFragment.Companion.newInstance(
                bitmap,
                () -> {
                    log("User confirmed print. Starting hardware stream...");
                    printManager.printBitmapAsync(bitmap);
                }
        );
        previewDialog.show(getSupportFragmentManager(), "PrintPreview");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (networkPrintServer != null) {
            networkPrintServer.stop();
        }
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception e) { }
    }
}
