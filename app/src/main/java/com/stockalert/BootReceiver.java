package com.stockalert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.stockalert.core.Json;
import com.stockalert.core.Net;

import java.io.File;
import java.io.FileInputStream;
import java.util.Map;

/** 폰 재부팅 / 앱 업데이트 후 자동으로 감시 시작 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        File f = new File(context.getFilesDir(), "config.json");
        if (!f.exists()) return;
        try {
            Map<String, Object> cfg = Json.obj(Json.parse(Net.readAll(new FileInputStream(f))));
            boolean auto = Json.b(Json.obj(cfg.get("alerts")).get("autostart"));
            String key = Json.s(Json.obj(cfg.get("kiwoom")).get("appkey"));
            if (auto && !key.isEmpty()) AlertService.start(context);
        } catch (Exception ignored) {
        }
    }
}
