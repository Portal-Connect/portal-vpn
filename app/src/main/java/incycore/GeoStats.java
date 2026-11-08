package incycore;

import go.Seq;

public final class GeoStats implements Seq.Proxy {
    private final int refnum;

    static {
        Incycore.touch();
    }

    private static native int __New();

    public GeoStats() {
        this.refnum = __New();
        Seq.trackGoRef(this.refnum, this);
    }

    public GeoStats(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(this.refnum, this);
    }

    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native long getCategoryCount();
    public final native long getRuleCount();
    public final native void setCategoryCount(long v);
    public final native void setRuleCount(long v);
}