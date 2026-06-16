package sh.harold.fulcrum.distribution.launcher;

import java.io.PrintStream;
import java.util.Arrays;

public final class FulcrumLauncher {
    static final int OK = 0;
    static final int USAGE_ERROR = 64;
    static final int CONFIGURATION_BLOCKED = 78;

    public static void main(String[] args) {
        int code = new FulcrumLauncher().run(args, System.out, System.err);
        if (code != OK) {
            System.exit(code);
        }
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        if (Arrays.stream(args).anyMatch(arg -> arg.equals("--help") || arg.equals("-h"))) {
            out.print(usage());
            return OK;
        }

        try {
            LaunchCommand command = LaunchCommand.parse(args);
            LaunchPlan plan = RuntimeEntrypointRegistry.plan(
                    command,
                    Thread.currentThread().getContextClassLoader()
            );
            out.print(PlanRenderer.render(plan));

            if (command.mode() == LaunchMode.RUN && !plan.canStart()) {
                err.println("Cannot start Fulcrum runtime: required bindings are not configured.");
                plan.missingBindings().forEach(binding -> err.println("- " + binding));
                return CONFIGURATION_BLOCKED;
            }
            return OK;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            err.println(exception.getMessage());
            err.print(usage());
            return USAGE_ERROR;
        }
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: fulcrum [--profile=<profile>] [--role=<role>] [--mode=<plan|run>]",
                "",
                "Profiles: single-machine, small-production, large-production",
                "Roles: authority-service, controller-service, worker-agent, paper-agent, velocity-agent, all",
                "Default: --profile=single-machine --role=all --mode=plan",
                "");
    }
}
