package com.linye.netblock;

import android.app.Application;

import com.linye.netblock.core.NetBlocker;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 初始化状态机（内含 Root 残留规则的安全清理）
        NetBlocker.get().init(this);
    }
}
