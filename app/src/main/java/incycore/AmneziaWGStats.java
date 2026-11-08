package incycore;

import go.Seq;

public final class AmneziaWGStats implements Seq.Proxy {
    private final int refnum;

    static {
        Incycore.touch();
    }

    private static native int __New();

    public AmneziaWGStats() {
        this.refnum = __New();
        Seq.trackGoRef(this.refnum, this);
    }

    public AmneziaWGStats(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(this.refnum, this);
    }

    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native long getLastHandshake();
    public final native long getRxBytes();
    public final native long getTxBytes();
    public final native void setLastHandshake(long v);
    public final native void setRxBytes(long v);
    public final native void setTxBytes(long v);
}