package com.jack.zremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * In-app QR scanner: camera preview via Camera2 + live decoding via ZXing.
 * On a successful scan of an http(s) URL, returns it to MainActivity.
 */
@SuppressLint("MissingPermission") // CAMERA permission is checked before every camera call
public class ScanActivity extends Activity {

    public static final String EXTRA_URL = "url";
    private static final int REQ_CAM = 52001;
    private static final long FRAME_INTERVAL_MS = 200;

    private TextureView textureView;
    private TextView statusView;
    private Button torchButton;

    private CameraManager cameraManager;
    private String cameraId;
    private boolean flashAvailable;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private CaptureRequest.Builder requestBuilder;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private QRCodeReader reader;
    private Map<DecodeHintType, Object> hints;
    private long lastDecodeMs;
    private boolean torchOn;
    private boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        reader = new QRCodeReader();
        hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        buildUi();

        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            bindWhenReady();
        } else {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAM);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setFitsSystemWindows(true);

        textureView = new TextureView(this);
        root.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("对准电脑上的二维码");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(17);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        titleLp.bottomMargin = dp(120);
        root.addView(title, titleLp);

        statusView = new TextView(this);
        statusView.setText("正在启动相机…");
        statusView.setTextColor(0xFF9CA3AF);
        statusView.setTextSize(13);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statusLp.bottomMargin = dp(92);
        root.addView(statusView, statusLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        torchButton = new Button(this);
        torchButton.setText("手电筒");
        torchButton.setTextColor(0xFFFFFFFF);
        torchButton.setBackgroundColor(0x33FFFFFF);
        torchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                torchOn = !torchOn;
                applyTorch();
            }
        });
        LinearLayout.LayoutParams torchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        torchLp.setMargins(dp(8), 0, dp(8), 0);
        buttons.addView(torchButton, torchLp);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setTextColor(0xFFFFFFFF);
        cancel.setBackgroundColor(0x33FFFFFF);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelLp.setMargins(dp(8), 0, dp(8), 0);
        buttons.addView(cancel, cancelLp);

        FrameLayout.LayoutParams buttonsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        buttonsLp.bottomMargin = dp(24);
        root.addView(buttons, buttonsLp);

        setContentView(root);
    }

    private void bindWhenReady() {
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    closeCamera();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            });
        }
    }

    private void openCamera() {
        if (cameraDevice != null) {
            return;
        }
        startBgThread();
        try {
            cameraId = pickBackCamera();
            if (cameraId == null) {
                setStatus("找不到可用相机");
                return;
            }
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(cameraId);
            flashAvailable = Boolean.TRUE.equals(cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            torchButton.setVisibility(flashAvailable ? View.VISIBLE : View.GONE);

            StreamConfigurationMap map =
                    cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size analysis = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888), 1280, 720);
            imageReader = ImageReader.newInstance(analysis.getWidth(), analysis.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(
                    new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(ImageReader r) {
                            handleFrame(r);
                        }
                    }, bgHandler);

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    closeCamera();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    setStatus("相机打开失败 (" + error + ")");
                    closeCamera();
                }
            }, bgHandler);
        } catch (Exception e) {
            setStatus("相机异常: " + e.getMessage());
            closeCamera();
        }
    }

    private String pickBackCamera() {
        try {
            String fallback = null;
            for (String id : cameraManager.getCameraIdList()) {
                Integer facing = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraMetadata.LENS_FACING_BACK) {
                    return id;
                }
                fallback = id;
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private void createSession() {
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null || cameraDevice == null) {
                return;
            }
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map =
                    cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size preview = chooseSize(map.getOutputSizes(SurfaceTexture.class), 1280, 720);
            st.setDefaultBufferSize(preview.getWidth(), preview.getHeight());
            Surface previewSurface = new Surface(st);

            requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(previewSurface);
            requestBuilder.addTarget(imageReader.getSurface());
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            List<Surface> targets = new ArrayList<>(
                    Arrays.asList(previewSurface, imageReader.getSurface()));
            cameraDevice.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    applyTorch();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    setStatus("相机初始化失败");
                }
            }, bgHandler);
        } catch (Exception e) {
            setStatus("相机异常: " + e.getMessage());
        }
    }

    private void applyTorch() {
        if (requestBuilder == null || captureSession == null || !flashAvailable) {
            return;
        }
        try {
            requestBuilder.set(CaptureRequest.FLASH_MODE, torchOn
                    ? CaptureRequest.FLASH_MODE_TORCH
                    : CaptureRequest.FLASH_MODE_OFF);
            captureSession.setRepeatingRequest(requestBuilder.build(), null, bgHandler);
        } catch (Exception ignored) {
        }
    }

    private void handleFrame(ImageReader r) {
        if (finished) {
            return;
        }
        Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastDecodeMs < FRAME_INTERVAL_MS) {
                return;
            }
            lastDecodeMs = now;
            final String url = extractAndDecode(image);
            if (url != null) {
                finished = true;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        vibrateOnce();
                        if (getCallingActivity() == null) {
                            // Opened from the notification bar (not for-result):
                            // hand the pairing URL straight to MainActivity.
                            Intent connect = new Intent(ScanActivity.this, MainActivity.class);
                            connect.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            connect.putExtra(MainActivity.EXTRA_CONNECT_URL, url);
                            startActivity(connect);
                        }
                        Intent data = new Intent();
                        data.putExtra(EXTRA_URL, url);
                        setResult(RESULT_OK, data);
                        finish();
                    }
                });
            }
        } catch (Exception ignored) {
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private String extractAndDecode(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        yBuf.rewind();
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();
        int width = image.getWidth();
        int height = image.getHeight();

        byte[] data;
        int dataWidth;
        if (pixelStride == 1 && yBuf.remaining() >= rowStride * (height - 1) + width) {
            // Fast path: hand the (possibly padded) Y plane straight to ZXing.
            data = new byte[rowStride * height];
            yBuf.get(data, 0, Math.min(yBuf.remaining(), data.length));
            dataWidth = rowStride;
        } else {
            // Slow path: de-stride/pack pixel by pixel.
            data = new byte[width * height];
            for (int row = 0; row < height; row++) {
                int base = row * rowStride;
                for (int col = 0; col < width; col++) {
                    data[row * width + col] = yBuf.get(base + col * pixelStride);
                }
            }
            dataWidth = width;
        }

        LuminanceSource source = new PlanarYUVLuminanceSource(
                data, dataWidth, height, 0, 0, width, height, false);
        Result result = tryDecode(source);
        if (result == null) {
            result = tryDecode(source.rotateCounterClockwise());
        }
        if (result == null) {
            return null;
        }
        String text = result.getText();
        if (text == null) {
            return null;
        }
        text = text.trim();
        if (text.startsWith("http://") || text.startsWith("https://")) {
            return text;
        }
        return null; // Not a URL: keep scanning
    }

    private Result tryDecode(LuminanceSource source) {
        try {
            return reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
        } catch (Exception e) {
            return null; // NotFoundException etc. — normal for most frames
        }
    }

    private void vibrateOnce() {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            v = vm == null ? null : vm.getDefaultVibrator();
        } else {
            v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (v != null) {
            v.vibrate(80);
        }
    }

    private Size chooseSize(Size[] sizes, int targetW, int targetH) {
        if (sizes == null || sizes.length == 0) {
            return new Size(targetW, targetH);
        }
        Size best = sizes[0];
        long bestDiff = Long.MAX_VALUE;
        for (Size s : sizes) {
            if (s.getWidth() < s.getHeight()) {
                continue; // prefer landscape sensor frames; rotation handled in decode
            }
            long diff = Math.abs((long) s.getWidth() * s.getHeight()
                    - (long) targetW * targetH);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    private void setStatus(String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusView.setText(text);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                bindWhenReady();
            } else {
                setStatus("相机权限被拒绝，无法扫码");
            }
        }
    }

    private void startBgThread() {
        if (bgThread == null) {
            bgThread = new HandlerThread("qr-scan");
            bgThread.start();
            bgHandler = new Handler(bgThread.getLooper());
        }
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
            }
        } catch (Exception ignored) {
        }
        captureSession = null;
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
            }
        } catch (Exception ignored) {
        }
        cameraDevice = null;
        try {
            if (imageReader != null) {
                imageReader.close();
            }
        } catch (Exception ignored) {
        }
        imageReader = null;
    }

    private void stopBgThread() {
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
            bgHandler = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED && textureView.isAvailable()) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopBgThread();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
