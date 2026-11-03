package com.lody.virtual.helper.compat;

/**
 * Selects the callback overload used to relay an IServiceConnection result.
 *
 * <p>Android 16 exposes the three-argument callback while Android 17 exposes the
 * four-argument callback with an IBinderSession. Reflection by method name alone is
 * ambiguous because the framework may also retain the deprecated two-argument
 * overload. Keeping this decision in a small pure Java policy makes the version
 * boundary testable and prevents the two-argument method from being selected by
 * method declaration order.</p>
 */
public final class ServiceConnectionDispatchPolicy {

    private ServiceConnectionDispatchPolicy() {
    }

    /**
     * Chooses the number of arguments for relaying the connection callback.
     *
     * <p>A callback that arrived with a session must use the four-argument method
     * whenever the running framework provides it. Otherwise the three-argument
     * method is the safe Android 8-16 path. The two-argument method is only used
     * as a final compatibility fallback.</p>
     *
     * @param receivedSessionArgument whether the current callback included a session
     * @param hasSessionOverload whether a four-argument overload exists
     * @param hasLegacyOverload whether a three-argument overload exists
     * @param hasMinimalOverload whether a two-argument overload exists
     * @return supported argument count, or zero when no relay is possible
     */
    public static int selectDeliveryArity(
            boolean receivedSessionArgument,
            boolean hasSessionOverload,
            boolean hasLegacyOverload,
            boolean hasMinimalOverload
    ) {
        if (receivedSessionArgument && hasSessionOverload) {
            return 4;
        }
        if (hasLegacyOverload) {
            return 3;
        }
        if (hasMinimalOverload) {
            return 2;
        }
        return hasSessionOverload ? 4 : 0;
    }
}
