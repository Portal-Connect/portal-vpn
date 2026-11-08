package incycore;

import go.Seq;

public final class OpenConnectAuthField implements Seq.Proxy {
    private final int refnum;

    static {
        Incycore.touch();
    }

    private static native int __New();

    public OpenConnectAuthField() {
        this.refnum = __New();
        Seq.trackGoRef(this.refnum, this);
    }

    public OpenConnectAuthField(int refnum) {
        this.refnum = refnum;
        Seq.trackGoRef(this.refnum, this);
    }

    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native String getLabel();
    public final native String getName();
    public final native String getOptions();
    public final native boolean getSecret();
    public final native void setLabel(String v);
    public final native void setName(String v);
    public final native void setOptions(String v);
    public final native void setSecret(boolean v);
}