package com.example.dy.camerapublishdemo1;

import android.hardware.Camera;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.alex.livertmppushsdk.RtmpSessionManager;
import com.alex.livertmppushsdk.SWVideoEncoder;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MainActivity extends AppCompatActivity {

    private Thread _h264EncoderThread = null;
    private Camera _mCamera = null;
    String LOG_TAG = "activity";
    private final int WIDTH_DEF = 480;
    private final int HEIGHT_DEF = 640;
    private Lock _yuvQueueLock = new ReentrantLock();
    private SWVideoEncoder _swEncH264 = null;
    private Queue<byte[]> _YUVQueue = new LinkedList<byte[]>();
    LiveCameraView liveCameraView;
    FrameLayout frameLayout;
    private RtmpSessionManager _rtmpSessionMgr = null;
    //相机图像类型
    Integer _iCameraCodecType = 0;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        //打开相机
        _mCamera = openCamera(Camera.CameraInfo.CAMERA_FACING_BACK);
        if(_mCamera==null){
            Toast.makeText(this,"打开摄像头失败",Toast.LENGTH_SHORT).show();
            finish();
        }
        //初始化相机,对象机做一些初始化设置
        InitCamera();
        //初始化控制器
        _rtmpSessionMgr = new RtmpSessionManager();
        //推流
        _rtmpSessionMgr.Start("rtmp://172.18.208.176:1935/myapp/test?pass=password");

        int iFormat = _iCameraCodecType;
        //初始化编码器
        _swEncH264 = new SWVideoEncoder(WIDTH_DEF, HEIGHT_DEF, 20, 800 * 1000);
        //开始以iformat格式编码
        _swEncH264.start(iFormat);

        //初始化编码线程
        _h264EncoderThread = new Thread(_h264Runnable);
        //设置线程优先级
        _h264EncoderThread.setPriority(Thread.MAX_PRIORITY);
        //开始h264编码线程
        _h264EncoderThread.start();
    }
    //打开摄像头
    public Camera openCamera(int cameraId) {
        try{
            return Camera.open(cameraId);
        }catch(Exception e) {
            Toast.makeText(this,"打开摄像头失败",Toast.LENGTH_SHORT).show();
            finish();
            return null;
        }
    }

    //初始化相机
    public void InitCamera() {
        Camera.Parameters p = _mCamera.getParameters();
        Camera.Size prevewSize = p.getPreviewSize();
        Log.i("相机预览大小", "Original Width:" + prevewSize.width + ", height:" + prevewSize.height);
        //获取支持的预览大小
        List<Camera.Size> PreviewSizeList = p.getSupportedPreviewSizes();
        //获取预览格式
        List<Integer> PreviewFormats = p.getSupportedPreviewFormats();
        Log.i(LOG_TAG, "Listing all supported preview sizes");
        //输出支持的大小
        for (Camera.Size size : PreviewSizeList) {
            Log.i("相机支持的数据", "  w: " + size.width + ", h: " + size.height);
        }

        Integer iNV21Flag = 0;
        Integer iYV12Flag = 0;
        for (Integer yuvFormat : PreviewFormats) {
            Log.i(LOG_TAG, "preview formats:" + yuvFormat);
            //如果相机支持格式YV12，设置iYV12Flag的值
            if (yuvFormat == android.graphics.ImageFormat.YV12) {
                iYV12Flag = android.graphics.ImageFormat.YV12;
            }
            //如果相机支持格式NV21，设置iNV21Flag的值
            if (yuvFormat == android.graphics.ImageFormat.NV21) {
                iNV21Flag = android.graphics.ImageFormat.NV21;
            }
        }


        //获取相机编码类型（图片格式）
        if (iNV21Flag != 0) {
            _iCameraCodecType = iNV21Flag;
        } else if (iYV12Flag != 0) {
            _iCameraCodecType = iYV12Flag;
        }
        p.setPreviewSize(HEIGHT_DEF, WIDTH_DEF);
        //相机图片格式
        p.setPreviewFormat(_iCameraCodecType);
        //设置帧数
        p.setPreviewFrameRate(20);

        //设置相机方向
        _mCamera.setDisplayOrientation(0);
        //设置相机方向
        p.setRotation(0);
        //相机回调
        _mCamera.setPreviewCallback(_previewCallback);
        _mCamera.setParameters(p);

        //相机预览布局
        frameLayout = (FrameLayout) findViewById(R.id.frameLayout);
        liveCameraView = new LiveCameraView(this);
        liveCameraView.setCamera(_mCamera);
        frameLayout.addView(liveCameraView);
        //自动对焦
        _mCamera.autoFocus(new Camera.AutoFocusCallback() {
            @Override
            public void onAutoFocus(boolean success, Camera camera) {

            }
        });
        //开始预览
        _mCamera.startPreview();
    }

    //相机回调,将视频图像格式转换并加入到队列_YUVQueue中
    private Camera.PreviewCallback _previewCallback = new Camera.PreviewCallback() {

        /*
        * 摄像头录像的字节数据==YUV
        * */
        @Override
        public void onPreviewFrame(byte[] YUV, Camera currentCamera) {
            boolean bBackCameraFlag = true;
            byte[] yuv420 = null;

            //如果相机图像格式是YV12
            if (_iCameraCodecType == android.graphics.ImageFormat.YV12) {
                yuv420 = new byte[YUV.length];
                _swEncH264.swapYV12toI420_Ex(YUV, yuv420, HEIGHT_DEF, WIDTH_DEF);
                //如果相机图像格式是NV21
            } else if (_iCameraCodecType == android.graphics.ImageFormat.NV21) {
                //写数据到yuv420
                yuv420 = _swEncH264.swapNV21toI420(YUV, HEIGHT_DEF, WIDTH_DEF);
            }

            if (yuv420 == null) {
                return;
            }

            //上锁
            _yuvQueueLock.lock();
            if (_YUVQueue.size() > 1) {
                _YUVQueue.clear();
            }
            //入队列
            _YUVQueue.offer(yuv420);
            //解锁
            _yuvQueueLock.unlock();
        }
    };

    //取出_YUVQueue队列中的图像数据，转换图像方向并编码为h264格式插入_rtmpSessionMgr中
    private Runnable _h264Runnable = new Runnable() {
        @Override
        public void run() {
            while (!_h264EncoderThread.interrupted()) {
                int iSize = _YUVQueue.size();
                if (iSize > 0) {
                    _yuvQueueLock.lock();
                    //取出队列中第一个元素
                    byte[] yuvData = _YUVQueue.poll();
                    if (iSize > 9) {
                        Log.i(LOG_TAG, "###YUV Queue len=" + _YUVQueue.size() + ", YUV length=" + yuvData.length);
                    }

                    _yuvQueueLock.unlock();
                    if (yuvData == null) {
                        continue;
                    }

                    byte[] _yuvEdit = new byte[WIDTH_DEF * HEIGHT_DEF * 3 / 2];
                    //前置摄像头转换方向
                    //_yuvEdit = _swEncH264.YUV420pRotate270(yuvData, HEIGHT_DEF, WIDTH_DEF);
                    //后置摄像头转换方向
                    _yuvEdit = _swEncH264.YUV420pRotate90(yuvData, HEIGHT_DEF, WIDTH_DEF);

                    byte[] h264Data = _swEncH264.EncoderH264(_yuvEdit);
                    if (h264Data != null) {
                        //插入视频数据
                        _rtmpSessionMgr.InsertVideoData(h264Data);
                    }
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            _YUVQueue.clear();
        }
    };
}
