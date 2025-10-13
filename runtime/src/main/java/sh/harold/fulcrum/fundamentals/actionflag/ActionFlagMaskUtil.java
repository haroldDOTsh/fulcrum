package sh.harold.fulcrum.fundamentals.actionflag;

import java.util.EnumSet;
import java.util.Set;

final class ActionFlagMaskUtil {
    private ActionFlagMaskUtil() {
    }

    static Set<ActionFlag> flagsFromMask(long mask) {
        EnumSet<ActionFlag> flags = EnumSet.noneOf(ActionFlag.class);
        for (ActionFlag flag : ActionFlag.values()) {
            if ((mask & flag.mask()) != 0L) {
                flags.add(flag);
            }
        }
        return flags;
    }
}
