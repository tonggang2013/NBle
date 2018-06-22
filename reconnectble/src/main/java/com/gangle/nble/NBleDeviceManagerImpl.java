package com.gangle.nble;

import android.bluetooth.BluetoothProfile;
import android.content.Context;

import com.gangle.nble.device.DeviceBase;
import com.gangle.nble.ifunction.INBleNotifyFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Observer;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

/**
 * Created by Gang Tong
 * <p/>
 * 连接描述：ble设备的连接策略基本上是用户发起的连接，是'直接'连接。当直接连接不上返回exception后，并且是维护状态时，再次连接就采用'自动'连接。
 */
class NBleDeviceManagerImpl implements NBleDeviceManager, IDeviceConnectExceptionListener, OperationManager.OnValidateOperationListener {

    /**
     * 记录的devices
     */
    private Map<String, NBleDevice> mDevices = Collections.synchronizedMap(new LinkedHashMap<String, NBleDevice>());
    private Set<NBleDevice> mMaintainSet = Collections.synchronizedSet(new HashSet<NBleDevice>());

    /**
     * 根据不同设备的notification的处理接口的列表，此表是根据设备名来区分。
     */
    private Map<String, INBleNotifyFunction> mNotifySubscription = Collections.synchronizedMap(new LinkedHashMap<String, INBleNotifyFunction>());

    /**
     * 默认的notification的处理接口。当在mNotifySubscription中没有找到对应设备的处理接口，则使用默认的。
     */
    private INBleNotifyFunction mDefaultSubscription;

    /**
     * 禁止外部新建实例
     */
    private NBleDeviceManagerImpl() {
        // prevent instantiation
    }

    private Context context;

    private Operation currentOperation;

    /**
     * 单例
     */
    private static class LazyHolder {
        private static final NBleDeviceManagerImpl INSTANCE = new NBleDeviceManagerImpl();
    }

    public static NBleDeviceManagerImpl getInstance() {
        return LazyHolder.INSTANCE;
    }

    /**
     * 获取Context
     */
    public Context getContext() {
        return this.context;
    }

    /**
     * 初始化
     */
    public void init(Context context) {
        this.context = context;

        // 第一次启动，恢复‘维护设备列表’。
        NBleDeviceManagerImpl.getInstance().restoreDevices(context);
    }

    /**
     * 创建device，INBleNotifyFunction表示后续
     */
    public NBleDevice createDevice(String address, String name) {
        assert getDevice(address) == null : String.format("The device with %s is EXIST!", address);
        NBleDevice device = new NBleDeviceImpl(NBleDeviceManagerImpl.getInstance().getContext(), address, name);
        NBleDeviceManagerImpl.getInstance().add(device);
        return device;
    }


    /**
     * 根据address来获取维护的device
     */
    public NBleDevice getDevice(String address) {
        return mDevices.get(address);
    }

    /**
     * 获取所有设备
     */
    public List<NBleDevice> getAllDevices() {
        ArrayList<NBleDevice> allDeviceSettingItems = new ArrayList<>(mDevices.values());
        return Collections.unmodifiableList(allDeviceSettingItems);
    }

    /**
     * 获取所有被维护的设备
     */
    public List<NBleDevice> getMaintainedDevices() {
        List<NBleDevice> items = new ArrayList<>();
        List<NBleDevice> allDeviceSettingItems = new ArrayList<>(mDevices.values());
        for (NBleDevice device : allDeviceSettingItems) {
            if (NBle.manager().isMaintain(device)) {
                items.add(device);
            }
        }
        return Collections.unmodifiableList(items);
    }

    /**
     * 获取所有已连接的设备
     */
    public List<NBleDevice> getConnectedDevices() {
        List<NBleDevice> items = new ArrayList<>();
        List<NBleDevice> allDeviceSettingItems = new ArrayList<>(mDevices.values());
        for (NBleDevice device : allDeviceSettingItems) {
            int state = ((NBleDeviceImpl) device).getConnectionState();
            if (state == BluetoothProfile.STATE_CONNECTED) {
                items.add(device);
            }
        }
        return Collections.unmodifiableList(items);
    }

    /**
     * 查询某设备是否是维护状态
     */
    public synchronized boolean isMaintain(NBleDevice device) {
        return device != null && mMaintainSet.contains(device);
    }

    /**
     * 根据device设置设备的维护状态
     */
    public synchronized void setMaintain(NBleDevice device, boolean bMaintain) {
        if (bMaintain) {
            mMaintainSet.add(device);
        } else {
            mMaintainSet.remove(device);
        }
        NBleDeviceManagerImpl.getInstance().storeDevices();
    }

    /**
     * 根据设备名获取notification的接口
     */
    public synchronized INBleNotifyFunction getNotification(String deviceName) {
        INBleNotifyFunction ifunction = mNotifySubscription.get(deviceName);
        return ifunction == null ? mDefaultSubscription : ifunction;
    }

    /**
     * 根据设备名注册notification的处理接口
     */
    public synchronized void registerNotification(String deviceName, INBleNotifyFunction iFunction) {
        if (!mNotifySubscription.containsKey(deviceName)) {
            mNotifySubscription.put(deviceName, iFunction);
        }
    }

    /**
     * 注册notification的默认处理接口
     */
    public void registerDefaultNotification(INBleNotifyFunction iFunction) {
        mDefaultSubscription = iFunction;
    }

    /**
     * 添加设备
     */
    public synchronized void add(NBleDevice deviceSettingItem, boolean store) {
        mDevices.put(deviceSettingItem.getAddress(), deviceSettingItem);
        if (store)
            storeDevices();
    }

    public synchronized void add(NBleDevice deviceSettingItem) {
        add(deviceSettingItem, false);
    }

    /**
     * 删除设备
     */
    public synchronized void remove(NBleDevice device) {
        LogUtils.v("remove Device:%s", device.getAddress());
        NBleDeviceImpl remove = (NBleDeviceImpl) mDevices.remove(device.getAddress());
        if (remove != null && isMaintain(remove)) {
            storeDevices();
        }
    }

    /**
     * 直接连接设备
     */
    public boolean connectDirectly(NBleDevice bleDevice) {
        return ((NBleDeviceImpl) bleDevice).connectImpl(false);
    }

    /**
     * 断开设备
     */
    public void disconnect(NBleDevice bleDevice) {
        ((NBleDeviceImpl) bleDevice).disconnectImpl();
        // 在连接过程中做disconnect，会导致连接中断，且没有回调。
        // 所以每次重连需要先做close，以及后续的判断处理。
        reconnect(bleDevice);
    }

    public void writeCharacteristic(String address, UUID serviceUuid, UUID characteristicUuid, byte[] data) {
        OperationManager.getInstance().pend(new Operation(Operation.OP_WRITE_CHARACTERISTIC, address, serviceUuid, characteristicUuid, data));
    }

    public void readCharacteristic(String address, UUID serviceUuid, UUID characteristicUuid) {
        OperationManager.getInstance().pend(new Operation(Operation.OP_READ_CHARACTERISTIC, address, serviceUuid, characteristicUuid));
    }

    public void onServicesDiscovered(String address) {
        ((NBleDeviceImpl) getDevice(address)).onServicesDiscovered(address);
    }


    public void onReadCharacteristic(String address, UUID uuid, byte[] value) {

        synchronized (currentOperation) {
            OperationManager.getInstance().done(currentOperation);
            currentOperation = null;
        }

        ((NBleDeviceImpl) getDevice(address)).onReadImpl(address, uuid, value);
    }

    public void onWriteCharacteristic(String address, UUID uuid, byte[] value) {

        synchronized (currentOperation) {
            OperationManager.getInstance().done(currentOperation);
            currentOperation = null;
        }

        ((NBleDeviceImpl) getDevice(address)).onWriteImpl(address, uuid, value);
    }

    @Override
    public void onNextPendingOperation(Operation operation) {
        synchronized (currentOperation) {
            if (currentOperation == null) {
                NBleDeviceImpl device = (NBleDeviceImpl) getDevice(operation.getAddress());
                if (device != null && device.getConnectionState() == BluetoothProfile.STATE_CONNECTED) {
                    currentOperation = operation;
                    switch (operation.getType()) {
                        case Operation.OP_READ_CHARACTERISTIC:
                            device.readImpl(operation.getServiceUuid(), operation.getCharacteristicUuid());
                            break;
                        case Operation.OP_WRITE_CHARACTERISTIC:
                            device.writeImpl(operation.getServiceUuid(), operation.getCharacteristicUuid(), operation.getData());
                            break;
                    }
                } else {
                    OperationManager.getInstance().done(operation);
                }
            } else {
                LogUtils.w("currentOperation != null");
            }
        }
    }

    /**
     * 序列化设备。只序列化设为“维护”的设备。
     */
    public void storeDevices() {
        LogUtils.v("Store Device size:%d", mMaintainSet.size());
        synchronized (mMaintainSet) {
            List<DeviceBase> list = new ArrayList<>();
            for (NBleDevice device : mMaintainSet) {
                list.add((DeviceBase) device);
            }
            NBlePreference.getInstance().saveSerialization(list);
        }
    }

    /**
     * 反序列化设备
     */
    public void restoreDevices(Context context) {
        List<DeviceBase> serializationList = NBlePreference.getInstance().restoreSerialization();
        if (serializationList != null) {
            synchronized (mDevices) {
                for (DeviceBase deviceBase : serializationList) {
                    NBleDevice device = getDevice(deviceBase.getAddress());
                    if (device == null) {
                        device = createDevice(deviceBase.getAddress(), deviceBase.getName());
                    }
                    setMaintain(device, true);
                    LogUtils.v("Restore Device:%s, isMaintain:%s", deviceBase.getAddress(), true);
                }
            }
        }
    }

    /**
     * 在连接过程中做disconnect，会导致连接中断，且没有回调。
     * 所以每次重连需要先做close，以及后续的判断处理。
     */
    protected void reconnect(final NBleDevice device) {
        Observable.just(device)
                .subscribeOn(Schedulers.newThread())
                .map(new Func1<NBleDevice, NBleDevice>() {
                    @Override
                    public NBleDevice call(NBleDevice device) {
                        ((NBleDeviceImpl) device).close();
                        return device;
                    }
                })
                .filter(new Func1<NBleDevice, Boolean>() {
                    @Override
                    public Boolean call(NBleDevice device) {
                        return NBleUtil.isAdapterEnable(context) && NBleDeviceManagerImpl.getInstance().isMaintain(device);
                    }
                })
                .delay(2, TimeUnit.SECONDS)
                .subscribeOn(Schedulers.immediate())
                .map(new Func1<NBleDevice, Boolean>() {
                    @Override
                    public Boolean call(NBleDevice device) {
                        return connectDirectly(device);
                    }
                })
                .subscribe(new Observer<Boolean>() {
                    @Override
                    public void onCompleted() {

                    }

                    @Override
                    public void onError(Throwable e) {
                        reconnect(device);
                    }

                    @Override
                    public void onNext(Boolean s) {

                    }
                });
    }

    @Override
    public void onConnectException(NBleDevice device, int status) {
        reconnect(device);
    }

}
