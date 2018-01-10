package com.example.dy.screenrecoder_demo1;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import net.yrom.screenrecorder.rtmp.RESFlvData;
import net.yrom.screenrecorder.rtmp.RESFlvDataCollecter;
import net.yrom.screenrecorder.task.RtmpStreamingSender;

import java.util.concurrent.ExecutorService;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final int REQUEST_CODE = 1;
    private Button mButton;
    private EditText mRtmpAddET;
    private MediaProjectionManager mMediaProjectionManager;
    MediaProjection mediaProjection;
    private ScreenRecorder mRecorder;
    private RtmpStreamingSender streamingSender;
    private ExecutorService executorService;
    private String rtmpAddr;

    static {
        System.loadLibrary("screenrecorderrtmp");
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mButton = (Button) findViewById(R.id.button);
        mRtmpAddET = (EditText) findViewById(R.id.et_rtmp_address);
        mButton.setOnClickListener(this);
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

    }

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            DisplayMetrics metrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
            ScreenRecorder.ScreenRecorderBinder binder = (ScreenRecorder.ScreenRecorderBinder) service;
            mRecorder = binder.getRecordService();
            //创建收集器
            RESFlvDataCollecter collecter = new RESFlvDataCollecter() {
                @Override
                public void collect(RESFlvData flvData, int type) {
                    //放东西进推流器
                    streamingSender.sendFood(flvData, type);
                }
            };
            mRecorder.setDta(collecter, RESFlvData.VIDEO_WIDTH, RESFlvData.VIDEO_HEIGHT, RESFlvData.VIDEO_BITRATE, 1, mediaProjection,streamingSender);
            //开始录制
            mRecorder.start();
            //new Thread(streamingSender).start();
            //metrics.widthPixels, metrics.heightPixels, metrics.densityDpi
            //startBtn.setEnabled(true);
            //startBtn.setText(recordService.isRunning() ? "stop_record" : "start_record");
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {}
    };

    /*
    * 开始录屏推流
    * */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        //创建MediaProjection
        mediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e("@@", "media projection is null");
            return;
        }
        //推流地址
        rtmpAddr = mRtmpAddET.getText().toString().trim();
        if (TextUtils.isEmpty(rtmpAddr)) {
            Toast.makeText(this, "rtmp address cannot be null", Toast.LENGTH_SHORT).show();
            return;
        }
        //创建推流器
        streamingSender = new RtmpStreamingSender();
        //设置推流地址
        streamingSender.sendStart(rtmpAddr);

        Intent intent = new Intent(this,ScreenRecorder.class);
        bindService(intent,connection,BIND_AUTO_CREATE);
        //finish();
        //添加streamingSender线程
        //executorService = Executors.newCachedThreadPool();
        //executorService.execute(streamingSender);

        Toast.makeText(MainActivity.this, "Screen recorder is running...", Toast.LENGTH_SHORT).show();

        mButton.setText("Stop Recorder");

        //window = new Window(ScreenRecordActivity.this);
        //window.createWindowManager();
        //window.createDesktopLayout();
        //window.showDesk();
        moveTaskToBack(true);
    }

    @Override
    public void onClick(View v) {
        if (mRecorder != null) {
            //结束录屏
            stopScreenRecord();
        } else {
            //创建录屏
            createScreenCapture();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRecorder != null) {
            stopScreenRecord();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        //if (isRecording) stopScreenRecordService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        //if (isRecording) startScreenRecordService();
    }


    private void createScreenCapture() {
        //开始录制

        Intent captureIntent = mMediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_CODE);
    }

    private void stopScreenRecord() {
        mRecorder.exit();
        mRecorder = null;
        if (streamingSender != null) {
            streamingSender.sendStop();
            streamingSender.quit();
            streamingSender = null;
        }
        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }
        mButton.setText("Restart recorder");
    }


}
