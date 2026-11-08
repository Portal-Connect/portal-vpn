package incycore;

import go.Seq;

public final class GeoCode implements Seq.Proxy {
    private final int refnum;

    static {
        Incycore.touch();
    }

    private static native int __New();

    public GeoCode() {
        this.refnum = __New();
        Seq.trackGoRef(this.refnum, this);
    }

    public GeoCode(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(this.refnum, this);
    }

    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native String getCode();
    public final native long getRuleCount();
    public final native void setCode(String v);
    public final native void setRuleCount(long v);
}