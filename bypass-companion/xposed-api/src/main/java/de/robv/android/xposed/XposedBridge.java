package de.robv.android.xposed;

import java.lang.reflect.Member;

public final class XposedBridge {
    private XposedBridge() {}

    public static void log(String text) {
        System.out.println(text);
    }

    public static void log(Throwable t) {
        t.printStackTrace();
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return new XC_MethodHook.Unhook();
    }
}

