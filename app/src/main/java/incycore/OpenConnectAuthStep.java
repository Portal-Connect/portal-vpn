package incycore;

import go.Seq;

public final class OpenConnectAuthStep implements Seq.Proxy {
    private final int refnum;

    static {
        Incycore.touch();
    }

    private static native int __New();

    public OpenConnectAuthStep() {
        this.refnum = __New();
        Seq.trackGoRef(this.refnum, this);
    }

    public OpenConnectAuthStep(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(this.refnum, this);
    }

    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native OpenConnectAuthField field(long idx);
    public final native long getFieldCount();
    public final native String getMessage();
    public final native String getSessionID();
    public final native String getToken();
    public final native void setFieldCount(long v);
    public final native void setMessage(String v);
    public final native void setSessionID(String v);
    public final native void setToken(String v);
}