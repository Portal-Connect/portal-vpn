package incycore;

import go.Seq;

public interface DialerController extends Seq.GoObject {
    boolean protectFd(long fd);
}