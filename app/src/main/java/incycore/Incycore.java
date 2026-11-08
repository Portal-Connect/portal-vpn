package incycore;

import android.util.Log;
import go.Seq;

public final class Incycore {
    private static final String TAG = "Incycore";

    static {
        Seq.touch();
        _init();
        Log.i(TAG, "Incycore _init completed successfully!");
    }

    private static native void _init();

    public static void touch() {}

    private Incycore() {}

    public static native void tun2SocksStart(int fd, String proxy);
    public static native void tun2SocksStop();
    public static native boolean tun2SocksIsRunning();

    public static native void startTun2Socks(long fd, String proxy);
    public static native void stopTun2Socks();
    public static native boolean isTun2SocksRunning();

    public static native void startXrayWithTunFd(String config, int tunFd);
    public static native void stopXray();
    public static native boolean isXrayRunning();
    public static native void setXrayAssetsPath(String path);

    static final class proxyDialerController implements Seq.Proxy, DialerController {
        private final int refnum;

        proxyDialerController(int refnum) {
            this.refnum = refnum;
            Seq.trackGoRef(this.refnum, this);
        }

        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        public native boolean protectFd(long fd);
    }

    static final class proxyPingCallback implements Seq.Proxy, PingCallback {
        private final int refnum;

        proxyPingCallback(int refnum) {
            this.refnum = refnum;
            Seq.trackGoRef(this.refnum, this);
        }

        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        public native void onPingComplete();
        public native void onPingResult(int idx, long delay, String err);
    }
}
