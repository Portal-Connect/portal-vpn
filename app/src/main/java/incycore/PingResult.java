package incycore;

import go.Seq;

public final class PingResult implements Seq.Proxy {
    private final int refnum;

    static {
        Incycore.touch();
    }

    private static native int __New();

    public PingResult() {
        this.refnum = __New();
        Seq.trackGoRef(this.refnum, this);
    }

    public PingResult(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(this.refnum, this);
    }

    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native String getAddress();
    public final native long getDelay();
    public final native String getError();
    public final native int getIndex();
    public final native void setAddress(String v);
    public final native void setDelay(long v);
    public final native void setError(String v);
    public final native void setIndex(int v);
}