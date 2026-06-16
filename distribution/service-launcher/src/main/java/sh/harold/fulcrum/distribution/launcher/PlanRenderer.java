package sh.harold.fulcrum.distribution.launcher;

final class PlanRenderer {
    private PlanRenderer() {
    }

    static String render(LaunchPlan plan) {
        StringBuilder builder = new StringBuilder();
        ProfileDescriptor profile = plan.profile();
        builder.append("Fulcrum launch plan").append(System.lineSeparator());
        builder.append("profile=").append(profile.profileId()).append(System.lineSeparator());
        builder.append("semanticModel=").append(profile.semanticModel()).append(System.lineSeparator());
        builder.append("contractSet=").append(profile.contractSet()).append(System.lineSeparator());
        builder.append("servicePlacement=").append(profile.servicePlacement()).append(System.lineSeparator());
        builder.append("storageShape=").append(profile.storageShape()).append(System.lineSeparator());
        builder.append("agonesMode=").append(profile.agonesMode()).append(System.lineSeparator());
        builder.append("objectStorage=").append(profile.objectStorage()).append(System.lineSeparator());
        builder.append("launchMode=").append(plan.mode().id()).append(System.lineSeparator());
        builder.append("entrypoints:").append(System.lineSeparator());

        for (LaunchEntry entry : plan.entries()) {
            builder.append("- ")
                    .append(entry.role().id())
                    .append(" [")
                    .append(entry.processFamily())
                    .append("] ")
                    .append(entry.command(profile))
                    .append(System.lineSeparator());
            builder.append("  mainClass=")
                    .append(entry.mainClass())
                    .append(System.lineSeparator());
            if (!entry.requiredBindings().isEmpty()) {
                builder.append("  requiredBindings=")
                        .append(String.join(", ", entry.requiredBindings()))
                        .append(System.lineSeparator());
            }
        }

        return builder.toString();
    }
}
