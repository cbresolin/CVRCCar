/*
 * This file is part of the Autonomous Android Vehicle (AAV) application.
 *
 * AAV is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AAV is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AAV.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.kreolite.androvision;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.JavaCameraView;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class carTracker extends AppCompatActivity implements CvCameraViewListener2 {

	private static final String _TAG = "carTrackerActivity";
	static final double MIN_CONTOUR_AREA = 1500;
	static final int SCREEN_WIDTH = 1920; // initially 352
	static final int SCREEN_HEIGHT = 1080; // initially 288

    static final double FORWARD_BOUNDARY_PERCENT = -0.03;
    static final double REVERSE_BOUNDARY_PERCENT = 0.20;

	private Mat _rgbaImage;

	private JavaCameraView _openCvCameraView;
	private ActuatorController _mainController;

	volatile double _contourArea = MIN_CONTOUR_AREA;
	volatile Point _targetCenter = new Point(-1, -1);
	Point _screenCenter = new Point(-1, -1);
	int _countOutOfFrame = 0;

	Mat _hsvMat;
	Mat _processedMat;
	Mat _dilatedMat;
	Scalar _lowerThreshold;
	Scalar _upperThreshold;
	final List<MatOfPoint> contours = new ArrayList<>();

	SharedPreferences _sharedPreferences;
	GestureDetector _gestureDetector;
	static int _trackingColor = 0;

	private boolean _showContourEnable = true;
    String _lastPwmJsonValues = "";
    boolean _isReversingHandled = false;

	// See Static Initialization of OpenCV (http://tinyurl.com/zof437m)
	//
	static {
		if (!OpenCVLoader.initDebug()) {
			Log.e("ERROR", "Unable to load OpenCV");
		}
	}

	private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
		@Override
		public void onManagerConnected(int status) {
			switch (status) {
			case LoaderCallbackInterface.SUCCESS: {
				_openCvCameraView.enableView();
				_hsvMat = new Mat();
				_processedMat = new Mat();
				_dilatedMat = new Mat();
			}
				break;
			default: {
				super.onManagerConnected(status);
			}
				break;
			}
		}
	};

	/*
     * Notifications from UsbService will be received here.
     */
	private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			switch (intent.getAction()) {
				case UsbService.ACTION_USB_PERMISSION_GRANTED: // USB PERMISSION GRANTED
					Toast.makeText(context, "USB Ready", Toast.LENGTH_SHORT).show();
					break;
				case UsbService.ACTION_USB_PERMISSION_NOT_GRANTED: // USB PERMISSION NOT GRANTED
					Toast.makeText(context, "USB Permission not granted", Toast.LENGTH_SHORT).show();
					break;
				case UsbService.ACTION_NO_USB: // NO USB CONNECTED
					Toast.makeText(context, "No USB connected", Toast.LENGTH_SHORT).show();
					break;
				case UsbService.ACTION_USB_DISCONNECTED: // USB DISCONNECTED
					Toast.makeText(context, "USB disconnected", Toast.LENGTH_SHORT).show();
					break;
				case UsbService.ACTION_USB_NOT_SUPPORTED: // USB NOT SUPPORTED
					Toast.makeText(context, "USB device not supported", Toast.LENGTH_SHORT).show();
					break;
			}
		}
	};
	private UsbService usbService;
	private MyHandler mHandler;
	private final ServiceConnection usbConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName arg0, IBinder arg1) {
			usbService = ((UsbService.UsbBinder) arg1).getService();
			usbService.setHandler(mHandler);
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			usbService = null;
		}
	};

	private void startService(Class<?> service, ServiceConnection serviceConnection, Bundle extras) {
		if (!UsbService.SERVICE_CONNECTED) {
			Intent startService = new Intent(this, service);
			if (extras != null && !extras.isEmpty()) {
				Set<String> keys = extras.keySet();
				for (String key : keys) {
					String extra = extras.getString(key);
					startService.putExtra(key, extra);
				}
			}
			startService(startService);
		}
		Intent bindingIntent = new Intent(this, service);
		bindService(bindingIntent, serviceConnection, Context.BIND_AUTO_CREATE);
	}

	private void setFilters() {
		IntentFilter filter = new IntentFilter();
		filter.addAction(UsbService.ACTION_USB_PERMISSION_GRANTED);
		filter.addAction(UsbService.ACTION_NO_USB);
		filter.addAction(UsbService.ACTION_USB_DISCONNECTED);
		filter.addAction(UsbService.ACTION_USB_NOT_SUPPORTED);
		filter.addAction(UsbService.ACTION_USB_PERMISSION_NOT_GRANTED);
		registerReceiver(mUsbReceiver, filter);
	}

	/*
     * This handler will be passed to UsbService. Data received from serial port is displayed through this handler
     */
	private static class MyHandler extends Handler {
		private final WeakReference<carTracker> mActivity;

		MyHandler(carTracker activity) {
			mActivity = new WeakReference<>(activity);
		}

		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case UsbService.MESSAGE_FROM_SERIAL_PORT:
					String data = (String) msg.obj;
					Log.d(_TAG, "Received data from serial: " + data);
					// Toast.makeText(mActivity.get(), "DATA_RCV: " + data, Toast.LENGTH_SHORT).show();
					break;
				case UsbService.CTS_CHANGE:
					Toast.makeText(mActivity.get(), "CTS_CHANGE",Toast.LENGTH_LONG).show();
					break;
				case UsbService.DSR_CHANGE:
					Toast.makeText(mActivity.get(), "DSR_CHANGE",Toast.LENGTH_LONG).show();
					break;
			}
		}
	}

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		setContentView(R.layout.activity_car);

		PreferenceManager.setDefaultValues(this, R.xml.settings, false);
		_sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		_trackingColor = Integer.parseInt(_sharedPreferences.getString(getString(R.string.color_key), "0"));

		if (_trackingColor == 0) {
			_lowerThreshold = new Scalar(60, 100, 30); // Green
			_upperThreshold = new Scalar(130, 255, 255);
		} else if (_trackingColor == 1) {
			_lowerThreshold = new Scalar(160, 50, 90); // Purple
			_upperThreshold = new Scalar(255, 255, 255);
		} else if (_trackingColor == 2) {
			_lowerThreshold = new Scalar(1, 50, 150); // Orange
			_upperThreshold = new Scalar(60, 255, 255);
		}
		_showContourEnable = _sharedPreferences.getBoolean("contour", true);
		_openCvCameraView = (JavaCameraView) findViewById(R.id.aav_activity_surface_view);
		_openCvCameraView.setCvCameraViewListener(this);

		_openCvCameraView.setMaxFrameSize(SCREEN_WIDTH, SCREEN_HEIGHT);
		_mainController = new ActuatorController();
		_countOutOfFrame = 0;

		_gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
			@Override
			public void onLongPress(MotionEvent e) {
				startActivityForResult(new Intent(getApplicationContext(), SettingsActivity.class), 0);
			}
		});
		mHandler = new MyHandler(this);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		_showContourEnable = _sharedPreferences.getBoolean("contour", true);
		_trackingColor = Integer.parseInt(_sharedPreferences.getString(getString(R.string.color_key), "0"));

		switch (_trackingColor) {
		case 0: // Green
			_lowerThreshold.set(new double[] { 60, 100, 30, 0 });
			_upperThreshold.set(new double[] { 130, 255, 255, 0 });
			break;
		case 1: // Purple
			_lowerThreshold.set(new double[] { 160, 50, 90 });
			_upperThreshold.set(new double[] { 255, 255, 255, 0 });
			break;
		case 2: // Orange
			_lowerThreshold.set(new double[] { 1, 50, 150 });
			_upperThreshold.set(new double[] { 60, 255, 255, 0 });
			break;
		default:
			_lowerThreshold.set(new double[] { 60, 100, 30, 0 });
			_upperThreshold.set(new double[] { 130, 255, 255, 0 });
			break;
		}
	}

	@Override
	public void onResume() {
		super.onResume();

		// if (!OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_1_0, this, mLoaderCallback)) {
		// Log.e(_TAG, "Cannot connect to OpenCV Manager");
		// }
		mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
		hideNavigationBar();
		setFilters();  // Start listening notifications from UsbService
		startService(UsbService.class, usbConnection, null); // Start UsbService(if it was not started before) and Bind it
	}

	@Override
	public void onPause() {
		super.onPause();

		if (_openCvCameraView != null)
			_openCvCameraView.disableView();

		unregisterReceiver(mUsbReceiver);
		unbindService(usbConnection);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		if (_openCvCameraView != null)
			_openCvCameraView.disableView();
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		getWindow().getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
						| View.SYSTEM_UI_FLAG_FULLSCREEN);
		return _gestureDetector.onTouchEvent(event);
	}

	private void hideNavigationBar() {
		getWindow().getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
						| View.SYSTEM_UI_FLAG_FULLSCREEN);
	}

	@Override
	public void onCameraViewStarted(int width, int height) {
		_rgbaImage = new Mat(height, width, CvType.CV_8UC4);
		_screenCenter.x = _rgbaImage.size().width / 2;
		_screenCenter.y = _rgbaImage.size().height / 2;
	}

	@Override
	public void onCameraViewStopped() {
		_mainController.reset();
		_rgbaImage.release();
		_targetCenter.x = -1;
		_targetCenter.y = -1;
		updateActuator();
	}

	@Override
	public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
		//noinspection SynchronizationOnLocalVariableOrMethodParameter
		synchronized (inputFrame) {
			_rgbaImage = inputFrame.rgba();
			double current_contour;

            // In contrast to the C++ interface, Android API captures images in the RGBA format.
			// Also, in HSV space, only the hue determines which color it is. Saturation determines
			// how 'white' the color is, and Value determines how 'dark' the color is.
			Imgproc.cvtColor(_rgbaImage, _hsvMat, Imgproc.COLOR_RGB2HSV_FULL);
			Core.inRange(_hsvMat, _lowerThreshold, _upperThreshold, _processedMat);
			Imgproc.erode(_processedMat, _dilatedMat, new Mat());
			Imgproc.findContours(_dilatedMat, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            MatOfPoint2f points = new MatOfPoint2f();
            _contourArea = 0;

			for (int i = 0, n = contours.size(); i < n; i++) {
				current_contour = Imgproc.contourArea(contours.get(i));
				if (current_contour > _contourArea) {
					_contourArea = current_contour;
                    contours.get(i).convertTo(points, CvType.CV_32FC2); // contours.get(x) is a single MatOfPoint, but to use minEnclosingCircle we need to pass a MatOfPoint2f so we need to do a
					// conversion
				}
			}

			if (!points.empty() && _contourArea > MIN_CONTOUR_AREA) {
				Imgproc.minEnclosingCircle(points, _targetCenter, null);
				if (_showContourEnable) {
                    Imgproc.circle(_rgbaImage,
                            _targetCenter,
                            5,
                            new Scalar(255, 255, 10),
                            Core.FILLED);

                    Imgproc.circle(_rgbaImage,
                            _targetCenter,
                            (int) Math.round(Math.sqrt(_contourArea / Math.PI)),
                            new Scalar(255, 255, 10), 2, 0, 0);
                }
			}
            updateActuator();
			contours.clear();
		}
		return _rgbaImage;
	}

    private void updateActuator(){
        String _pwmJsonValues, _pwmJsonNeutralValues;

        try {
            if (_contourArea > MIN_CONTOUR_AREA) {
                _mainController.updateTargetPWM(_screenCenter, _targetCenter,
                        FORWARD_BOUNDARY_PERCENT,
                        REVERSE_BOUNDARY_PERCENT);
                _countOutOfFrame = 0;
            } else
            {
                _countOutOfFrame++;
                if (_countOutOfFrame > 2) {
                    _targetCenter.x = -1;
                    _targetCenter.y = -1;
                    _countOutOfFrame = 0;
                    _mainController.reset();
                }
            }

            _pwmJsonValues = _mainController.getPWMValuesToJson();

            if ((_pwmJsonValues != null) && !_pwmJsonValues.contentEquals(_lastPwmJsonValues)) {
                Log.i(_TAG, "Update Actuator:");
                Log.i(_TAG, "Screen Center = " + _screenCenter);
                Log.i(_TAG, "Target Center = " + _targetCenter);
                Log.i(_TAG, "Current Contour Area = " + _contourArea);

                if (usbService != null) {
                    if (!_mainController.isReversing()) {
                        Log.i(_TAG, "Sending PWM values: " + _pwmJsonValues);
                        usbService.write(_pwmJsonValues.getBytes());
                        _isReversingHandled = false;
                    }
                    else {
                        Log.i(_TAG, "Sending PWM values: " + _pwmJsonValues);
                        usbService.write(_pwmJsonValues.getBytes());

                        // When reversing, need to send neutral first
                        if (!_isReversingHandled) {
                            _pwmJsonNeutralValues = _mainController.getPWMNeutralValuesToJson();
                            Log.i(_TAG, "Sending PWM values: " + _pwmJsonNeutralValues);
                            usbService.write(_pwmJsonNeutralValues.getBytes());

                            Log.i(_TAG, "Sending PWM values: " + _pwmJsonValues);
                            usbService.write(_pwmJsonValues.getBytes());
                            _isReversingHandled = true;
                        }
                    }
                }
                _lastPwmJsonValues = _pwmJsonValues;
            }
        } catch (InterruptedException e) {
            Log.e(_TAG, e.getMessage());
        }
    }
}