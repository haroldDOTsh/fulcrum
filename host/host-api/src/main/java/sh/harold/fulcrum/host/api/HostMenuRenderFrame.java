package sh.harold.fulcrum.host.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record HostMenuRenderFrame(
        String menuId,
        String title,
        List<HostMenuSlot> slots,
        List<String> messages,
        List<HostMenuReceipt> receipts,
        Optional<String> refusalReason) {
    public HostMenuRenderFrame {
        menuId = HostNames.requireNonBlank(menuId, "menuId");
        title = HostNames.requireNonBlank(title, "title");
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        messages = List.copyOf(Objects.requireNonNull(messages, "messages").stream()
                .map(value -> HostNames.requireNonBlank(value, "message"))
                .toList());
        receipts = List.copyOf(Objects.requireNonNull(receipts, "receipts"));
        refusalReason = refusalReason == null
                ? Optional.empty()
                : refusalReason.map(value -> HostNames.requireNonBlank(value, "refusalReason"));
    }
}
