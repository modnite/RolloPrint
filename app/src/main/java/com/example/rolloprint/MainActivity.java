package com.example.rolloprint;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private UsbPrintManager printManager;
    private TextView tvLog;
    private ScrollView scrollView;
    private Bitmap lastRenderedBitmap;

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
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tvLog);
        scrollView = findViewById(R.id.scrollView);
        Button btnSelect = findViewById(R.id.btnSelect);

        printManager = new UsbPrintManager(this, text -> {
            log(text);
            return null;
        });

        btnSelect.setOnClickListener(v -> {
            log("--- Direct TSPL Label Print ---");
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/pdf");
            startActivityForResult(intent, 101);
        });

        IntentFilter filter = new IntentFilter(UsbPrintManager.ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        
        log("Rollo X1038 Utility Loaded.");
        log("Ready to print PDF labels.");
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
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception e) { }
    }
}
