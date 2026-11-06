package com.music.bypass.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class BypassXposedEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.music.bitchord") {
            try {
                BitChordHook.init(lpparam)
            } catch (t: Throwable) {
                de.robv.android.xposed.XposedBridge.log("[BypassEngine] Hook initialization error: ${t.message}")
            }
        } else if (lpparam.packageName == "com.realme.link") {
            try {
                RealmeLinkHook.init(lpparam)
            } catch (t: Throwable) {
                de.robv.android.xposed.XposedBridge.log("[BypassEngine] RealmeLinkHook error: ${t.message}")
            }
        }
    }
}

