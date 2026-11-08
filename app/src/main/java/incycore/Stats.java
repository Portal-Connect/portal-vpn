package incycore;

import go.Seq;

public final class Stats implements Seq.Proxy {
    private final int refnum;

    static {
        Incycore.touch();
    }

    private static native int __New();

    public Stats() {
        this.refnum = __New();
        Seq.trackGoRef(this.refnum, this);
    }

    public Stats(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(this.refnum, this);
    }

    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native long getRxBytes();
    public final native long getRxPackets();
    public final native long getTxBytes();
    public final native long getTxPackets();
    public final native void setRxBytes(long v);
    public final native void setRxPackets(long v);
    public final native void setTxBytes(long v);
    public final native void setTxPackets(long v);
}