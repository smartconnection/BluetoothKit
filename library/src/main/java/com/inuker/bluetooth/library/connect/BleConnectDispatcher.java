package com.inuker.bluetooth.library.connect;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.inuker.bluetooth.library.Code;
import com.inuker.bluetooth.library.connect.request.BleConnectRequest;
import com.inuker.bluetooth.library.connect.request.BleDisconnectRequest;
import com.inuker.bluetooth.library.connect.request.BleNotifyRequest;
import com.inuker.bluetooth.library.connect.request.BleReadRequest;
import com.inuker.bluetooth.library.connect.request.BleReadRssiRequest;
import com.inuker.bluetooth.library.connect.request.BleRequest;
import com.inuker.bluetooth.library.connect.request.BleUnnotifyRequest;
import com.inuker.bluetooth.library.connect.request.BleWriteRequest;
import com.inuker.bluetooth.library.connect.response.BleResponse;
import com.inuker.bluetooth.library.utils.BluetoothConstants;
import com.inuker.bluetooth.library.utils.BluetoothLog;
import com.inuker.bluetooth.library.utils.BluetoothUtils;
import com.inuker.bluetooth.library.utils.ListUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BleConnectDispatcher implements IBleDispatch {

    public static final int MSG_REQUEST_SUCCESS = 0x100;
    public static final int MSG_REQUEST_FAILED = 0x110;

    private static final int MAX_REQUEST_COUNT = 100;

    private Handler mWorkerHandler;

    private List<BleRequest> mBleWorkList;
    private BleRequest mCurrentRequest;

    public static BleConnectDispatcher newInstance(String mac, IBleRunner runner) {
        return new BleConnectDispatcher(mac, runner);
    }

    private BleConnectDispatcher(String mac, IBleRunner runner) {
        mBleWorkList = new ArrayList<BleRequest>();
        BleConnectWorker.attch(mac, runner, this);
    }

    public void connect(BleResponse response) {
        addNewRequest(new BleConnectRequest(response));
    }

    public void disconnect() {
        addNewRequest(new BleDisconnectRequest());
    }

    public void read(UUID service, UUID character, BleResponse response) {
        addNewRequest(new BleReadRequest(service, character, response));
    }

    public void write(UUID service, UUID character, byte[] bytes,
                      BleResponse response) {
        addNewRequest(new BleWriteRequest(service, character, bytes, response));
    }

    public void notify(UUID service, UUID character, BleResponse response) {
        addNewRequest(new BleNotifyRequest(service, character, response));
    }

    public void unnotify(UUID service, UUID character) {
        addNewRequest(new BleUnnotifyRequest(service, character));
    }

    public void readRemoteRssi(BleResponse response) {
        addNewRequest(new BleReadRssiRequest(response));
    }

    private void addNewRequest(BleRequest request) {
        if (!isRequestExceedLimit()) {
            mBleWorkList.add(request);
            scheduleNextRequest();
        } else {
            notifyRequestExceedLimit(request);
        }
    }

    private boolean isRequestExceedLimit() {
        return mBleWorkList.size() >= MAX_REQUEST_COUNT;
    }

    private void addPrioRequest(BleRequest request) {
        mBleWorkList.add(0, request);
        scheduleNextRequest();
    }

    /**
     * 向worker发送一个新任务
     *
     * @param request
     */
    private void callWorkerForNewRequest(BleRequest request) {
        mWorkerHandler.obtainMessage(BleConnectWorker.MSG_SCHEDULE_NEXT, request).sendToTarget();
    }

    /**
     * 准备处理下一个请求
     */
    private void scheduleNextRequest() {
        if (mCurrentRequest != null) {
            return;
        }

        if (!ListUtils.isEmpty(mBleWorkList)) {
            mCurrentRequest = mBleWorkList.remove(0);

            if (!BluetoothUtils.isBleSupported()) {
                mCurrentRequest.setRequestCode(Code.BLE_NOT_SUPPORTED);
                dispatchRequestResult(BluetoothConstants.FAILED);
            } else if (!BluetoothUtils.isBluetoothEnabled()) {
                mCurrentRequest.setRequestCode(Code.BLUETOOTH_DISABLED);
                dispatchRequestResult(BluetoothConstants.FAILED);
            } else {
                callWorkerForNewRequest(mCurrentRequest);
            }
        }
    }

    /**
     * 重试当前任务，直接插入任务头即可
     */
    private void retryCurrentRequest() {
        BleRequest request = mCurrentRequest;
        mCurrentRequest.retry();
        mCurrentRequest = null;
        addPrioRequest(request);
    }

    private void sendMessageToResponseHandler(int what, Object obj) {
        sendMessageToResponseHandler(what, obj, null);
    }

    private void sendMessageToResponseHandler(int what, Object obj, Bundle data) {
        Message msg = mResponseHandler.obtainMessage(what, obj);

        if (data != null) {
            msg.setData(data);
        }

        msg.sendToTarget();
    }

    private void notifyRequestExceedLimit(BleRequest request) {
        request.setRequestCode(Code.REQUEST_OVERFLOW);
        sendMessageToResponseHandler(MSG_REQUEST_FAILED, request);
    }

    private final Handler mResponseHandler = new Handler(Looper.getMainLooper()) {

        @Override
        public void handleMessage(Message msg) {
            // TODO Auto-generated method stub
            BleRequest request = null;

            if (msg != null && msg.obj instanceof BleRequest) {
                request = (BleRequest) msg.obj;
            }

            switch (msg.what) {
                case MSG_REQUEST_SUCCESS:
                    request.onResponse(Code.REQUEST_SUCCESS, request.getBundle());

                    break;

                case MSG_REQUEST_FAILED:
                    request.onResponse(request.getIntExtra(BluetoothConstants.KEY_CODE, Code.REQUEST_FAILED), null);

                    break;
            }
        }
    };

    @Override
    public void notifyWorkerResult(BleRequest request, boolean result) {
        if (request == null || request != mCurrentRequest) {
            BluetoothLog.w("strange notifyWorkerResult in BleConnectDispatcher");
            return;
        }

        if (result == BluetoothConstants.SUCCESS) {
            dispatchRequestResult(result);
        } else {
            if (mCurrentRequest != null) {
                if (mCurrentRequest.canRetry()) {
                    retryCurrentRequest();
                } else {
                    dispatchRequestResult(result);
                }
            } else {
                /**
                 * 此处可能因为worker出现异常了，从而催促dispatcher分发下一个任务
                 */
                dispatchRequestResult(result);
            }
        }
    }

    /**
     * 让当前request收场回调，然后启动下一个任务
     */
    private void dispatchRequestResult(boolean result) {
        if (mCurrentRequest != null) {
            int msg = (result == BluetoothConstants.SUCCESS ?
                    MSG_REQUEST_SUCCESS : MSG_REQUEST_FAILED);
            sendMessageToResponseHandler(msg, mCurrentRequest);
        }

        mCurrentRequest = null;
        scheduleNextRequest();
    }

    @Override
    public void notifyHandlerReady(Handler handler) {
        // TODO Auto-generated method stub
        mWorkerHandler = handler;
    }
}
