package sh.harold.fulcrum.api.message.util;

public class DefaultTagFormatter implements TagFormatter {
    @Override
    public String formatTag(MessageTag tag) {
        if ("tag".equals(tag.getKey())) {
            return switch (tag.getValue()) {
                case "staff" -> "<aqua>[STAFF]</aqua> ";
                case "error" -> "<dark_red>[ERROR]</dark_red> ";
                case "daemon" -> "<dark_blue>[DAEMON]</dark_blue> ";
                case "system" -> "<blue>[SYSTEM]</blue> ";
                case "debug" -> "<dark_gray>[DEBUG]</dark_gray> ";
                default -> "<" + tag.getKey() + ":" + tag.getValue() + "> ";
            };
        } else {
            return "<" + tag.getKey() + ":" + tag.getValue() + "> ";
        }
    }
}