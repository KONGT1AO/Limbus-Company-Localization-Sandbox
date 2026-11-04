package com.lody.virtual.client.hook.proxies.am;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.interfaces.IInjector;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.helper.AvoidRecursive;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.remote.StubActivityRecord;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import mirror.android.app.ActivityManagerNative;
import mirror.android.app.ActivityThread;
import mirror.android.app.ClientTransactionHandler;
import mirror.android.app.IActivityManager;
import mirror.android.app.servertransaction.ActivityResultItem;
import mirror.android.app.servertransaction.ClientTransaction;
import mirror.android.app.servertransaction.LaunchActivityItem;
import mirror.android.app.servertransaction.TopResumedActivityChangeItem;

public class HCallbackStub implements Handler.Callback, IInjector {

    static final String CLIENT_LOCAL_ACTIVITY_EXTRA = "_VA_|_client_local_activity_";
    private static final Set<IBinder> CLIENT_LOCAL_ACTIVITY_TOKENS =
            ConcurrentHashMap.newKeySet();
    private static final Set<IBinder> NOTIFIED_ACTIVITY_CREATE_TOKENS =
            ConcurrentHashMap.newKeySet();

    private static final int LAUNCH_ACTIVITY;
    private static final int EXECUTE_TRANSACTION;
    private static final int SCHEDULE_CRASH = getMessageWhat(ActivityThread.H.SCHEDULE_CRASH, -1);

    static {
        LAUNCH_ACTIVITY = BuildCompat.isPie() ? -1 : getMessageWhat(ActivityThread.H.LAUNCH_ACTIVITY, -1);
        EXECUTE_TRANSACTION = BuildCompat.isPie() ? getMessageWhat(ActivityThread.H.EXECUTE_TRANSACTION, -1) : -1;
    }

    private static final String TAG = HCallbackStub.class.getSimpleName();
    private static final HCallbackStub sCallback = new HCallbackStub();

    private final AvoidRecursive mAvoidRecurisve = new AvoidRecursive();
    private Handler.Callback otherCallback;

    private HCallbackStub() {
    }

    private static int getMessageWhat(mirror.RefStaticInt value, int fallback) {
        return value == null ? fallback : value.get();
    }

    public static HCallbackStub getDefault() {
        return sCallback;
    }

    private static Handler getH() {
        return ActivityThread.mH.get(VirtualCore.mainThread());
    }

    private static Handler.Callback getHCallback() {
        try {
            Handler handler = getH();
            return mirror.android.os.Handler.mCallback.get(handler);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public boolean handleMessage(Message msg) {
        if (mAvoidRecurisve.beginCall()) {
            try {
                if (LAUNCH_ACTIVITY == msg.what) {
                    if (!handleLaunchActivity(msg, msg.obj)) {
                        return true;
                    }
                } else if (BuildCompat.isPie() && EXECUTE_TRANSACTION == msg.what) {
                    if (!handleExecuteTransaction(msg)) {
                        return true;
                    }
                } else if (SCHEDULE_CRASH == msg.what) {
                    String crashReason = (String) msg.obj;
                    new RemoteException(crashReason).printStackTrace();
                    return true;
                }
                if (otherCallback != null) {
                    return otherCallback.handleMessage(msg);
                }
            } finally {
                mAvoidRecurisve.finishCall();
            }
        }
        return false;
    }

    private boolean handleExecuteTransaction(Message msg) {
        Object transaction = msg.obj;
        List<Object> activityCallbacks = getTransactionCallbacks(transaction);
        Object launchItem = findLaunchActivityItem(activityCallbacks);
        IBinder token = resolveTransactionActivityToken(transaction, launchItem);
        Object r = token != null && ClientTransactionHandler.getActivityClient != null
                ? ClientTransactionHandler.getActivityClient.call(VirtualCore.mainThread(), token)
                : null;
        if (launchItem != null) {
            Intent rawIntent = LaunchActivityItem.mIntent != null ? LaunchActivityItem.mIntent.get(launchItem) : null;
            if (rawIntent == null) {
                return true;
            }
            StubActivityRecord launchRecord = new StubActivityRecord(rawIntent);
            boolean proceed = handleLaunchActivity(msg, launchItem);
            if (proceed && r != null && launchRecord.intent != null && launchRecord.info != null) {
                ClassLoader appClassLoader = VClient.get().getClassLoader(launchRecord.info.applicationInfo);
                launchRecord.intent.setExtrasClassLoader(appClassLoader);
                ActivityThread.ActivityClientRecord.intent.set(r, launchRecord.intent);
                ActivityThread.ActivityClientRecord.activityInfo.set(r, launchRecord.info);
                if (ActivityThread.ActivityClientRecord.packageInfo != null) {
                    ActivityThread.ActivityClientRecord.packageInfo.set(r, VClient.get().getCurrentLoadedApk());
                }
            }
            return proceed;
        }
        if (r == null) {
            if (activityCallbacks == null || activityCallbacks.isEmpty()) {
                return true;
            }
            Object item = activityCallbacks.get(0);
            if(isInstance(ActivityResultItem.TYPE, item)){
                if(handleActivityResult(msg, item)){
                    return false;
                }
            }
            return true;
        } else if (BuildCompat.isQ()) {
            if (activityCallbacks != null && !activityCallbacks.isEmpty()) {
                Object item = activityCallbacks.get(0);
                if (isInstance(TopResumedActivityChangeItem.TYPE, item)) {
                    if (TopResumedActivityChangeItem.mOnTop.get(item) == ActivityThread.ActivityClientRecord.isTopResumedActivity.get(r)) {
                        VLog.e("HCallbackStub", "Activity top position already set to onTop=" + TopResumedActivityChangeItem.mOnTop.get(item));
                        return false;
                    }
                } else if(isInstance(ActivityResultItem.TYPE, item)){
                    if(handleActivityResult(msg, item)){
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> getTransactionCallbacks(Object transaction) {
        if (transaction == null) {
            return null;
        }
        if (ClientTransaction.mActivityCallbacks != null) {
            List<Object> callbacks = ClientTransaction.mActivityCallbacks.get(transaction);
            if (callbacks != null) {
                return callbacks;
            }
        }
        try {
            Method getItems = transaction.getClass().getMethod("getTransactionItems");
            Object items = getItems.invoke(transaction);
            if (items instanceof List) {
                return (List<Object>) items;
            }
        } catch (Throwable ignored) {
        }
        try {
            Field itemsField = transaction.getClass().getDeclaredField("mTransactionItems");
            itemsField.setAccessible(true);
            Object items = itemsField.get(transaction);
            if (items instanceof List) {
                return (List<Object>) items;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static IBinder resolveTransactionActivityToken(Object transaction, Object item) {
        if (transaction != null && ClientTransaction.mActivityToken != null) {
            IBinder token = ClientTransaction.mActivityToken.get(transaction);
            if (token != null) {
                return token;
            }
        }
        if (transaction != null) {
            try {
                Method getToken = transaction.getClass().getMethod("getActivityToken");
                Object token = getToken.invoke(transaction);
                if (token instanceof IBinder) {
                    return (IBinder) token;
                }
            } catch (Throwable ignored) {
            }
        }
        if (item != null) {
            try {
                Method getToken = item.getClass().getMethod("getActivityToken");
                Object token = getToken.invoke(item);
                if (token instanceof IBinder) {
                    return (IBinder) token;
                }
            } catch (Throwable ignored) {
            }
            Class<?> current = item.getClass();
            while (current != null && current != Object.class) {
                try {
                    Field tokenField = current.getDeclaredField("mActivityToken");
                    tokenField.setAccessible(true);
                    Object token = tokenField.get(item);
                    if (token instanceof IBinder) {
                        return (IBinder) token;
                    }
                } catch (Throwable ignored) {
                }
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private Object findLaunchActivityItem(List<Object> activityCallbacks) {
        if (activityCallbacks == null || activityCallbacks.isEmpty()) {
            return null;
        }
        for (Object item : activityCallbacks) {
            if (isInstance(LaunchActivityItem.TYPE, item)) {
                return item;
            }
        }
        return null;
    }

    private static boolean isInstance(Class<?> type, Object item) {
        return type != null && item != null && type.isInstance(item);
    }

    private boolean handleActivityResult(Message msg, Object r){
        return false;
    }

    private boolean handleLaunchActivity(Message msg, Object r) {
        Intent stubIntent;
        if (BuildCompat.isPie()) {
            stubIntent = LaunchActivityItem.mIntent != null ? LaunchActivityItem.mIntent.get(r) : null;
        } else {
            stubIntent = ActivityThread.ActivityClientRecord.intent.get(r);
        }
        if (stubIntent == null) {
            return true;
        }
        StubActivityRecord saveInstance = new StubActivityRecord(stubIntent);
        if (saveInstance.intent == null) {
            return true;
        }
        Intent intent = saveInstance.intent;
        IBinder token;
        if (BuildCompat.isPie()) {
            token = resolveTransactionActivityToken(msg.obj, r);
        } else {
            token = ActivityThread.ActivityClientRecord.token.get(r);
        }
        ActivityInfo info = saveInstance.info;
        if (info == null) {
            return true;
        }
        if (VClient.get().getClientConfig() == null) {
            InstalledAppInfo installedAppInfo = VirtualCore.get().getInstalledAppInfo(info.packageName, 0);
            if (installedAppInfo == null) {
                return true;
            }
            VActivityManager.get().processRestarted(info.packageName, info.processName, saveInstance.userId);
            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
            return false;
        }
        if (!VClient.get().isAppRunning()) {
            VClient.get().bindApplication(info.packageName, info.processName);
            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
            return false;
        }
        int stubOrientation = info.screenOrientation;
        if ("com.ProjectMoon.LimbusCompany".equals(info.packageName)) {
            stubOrientation = com.lody.virtual.client.hook.delegate.LimbusOrientationManager.resolveInitialOrientation(
                    VirtualCore.get().getContext(), info.screenOrientation);
        }
        if (stubOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED && token != null) {
            try {
                IActivityManager.setRequestedOrientation.call(ActivityManagerNative.getDefault.call(),
                        token, stubOrientation);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        registerActivityTokenIfNeeded(token, saveInstance, -1);
        ClassLoader appClassLoader = VClient.get().getClassLoader(info.applicationInfo);
        intent.setExtrasClassLoader(appClassLoader);
        if (BuildCompat.isPie()) {
            if (LaunchActivityItem.mIntent != null) {
                LaunchActivityItem.mIntent.set(r, intent);
            }
            if (LaunchActivityItem.mInfo != null) {
                LaunchActivityItem.mInfo.set(r, info);
            }
        } else {
            ActivityThread.ActivityClientRecord.intent.set(r, intent);
            ActivityThread.ActivityClientRecord.activityInfo.set(r, info);
        }
        return true;
    }

    public static void registerActivityTokenIfNeeded(IBinder token, StubActivityRecord record, int fallbackTaskId) {
        if (token == null || record == null) {
            return;
        }
        if (record.isClientLocal) {
            CLIENT_LOCAL_ACTIVITY_TOKENS.add(token);
            return;
        }
        if (record.virtualToken != null && NOTIFIED_ACTIVITY_CREATE_TOKENS.add(token)) {
            int taskId = fallbackTaskId;
            try {
                Integer resolved = IActivityManager.getTaskForActivity.call(
                        ActivityManagerNative.getDefault.call(),
                        token,
                        false
                );
                if (resolved != null && resolved > 0) {
                    taskId = resolved;
                }
            } catch (Throwable ignored) {
            }
            VActivityManager.get().onActivityCreate(record.virtualToken, token, taskId);
        }
    }

    public static boolean isClientLocalActivityToken(IBinder token) {
        return token != null && CLIENT_LOCAL_ACTIVITY_TOKENS.contains(token);
    }

    static boolean removeClientLocalActivityToken(IBinder token) {
        return token != null && CLIENT_LOCAL_ACTIVITY_TOKENS.remove(token);
    }

    @Override
    public void inject() {
        otherCallback = getHCallback();
        mirror.android.os.Handler.mCallback.set(getH(), this);
    }

    @Override
    public boolean isEnvBad() {
        Handler.Callback callback = getHCallback();
        boolean envBad = callback != this;
        if (callback != null && envBad) {
            VLog.d(TAG, "HCallback has bad, other callback = " + callback);
        }
        return envBad;
    }
}