package go;

import android.content.Context;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.logging.Logger;

public class Seq {
    private static Logger log = Logger.getLogger("GoSeq");

    private static final int NULL_REFNUM = 41;
    public static final Ref nullRef = new Ref(NULL_REFNUM, null);
    private static final GoRefQueue goRefQueue = new GoRefQueue();

    static {
        System.loadLibrary("gojni");
        init();
        Universe.touch();
    }

    public static void setContext(Context context) {
        setContext((Object) context);
    }

    public static native void init();

    public static void touch() {}

    private Seq() {}

    public static native void setContext(Object ctx);

    public static void incRefnum(int refnum) {
        tracker.incRefnum(refnum);
    }

    public static int incRef(Object o) {
        return tracker.inc(o);
    }

    public static int incGoObjectRef(GoObject o) {
        return o.incRefnum();
    }

    public static void trackGoRef(int refnum, GoObject obj) {
        if (refnum > 0) {
            throw new RuntimeException("trackGoRef called with Java refnum " + refnum);
        }
        goRefQueue.track(refnum, obj);
    }

    public static Ref getRef(int refnum) {
        return tracker.get(refnum);
    }

    public static native void incGoRef(int refnum, GoObject ref);
    static native void destroyRef(int refnum);

    static void decRef(int refnum) {
        tracker.dec(refnum);
    }

    public interface GoObject {
        int incRefnum();
    }

    public interface Proxy extends GoObject {}

    public static final class Ref {
        public final int refnum;
        private int refcnt;
        public final Object obj;

        Ref(int refnum, Object o) {
            if (refnum < 0) {
                throw new RuntimeException("Ref instantiated with a Go refnum " + refnum);
            }
            this.refnum = refnum;
            this.refcnt = 0;
            this.obj = o;
        }

        void inc() {
            if (refcnt == Integer.MAX_VALUE) {
                throw new RuntimeException("refnum " + refnum + " overflow");
            }
            refcnt++;
        }
    }

    static final RefTracker tracker = new RefTracker();

    static final class RefTracker {
        private static final int REF_OFFSET = 42;
        private int next = REF_OFFSET;
        private final RefMap javaObjs = new RefMap();
        private final IdentityHashMap<Object, Integer> javaRefs = new IdentityHashMap<>();

        synchronized int inc(Object o) {
            if (o == null) {
                return NULL_REFNUM;
            }
            if (o instanceof Proxy) {
                return ((Proxy) o).incRefnum();
            }
            Integer refnumObj = javaRefs.get(o);
            if (refnumObj == null) {
                if (next == Integer.MAX_VALUE) {
                    throw new RuntimeException("createRef overflow for " + o);
                }
                refnumObj = next++;
                javaRefs.put(o, refnumObj);
            }
            int refnum = refnumObj;
            Ref ref = javaObjs.get(refnum);
            if (ref == null) {
                ref = new Ref(refnum, o);
                javaObjs.put(refnum, ref);
            }
            ref.inc();
            return refnum;
        }

        synchronized void incRefnum(int refnum) {
            Ref ref = javaObjs.get(refnum);
            if (ref == null) {
                throw new RuntimeException("referenced Java object is not found: refnum=" + refnum);
            }
            ref.inc();
        }

        synchronized void dec(int refnum) {
            if (refnum <= 0) {
                log.severe("dec request for Go object " + refnum);
                return;
            }
            if (refnum == Seq.nullRef.refnum) {
                return;
            }
            Ref obj = javaObjs.get(refnum);
            if (obj == null) {
                throw new RuntimeException("referenced Java object is not found: refnum=" + refnum);
            }
            obj.refcnt--;
            if (obj.refcnt <= 0) {
                javaObjs.remove(refnum);
                javaRefs.remove(obj.obj);
            }
        }

        synchronized Ref get(int refnum) {
            if (refnum < 0) {
                throw new RuntimeException("ref called with Go refnum " + refnum);
            }
            if (refnum == NULL_REFNUM) {
                return nullRef;
            }
            Ref ref = javaObjs.get(refnum);
            if (ref == null) {
                throw new RuntimeException("unknown java Ref: " + refnum);
            }
            return ref;
        }
    }

    static class GoRefQueue extends ReferenceQueue<GoObject> {
        private final Collection<GoRef> refs = Collections.synchronizedCollection(new HashSet<GoRef>());

        void track(int refnum, GoObject obj) {
            refs.add(new GoRef(refnum, obj, this));
        }

        GoRefQueue() {
            Thread daemon = new Thread(new Runnable() {
                @Override public void run() {
                    while (true) {
                        try {
                            GoRef ref = (GoRef) remove();
                            refs.remove(ref);
                            destroyRef(ref.refnum);
                            ref.clear();
                        } catch (InterruptedException e) {
                        }
                    }
                }
            });
            daemon.setDaemon(true);
            daemon.setName("GoRefQueue Finalizer Thread");
            daemon.start();
        }
    }

    static class GoRef extends PhantomReference<GoObject> {
        final int refnum;

        GoRef(int refnum, GoObject obj, GoRefQueue q) {
            super(obj, q);
            if (refnum > 0) {
                throw new RuntimeException("GoRef instantiated with a Java refnum " + refnum);
            }
            this.refnum = refnum;
        }
    }

    static final class RefMap {
        private int next = 0;
        private int live = 0;
        private int[] keys = new int[16];
        private Ref[] objs = new Ref[16];

        RefMap() {}

        Ref get(int key) {
            int i = Arrays.binarySearch(keys, 0, next, key);
            if (i >= 0) {
                return objs[i];
            }
            return null;
        }

        void remove(int key) {
            int i = Arrays.binarySearch(keys, 0, next, key);
            if (i >= 0) {
                if (objs[i] != null) {
                    objs[i] = null;
                    live--;
                }
            }
        }

        void put(int key, Ref obj) {
            if (obj == null) {
                throw new RuntimeException("put a null ref (with key " + key + ")");
            }
            int i = Arrays.binarySearch(keys, 0, next, key);
            if (i >= 0) {
                if (objs[i] == null) {
                    objs[i] = obj;
                    live++;
                }
                if (objs[i] != obj) {
                    throw new RuntimeException("replacing an existing ref (with key " + key + ")");
                }
                return;
            }
            if (next >= keys.length) {
                grow();
                i = Arrays.binarySearch(keys, 0, next, key);
            }
            i = ~i;
            if (i < next) {
                System.arraycopy(keys, i, keys, i + 1, next - i);
                System.arraycopy(objs, i, objs, i + 1, next - i);
            }
            keys[i] = key;
            objs[i] = obj;
            live++;
            next++;
        }

        private void grow() {
            int[] newKeys;
            Ref[] newObjs;
            int len = 2 * roundPow2(live);
            if (len > keys.length) {
                newKeys = new int[keys.length * 2];
                newObjs = new Ref[objs.length * 2];
            } else {
                newKeys = keys;
                newObjs = objs;
            }

            int j = 0;
            for (int i = 0; i < keys.length; i++) {
                if (objs[i] != null) {
                    newKeys[j] = keys[i];
                    newObjs[j] = objs[i];
                    j++;
                }
            }
            for (int i = j; i < newKeys.length; i++) {
                newKeys[i] = 0;
                newObjs[i] = null;
            }

            keys = newKeys;
            objs = newObjs;
            next = j;

            if (live != next) {
                throw new RuntimeException("bad state: live=" + live + ", next=" + next);
            }
        }

        private static int roundPow2(int x) {
            int p = 1;
            while (p < x) {
                p *= 2;
            }
            return p;
        }
    }
}
