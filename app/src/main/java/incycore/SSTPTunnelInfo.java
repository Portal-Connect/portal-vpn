package incycore;

import go.Seq;

public final class SSTPTunnelInfo implements Seq.Proxy {
    private final int refnum;

    static {
        Incycore.touch();
    }

    private static native int __New();

    public SSTPTunnelInfo() {
        this.refnum = __New();
        Seq.trackGoRef(this.refnum, this);
    }

    public SSTPTunnelInfo(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(this.refnum, this);
    }

    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native String getAddress();
    public final native long getMTU();
    public final native long getPrefixLength();
    public final native void setAddress(String v);
    public final native void setMTU(long v);
    public final native void setPrefixLength(long v);
}