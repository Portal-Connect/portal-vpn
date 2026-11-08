package incycore;

import go.Seq;

public interface PingCallback extends Seq.GoObject {
    void onPingComplete();
    void onPingResult(int idx, long delay, String err);
}