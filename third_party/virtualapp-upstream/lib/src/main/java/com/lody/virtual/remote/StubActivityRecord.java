package com.lody.virtual.remote;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;

import com.lody.virtual.helper.compat.BundleCompat;

public class StubActivityRecord {
    private static final String EXTRA_CLIENT_LOCAL_ACTIVITY = "_VA_|_client_local_activity_";
    public Intent intent;
    public ActivityInfo info;
    public int userId;
    public IBinder virtualToken;
    public boolean isClientLocal;

    public StubActivityRecord(Intent intent, ActivityInfo info, int userId, IBinder virtualToken) {
        this.intent = intent;
        this.info = info;
        this.userId = userId;
        this.virtualToken = virtualToken;
        this.isClientLocal = false;
    }

    public StubActivityRecord(Intent stub) {
        this.intent = stub.getParcelableExtra("_VA_|_intent_");
        this.info = stub.getParcelableExtra("_VA_|_info_");
        this.userId = stub.getIntExtra("_VA_|_user_id_", 0);
        this.virtualToken = BundleCompat.getBinder(stub, "_VA_|_token_");
        this.isClientLocal = stub.getBooleanExtra(EXTRA_CLIENT_LOCAL_ACTIVITY, false);
    }

    public void saveToIntent(Intent stub) {
        stub.putExtra("_VA_|_intent_", intent);
        stub.putExtra("_VA_|_info_", info);
        stub.putExtra("_VA_|_user_id_", userId);
        BundleCompat.putBinder(stub, "_VA_|_token_", virtualToken);
        if (isClientLocal) {
            stub.putExtra(EXTRA_CLIENT_LOCAL_ACTIVITY, true);
        }
    }
}