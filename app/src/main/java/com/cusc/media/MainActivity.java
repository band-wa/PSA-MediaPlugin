package com.cusc.media;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cusc.media.base.player.MediaSessionListenerService;
import com.cusc.media.base.player.MusicService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private TextView permissionStatusText;
    private TextView connectedAppText;
    private boolean jumpedToPlayingApp;

    private final MusicService.MusicServiceCallback musicServiceCallback = new MusicService.MusicServiceCallback() {
        @Override
        public void onPackageChanged(String packageName) {
            runOnUiThread(() -> updateConnectedApp(packageName));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String playingPackage = resolvePlayingPackage();
        if (playingPackage != null) {
            launchPlayingApp(playingPackage);
            jumpedToPlayingApp = true;
            return;
        }

        setContentView(R.layout.activity_main);

        Button actionBtn = findViewById(R.id.btn_restore);
        if (actionBtn != null) {
            actionBtn.setOnClickListener(v -> {
                Uri packageUri = Uri.parse("package:" + getPackageName());
                Intent uninstallIntent = new Intent(Intent.ACTION_DELETE, packageUri);
                startActivity(uninstallIntent);
            });
        }

        LinearLayout permissionStatusLayout = findViewById(R.id.layout_permission_status);
        permissionStatusText = findViewById(R.id.text_permission_status);
        connectedAppText = findViewById(R.id.text_connected_app);

        permissionStatusLayout.setOnClickListener(v -> {
            if (!isNotificationServiceEnabled()) {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }
        });

        ImageView qrImage = findViewById(R.id.image_qr_code);
        if (qrImage != null) {
            String repoUrl = getString(R.string.qr_repo_url);
            Bitmap qrBitmap = generateQrCodeBitmap(repoUrl, qrImage.getWidth(), qrImage.getHeight());
            if (qrBitmap != null) {
                qrImage.setImageBitmap(qrBitmap);
            } else {
                Log.w(TAG, "Failed to generate QR code bitmap");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (jumpedToPlayingApp) {
            return;
        }
        updatePermissionStatus();

        MusicService musicService = MusicService.getInstance();
        if (musicService != null) {
            musicService.setMusicServiceCallback(musicServiceCallback);
            String playingPackage = musicService.getPlayingPackageName();
            if (playingPackage != null) {
                launchPlayingApp(playingPackage);
                return;
            }
        } else {
            Log.w(TAG, "MusicService not running, cannot register callback");
            connectedAppText.setText(R.string.none);
        }
    }

    private String resolvePlayingPackage() {
        MusicService musicService = MusicService.getInstance();
        if (musicService != null) {
            String playingPackage = musicService.getPlayingPackageName();
            if (playingPackage != null) {
                return playingPackage;
            }
        }
        if (!isNotificationServiceEnabled()) {
            return null;
        }
        try {
            MediaSessionManager sessionManager =
                    (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (sessionManager == null) {
                return null;
            }
            ComponentName listenerComponent =
                    new ComponentName(this, MediaSessionListenerService.class);
            List<MediaController> controllers = sessionManager.getActiveSessions(listenerComponent);
            if (controllers == null) {
                return null;
            }
            for (MediaController controller : controllers) {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    return controller.getPackageName();
                }
            }
        } catch (SecurityException | IllegalStateException e) {
            Log.w(TAG, "getActiveSessions failed: " + e.getMessage());
        }
        return null;
    }

    private void launchPlayingApp(String packageName) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent != null) {
            Log.d(TAG, "Music playing, jumping to: " + packageName);
            startActivity(launchIntent);
            finish();
        } else {
            Log.w(TAG, "No launch intent for playing app: " + packageName);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        MusicService musicService = MusicService.getInstance();
        if (musicService != null) {
            musicService.setMusicServiceCallback(null);
        }
    }

    private void updatePermissionStatus() {
        if (isNotificationServiceEnabled()) {
            permissionStatusText.setText(R.string.permission_status_granted);
            permissionStatusText.setTextColor(getResources().getColor(R.color.green, getTheme()));
        } else {
            permissionStatusText.setText(R.string.permission_status_not_granted);
            permissionStatusText.setTextColor(getResources().getColor(R.color.red, getTheme()));
        }
    }

    private void updateConnectedApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            connectedAppText.setText(R.string.none);
            return;
        }

        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            connectedAppText.setText(label != null ? label.toString() : packageName);
        } catch (PackageManager.NameNotFoundException e) {
            connectedAppText.setText(packageName);
        }
    }

    private boolean isNotificationServiceEnabled() {
        String packageName = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat != null) {
            String[] names = flat.split(":");
            for (String name : names) {
                ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && cn.getPackageName().equals(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Generates a QR code bitmap for the given content using ZXing.
     * Falls back to a square pixel size based on the larger of width/height if either is 0
     * (e.g. when the view hasn't been laid out yet).
     */
    private Bitmap generateQrCodeBitmap(String content, int width, int height) {
        int size = Math.max(width, height);
        if (size <= 0) {
            size = 512;
        }
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            int matrixWidth = matrix.getWidth();
            int matrixHeight = matrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(matrixWidth, matrixHeight, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < matrixWidth; x++) {
                for (int y = 0; y < matrixHeight; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (WriterException e) {
            Log.e(TAG, "Error generating QR code", e);
            return null;
        }
    }
}