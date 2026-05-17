package ipl.sable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static logger holder for cross-cutting diagnostics in this compat layer.
 *
 * <p>Lives outside the {@code mixin} package on purpose: interface mixins can't carry
 * fields (Mixin's processor throws
 * {@code InvalidInterfaceMixinException: contains a non-shadow field}), so any logger
 * needed from inside an interface mixin must live in a plain holder class like this.
 */
public final class IplSableLog {

    /** Used by {@code VeilPacketContextLevelMixin} to confirm redirection is firing. */
    public static final Logger VEIL_CTX = LoggerFactory.getLogger("ipl-sable-veil-ctx");

    private IplSableLog() {}
}
