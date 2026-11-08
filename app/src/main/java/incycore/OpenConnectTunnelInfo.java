package incycore;

import go.Seq;

public final class OpenConnectTunnelInfo implements Seq.Proxy {
    private final int refnum;

    static {
        Incycore.touch();
    }

    private static native int __New();

    public OpenConnectTunnelInfo() {
        this.refnum = __New();
        Seq.trackGoRef(this.refnum, this);
    }

    public OpenConnectTunnelInfo(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(this.refnum, this);
    }

    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native String getAddress();
    public final native String getAddress6();
    public final native String getDNS();
    public final native long getMTU();
    public final native String getNetmask();
    public final native String getSplitExclude();
    public final native String getSplitInclude();
    public final native void setAddress(String v);
    public final native void setAddress6(String v);
    public final native void setDNS(String v);
    public final native void setMTU(long v);
    public final native void setNetmask(String v);
    public final native void setSplitExclude(String v);
    public final native void setSplitInclude(String v);
}