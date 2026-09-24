package com.hotspot.billing.net;

import android.net.wifi.WifiConfiguration;
import android.os.IBinder;

import java.lang.reflect.Method;

/**
 * Started as root via {@code app_process}, not from the app process.
 *
 * <p>On Android 9 the Infinix Hot 8 will bring {@code ap0} up
 * ({@code WifiService: startSoftAp}) but a normal app is not allowed to call
 * that method ({@code NETWORK_STACK} / {@code TETHER_PRIVILEGED}). uid 0 is
 * granted every permission, and the device's own {@code IWifiManager} stub
 * has the right binder transaction codes — so this class calls the framework
 * the Settings app calls, instead of guessing {@code service call} numbers.
 *
 * <p>Keep this file free of Kotlin and AndroidX. {@code app_process} loads it
 * out of the APK with only the boot classpath beside it.
 */
public final class SoftApEntry {

    private SoftApEntry() {}

    public static void main(String[] args) {
        try {
            int rc = run(args);
            System.out.println("EXIT=" + rc);
            System.exit(rc);
        } catch (Throwable t) {
            System.out.println("ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static int run(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "state";
        Object mgr = wifiService();
        if (mgr == null) {
            System.out.println("ERROR: wifi service is not registered");
            return 1;
        }
        if ("state".equals(cmd)) {
            Object state = invokeNoArg(mgr, "getWifiApEnabledState");
            System.out.println("AP_STATE=" + state);
            return 0;
        }
        if ("stop".equals(cmd)) {
            Object stopped = invokeNoArg(mgr, "stopSoftAp");
            System.out.println("STOP=" + stopped);
            return 0;
        }
        if (!"start".equals(cmd)) {
            System.out.println("ERROR: usage: SoftApEntry {start [ssid pass]|stop|state}");
            return 1;
        }

        WifiConfiguration cfg = null;
        if (args.length >= 3 && args[1] != null && args[1].length() > 0) {
            cfg = configuration(args[1], args[2]);
            Object set = invokeSetConfig(mgr, cfg);
            System.out.println("SET_CONFIG=" + set);
        }
        Object started = invokeStart(mgr, cfg);
        System.out.println("START=" + started);
        // null means the method returned void, or an overload we could not
        // read. The caller confirms by watching for ap0. An explicit false
        // is a refusal.
        if (Boolean.FALSE.equals(started)) return 2;
        return 0;
    }

    @SuppressWarnings("deprecation")
    private static WifiConfiguration configuration(String ssid, String pass) {
        WifiConfiguration cfg = new WifiConfiguration();
        // SoftAp on Android 9 stores the SSID unquoted. A quoted SSID is
        // beaconed with the quote characters in it.
        cfg.SSID = ssid;
        cfg.preSharedKey = pass;
        cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK);
        cfg.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
        cfg.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
        cfg.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        cfg.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        cfg.hiddenSSID = false;
        // Hidden on API 28, gone from the SDK later. 0 = 2.4 GHz. The Hot 8's
        // saved hotspot was channel 165 (5 GHz only); many client phones
        // cannot see that.
        try {
            java.lang.reflect.Field band = WifiConfiguration.class.getField("apBand");
            band.setInt(cfg, 0);
            java.lang.reflect.Field channel = WifiConfiguration.class.getField("apChannel");
            channel.setInt(cfg, 6);
            System.out.println("AP_BAND=2.4 channel=6");
        } catch (Throwable t) {
            System.out.println("AP_BAND=default (" + t.getClass().getSimpleName() + ")");
        }
        return cfg;
    }

    private static Object wifiService() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "wifi");
        if (binder == null) return null;
        Class<?> stub = Class.forName("android.net.wifi.IWifiManager$Stub");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static Object invokeNoArg(Object mgr, String name) throws Exception {
        Method method = mgr.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(mgr);
    }

    private static Object invokeSetConfig(Object mgr, WifiConfiguration cfg) {
        try {
            for (Method method : mgr.getClass().getMethods()) {
                if (!method.getName().equals("setWifiApConfiguration")) continue;
                Class<?>[] params = method.getParameterTypes();
                method.setAccessible(true);
                if (params.length == 2) {
                    return method.invoke(mgr, cfg, "com.android.settings");
                }
                if (params.length == 1) {
                    return method.invoke(mgr, cfg);
                }
            }
            System.out.println("SET_CONFIG=no method");
            return null;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            System.out.println("SET_CONFIG=threw " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return null;
        }
    }

    private static Object invokeStart(Object mgr, WifiConfiguration cfg) throws Exception {
        Method oneArg = null;
        Method twoArg = null;
        for (Method method : mgr.getClass().getMethods()) {
            if (!method.getName().equals("startSoftAp")) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1) oneArg = method;
            if (params.length == 2 && params[1] == String.class) twoArg = method;
        }
        if (oneArg != null) {
            oneArg.setAccessible(true);
            System.out.println("CALL=startSoftAp(WifiConfiguration)");
            return oneArg.invoke(mgr, cfg);
        }
        if (twoArg != null) {
            twoArg.setAccessible(true);
            System.out.println("CALL=startSoftAp(WifiConfiguration, String)");
            return twoArg.invoke(mgr, cfg, "com.android.settings");
        }
        throw new NoSuchMethodException("IWifiManager.startSoftAp");
    }
}
