package com.gangle.nble;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;

import com.gangle.nble.ScanFilter.IScanFilter;

import java.util.List;
import java.util.UUID;



@TargetApi(21)
class NBleScannerImpl implements NBleScanner {

    private Context context;
    private UUID[] uuids;
    private IScanFilter[] scanFilters;

    private ScanCallback m21Scancalback;
    private BluetoothAdapter mAdapter;
    private BleScanListener mScanListener; // of this scanner

    private Handler mHandler;
    private boolean mScanning = false;

    private final BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, final int rssi, final byte[] scanRecord) {
            processScanResult(device, rssi, scanRecord);
        }
    };

     NBleScannerImpl(final Context context) {
        this.context = context;

         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            m21Scancalback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    super.onScanResult(callbackType, result);

                    if (callbackType == ScanSettings.CALLBACK_TYPE_ALL_MATCHES) {
                        ScanRecord scanRecord = result.getScanRecord();
                        processScanResult(result.getDevice(), result.getRssi(), scanRecord == null ? new byte[]{} : scanRecord.getBytes());
                    }
                }

                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    super.onBatchScanResults(results);

                    LogUtils.d("onBatchScanResults, size:%d", results.size());

                }

                @Override
                public void onScanFailed(int errorCode) {
                    super.onScanFailed(errorCode);
                    stop();
                    LogUtils.d("onScanFailed, errorCode:%d", errorCode);
                }
            };
        }

        mHandler = new Handler(context.getMainLooper());
    }

    private void processScanResult(BluetoothDevice device, final int rssi, final byte[] scanRecord) {
        if (mScanListener == null) {
            LogUtils.e("Callback not set!");
            return;
        }

        // add device to manager
        if (NBle.manager().getDevice(device.getAddress()) == null) {
            ((NBleDeviceManagerImpl)NBle.manager()).createDevice(device.getAddress(), device.getName());
            LogUtils.v("ADDRESS:%s, RSSI:%d, NAME:%s", device.getAddress(), rssi, device.getName());
        }

        // set rssi
        NBleDeviceImpl nBleDevice = (NBleDeviceImpl)NBle.manager().getDevice(device.getAddress());
        nBleDevice.setRssi(rssi);

        // whether device match the filters
        if (scanFilters != null && scanFilters.length > 0) {
            for (IScanFilter filter : scanFilters) {
                if (filter.isMatch(device, rssi)) {
                    LogUtils.v("MATCH FILTER ADDRESS:%s, RSSI:%d, NAME:%s", device.getAddress(), rssi, device.getName());
                    mScanListener.onDeviceDiscovered(nBleDevice, scanRecord);
                    return;
                }
            }
        } else {
            LogUtils.v("NO FILTER!");
            mScanListener.onDeviceDiscovered(nBleDevice, scanRecord);
        }
    }

    public UUID[] getUuids() {
        return uuids;
    }

    public void setUuids(UUID[] uuids) {
        this.uuids = uuids;
    }

    public void setFilters(IScanFilter[] filters) {
        this.scanFilters = filters;
    }

    public boolean start(BleScanListener callback) {
        return start(callback, INDEFINITE);
    }

    public boolean start(BleScanListener callback, int duration) {
        return start(uuids, callback, duration);
    }

    private synchronized boolean start(UUID[] serviceUuids, BleScanListener listener, long duration) {
        if (listener == null) {
            LogUtils.e("Null call back, refuse to scan!");
            return false;
        }

        if (mAdapter == null) {
            BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            mAdapter = bluetoothManager.getAdapter();

            // 目前有手机是api 18以上，但getAdapter()有时返回的是null。
            if (mAdapter == null) {
                LogUtils.e("Blue Adapter is Null!");
                return false;
            }
        }


        if (!mScanning) {

            mScanListener = listener;

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                if (!mAdapter.startLeScan(serviceUuids, mLeScanCallback)) {
                    LogUtils.e("ble_scan_fail, startLeScan return false!");
                    return false;
                }
            } else {
                BluetoothLeScanner scanner = mAdapter.getBluetoothLeScanner();
                if (scanner != null) {
                    scanner.startScan(m21Scancalback);
                } else {
                    LogUtils.e("ble_scan_fail, getBluetoothLeScanner return null!");
                    return false;
                }
            }

            mScanning = true;
            mScanListener.onScanStarted();
            // Stops scanning after a defined scan duration.
            if (duration > 0) {
                mHandler.postDelayed(stopRunnable, duration);
            }
            return true;
        }

        return false;
    }

    private Runnable stopRunnable = new Runnable() {
        @Override
        public void run() {
            stop();
        }
    };

    public synchronized void stop() {
        //noinspection deprecation
        mHandler.removeCallbacks(stopRunnable);
        if (mScanning) {
            mScanning = false;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                mAdapter.stopLeScan(mLeScanCallback);
            } else {
                BluetoothLeScanner scanner = mAdapter.getBluetoothLeScanner();
                if (scanner != null) {
                    scanner.stopScan(m21Scancalback);
                }
            }


            // if the callback is an activity, it may be finished before this called
            if (mScanListener != null) {
                mScanListener.onScanStopped();
            }
        }
        mScanListener = null;
    }

    public synchronized boolean isScanning() {
        return mScanning;
    }

}
