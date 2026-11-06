package com.music.bypass.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object RealmeLinkHook {

    private const val TAG = "[BypassEngine:RealmeLink]"
    private var appContext: Context? = null

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG Hooking process: ${lpparam.processName}")

        // 1. Hook Application.onCreate to capture Context & register dynamic receiver
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        appContext = app
                        registerAncReceiver(app)
                        // Also try attaching hooks with app classloader in case some classes were deferred
                        attachHeadsetHooks(app.classLoader)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error hooking Application.onCreate: ${t.message}")
        }

        // Attach headset hooks immediately with default classloader
        attachHeadsetHooks(lpparam.classLoader)

        // 2. Bypass permission check in ShortcutsProvider so any caller can toggle ANC
        try {
            val providerCls = XposedHelpers.findClassIfExists(
                "com.realme.iot.mydevice.sdk.ShortcutsProvider",
                lpparam.classLoader
            )
            if (providerCls != null) {
                XposedHelpers.findAndHookMethod(
                    providerCls,
                    "call",
                    String::class.java,
                    String::class.java,
                    Bundle::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val method = param.args[0] as? String
                            val extras = param.args[2] as? Bundle
                            if (method == "shortcut_click" && extras != null) {
                                XposedBridge.log("$TAG ShortcutsProvider.call intercepted: shortcut_click -> executing directly")
                                try {
                                    XposedHelpers.callMethod(
                                        param.thisObject,
                                        "a",
                                        method,
                                        param.args[1],
                                        extras
                                    )
                                    param.result = Bundle()
                                } catch (e: Throwable) {
                                    XposedBridge.log("$TAG Direct execution in ShortcutsProvider.a failed: ${e.message}")
                                }
                            }
                        }
                    }
                )
                XposedBridge.log("$TAG Hooked ShortcutsProvider.call successfully")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error hooking ShortcutsProvider: ${t.message}")
        }
    }

    private var headsetHooksAttached = false

    private fun attachHeadsetHooks(classLoader: ClassLoader?) {
        if (classLoader == null || headsetHooksAttached) return

        var attachedAny = false

        // A. Hook SetCommandManager.a(String, int, int, int, ICommandListener)
        try {
            val scmCls = XposedHelpers.findClassIfExists(
                "com.realme.iot.headset.tl.protocol.commands.SetCommandManager",
                classLoader
            )
            val listenerCls = XposedHelpers.findClassIfExists(
                "com.realme.iot.headset.tl.protocol.ICommandListener",
                classLoader
            )
            if (scmCls != null && listenerCls != null) {
                XposedHelpers.findAndHookMethod(
                    scmCls,
                    "a",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    listenerCls,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val mac = param.args[0] as? String
                                val bitVal = (param.args[3] as? Int) ?: return
                                val mode = mapBitValToMode(bitVal)
                                if (mode != null) {
                                    XposedBridge.log("$TAG SetCommandManager.a: bitVal=0x${Integer.toHexString(bitVal)} -> mode=$mode, mac=$mac")
                                    notifyCompanionApp(mode, mac)
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG Error in SetCommandManager.a hook: ${e.message}")
                            }
                        }
                    }
                )
                XposedBridge.log("$TAG Hooked SetCommandManager.a successfully")
                attachedAny = true
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error hooking SetCommandManager.a: ${t.message}")
        }

        // B. Hook PacketFactory.c(String, int, byte[])
        try {
            val pfCls = XposedHelpers.findClassIfExists(
                "com.realme.iot.headset.tl.protocol.packet.PacketFactory",
                classLoader
            )
            if (pfCls != null) {
                XposedHelpers.findAndHookMethod(
                    pfCls,
                    "c",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    ByteArray::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val mac = param.args[0] as? String
                                val cmd = param.args[1] as? Int ?: return
                                val payload = param.args[2] as? ByteArray ?: return
                                // 0x0404 is CMD_SET_ANC (1028)
                                if (cmd == 1028 && payload.size >= 3) {
                                    val bitVal = payload[2].toInt() and 0xFF
                                    val mode = mapBitValToMode(bitVal)
                                    if (mode != null) {
                                        XposedBridge.log("$TAG PacketFactory.c: cmd=0x0404, bitVal=0x${Integer.toHexString(bitVal)} -> mode=$mode, mac=$mac")
                                        notifyCompanionApp(mode, mac)
                                    }
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG Error in PacketFactory.c hook: ${e.message}")
                            }
                        }
                    }
                )

                // C. Hook PacketFactory.g(byte[])
                XposedHelpers.findAndHookMethod(
                    pfCls,
                    "g",
                    ByteArray::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val packet = param.result ?: return
                                val cmd = try {
                                    XposedHelpers.callMethod(packet, "d") as? Int ?: -1
                                } catch (_: Throwable) { -1 }
                                val payload = try {
                                    XposedHelpers.callMethod(packet, "e") as? ByteArray
                                } catch (_: Throwable) { null }

                                if (payload != null && payload.isNotEmpty()) {
                                    if (cmd == 1028 && payload.size >= 3) {
                                        val bitVal = payload[payload.size - 1].toInt() and 0xFF
                                        val mode = mapBitValToMode(bitVal)
                                        if (mode != null) {
                                            XposedBridge.log("$TAG PacketFactory.g (0x0404 reply): bitVal=0x${Integer.toHexString(bitVal)} -> mode=$mode")
                                            notifyCompanionApp(mode, null)
                                        }
                                    } else if (cmd == 268 && payload.size >= 4) {
                                        // 0x010C: only echo 01 01 is active mode; echo 02 01 is cycle mask (0x0B)
                                        val echo1 = payload[1].toInt() and 0xFF
                                        val echo2 = payload[2].toInt() and 0xFF
                                        if (echo1 == 1 && echo2 == 1) {
                                            val bitVal = payload[3].toInt() and 0xFF
                                            val mode = mapBitValToMode(bitVal)
                                            if (mode != null) {
                                                XposedBridge.log("$TAG PacketFactory.g (0x010C query reply): bitVal=0x${Integer.toHexString(bitVal)} -> mode=$mode")
                                                notifyCompanionApp(mode, null)
                                            }
                                        }
                                    } else if (cmd == 516 && payload.size >= 4 &&
                                        (payload[0].toInt() and 0xFF) == 3 &&
                                        (payload[1].toInt() and 0xFF) == 1 &&
                                        (payload[2].toInt() and 0xFF) == 1) {
                                        val bitVal = payload[3].toInt() and 0xFF
                                        val mode = mapBitValToMode(bitVal)
                                        if (mode != null) {
                                            XposedBridge.log("$TAG PacketFactory.g (0x0204 report): bitVal=0x${Integer.toHexString(bitVal)} -> mode=$mode")
                                            notifyCompanionApp(mode, null)
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG Error in PacketFactory.g hook: ${e.message}")
                            }
                        }
                    }
                )
                XposedBridge.log("$TAG Hooked PacketFactory.c and .g successfully")
                attachedAny = true
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error hooking PacketFactory: ${t.message}")
        }

        // D. Hook TLHeadsetConnectionImp.w5(Device, NoiseReductionInfo)
        try {
            val tlConnCls = XposedHelpers.findClassIfExists(
                "com.realme.iot.headset.tl.TLHeadsetConnectionImp",
                classLoader
            )
            val devCls = XposedHelpers.findClassIfExists(
                "com.realme.iot.common.devices.Device",
                classLoader
            )
            val nriCls = XposedHelpers.findClassIfExists(
                "com.realme.iot.headset.tl.protocol.commands.NoiseReductionInfo",
                classLoader
            )
            if (tlConnCls != null && devCls != null && nriCls != null) {
                XposedHelpers.findAndHookMethod(
                    tlConnCls,
                    "w5",
                    devCls,
                    nriCls,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val dev = param.args[0]
                                val info = param.args[1] ?: return
                                val mac = try {
                                    XposedHelpers.callMethod(dev, "getMac") as? String
                                        ?: XposedHelpers.getObjectField(dev, "mac") as? String
                                } catch (_: Throwable) { null }
                                val bitVal = try {
                                    XposedHelpers.callMethod(info, "c") as? Int
                                        ?: XposedHelpers.getIntField(info, "c")
                                } catch (_: Throwable) { -1 }

                                if (bitVal > 0) {
                                    val mode = mapBitValToMode(bitVal)
                                    if (mode != null) {
                                        XposedBridge.log("$TAG TLHeadsetConnectionImp.w5: bitVal=0x${Integer.toHexString(bitVal)} -> mode=$mode, mac=$mac")
                                        notifyCompanionApp(mode, mac)
                                    }
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG Error in w5 hook: ${e.message}")
                            }
                        }
                    }
                )
                XposedBridge.log("$TAG Hooked TLHeadsetConnectionImp.w5 successfully")
                attachedAny = true
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error hooking TLHeadsetConnectionImp.w5: ${t.message}")
        }

        if (attachedAny) {
            headsetHooksAttached = true
        }
    }

    private fun mapBitValToMode(bitVal: Int): String? {
        if (Integer.bitCount(bitVal) > 1) return null // Filter out cycle masks like 0x0B (11)
        return when {
            (bitVal and 0x01) != 0 -> "NORMAL"
            (bitVal and 0x02) != 0 -> "TRANSPARENCY"
            (bitVal and 0x3C) != 0 -> "ANC"
            else -> null
        }
    }

    private fun notifyCompanionApp(mode: String, mac: String?) {
        try {
            val ctx = appContext ?: run {
                try {
                    val atCls = Class.forName("android.app.ActivityThread")
                    val method = atCls.getMethod("currentApplication")
                    method.invoke(null) as? Context
                } catch (_: Throwable) { null }
            } ?: return

            val intent = Intent("com.music.bypass.ACTION_SET_ANC_MODE").apply {
                setPackage("com.music.bypass")
                putExtra("extra_mode", mode)
                putExtra("from_realme_link", true)
                if (mac != null) putExtra("mac", mac)
            }
            ctx.sendBroadcast(intent)
            XposedBridge.log("$TAG Successfully sent broadcast to Bypass Companion: mode=$mode, mac=$mac")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to send broadcast to Bypass Companion: ${t.message}")
        }
    }

    private fun registerAncReceiver(context: Context) {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != "com.music.bypass.SET_ANC_MODE") return

                    val mode = intent.getStringExtra("mode") ?: "ANC"
                    val shortcutId = intent.getStringExtra("shortcutId") ?: when (mode) {
                        "ANC" -> "NOISE_REDUCTION_OPEN"
                        "TRANSPARENCY" -> "NOISE_REDUCTION_TRANSPARENT"
                        else -> "NOISE_REDUCTION_NORMAL"
                    }
                    val mac = intent.getStringExtra("mac") ?: "B0:38:E2:DF:F4:8C"

                    XposedBridge.log("$TAG In-process trigger: mode=$mode, shortcutId=$shortcutId, mac=$mac")

                    try {
                        val uri = Uri.parse("content://com.realme.link.mydevice.shortcuts")
                        val extras = Bundle().apply {
                            putString("extra", """{"deviceId":"","macAddress":"$mac","shortcutId":"$shortcutId"}""")
                        }
                        ctx.contentResolver.call(uri, "shortcut_click", null, extras)
                        XposedBridge.log("$TAG Successfully dispatched internal shortcut_click")
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG Failed internal shortcut_click: ${e.message}")
                    }
                }
            }

            val filter = IntentFilter("com.music.bypass.SET_ANC_MODE")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            XposedBridge.log("$TAG Registered dynamic ANC BroadcastReceiver in ${context.packageName}")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to register ANC receiver: ${t.message}")
        }
    }
}
