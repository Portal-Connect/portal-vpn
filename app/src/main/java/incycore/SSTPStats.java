package incycore;

import go.Seq;

public final class SSTPStats implements Seq.Proxy {
    private final int refnum;

    static {
        Incycore.touch();
    }

    private static native int __New();

    public SSTPStats() {
        this.refnum = __New();
        Seq.trackGoRef(this.refnum, this);
    }

    public SSTPStats(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(this.refnum, this);
    }

    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native boolean getConnected();
    public final native long getRxBytes();
    public final native long getTxBytes();
    public final native void setConnected(boolean v);
    public final native void setRxBytes(long v);
    public final native void setTxBytes(long v);
}