package utils;

import gearth.extensions.ExtensionBase;

public class InterceptGuard {

    public static ExtensionBase.MessageListener guard(ExtensionBase.MessageListener inner) {
        return m -> {
            try {
                inner.act(m);
            } catch (Throwable t) {
                System.err.println("[RoomDuplicator] intercept guard swallowed " + t);
            }
        };
    }
}
