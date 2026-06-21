package sh.harold.fulcrum.distribution.launcher;

import java.io.PrintStream;
import java.util.Arrays;

public final class FulcrumLauncher {
    static final int OK = 0;
    static final int RUNTIME_FAILURE = 70;
    static final int USAGE_ERROR = 64;
    static final int CONFIGURATION_BLOCKED = 78;

    private final RuntimeEnvironment environment;

    public FulcrumLauncher() {
        this(RuntimeEnvironment.system());
    }

    FulcrumLauncher(RuntimeEnvironment environment) {
        this.environment = environment;
    }

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

            if (command.mode() == LaunchMode.RUN && !plan.canStart(environment)) {
                err.println("Cannot start Fulcrum runtime: required bindings are not configured.");
                plan.missingBindings(environment).forEach(binding -> err.println("- " + binding));
                return CONFIGURATION_BLOCKED;
            }

            if (command.mode() == LaunchMode.RUN) {
                try (FulcrumRuntimeSupervisor supervisor = new FulcrumRuntimeSupervisor(
                        plan,
                        environment,
                        command.probeHost(),
                        command.probePort())) {
                    supervisor.start();
                    out.println("Fulcrum runtime started");
                    out.println("probe=http://" + supervisor.displayHost() + ":" + supervisor.probePort());
                    supervisor.await(command.runFor());
                    out.println("Fulcrum runtime stopped");
                }
            }
            return OK;
        } catch (RuntimeConfigurationException exception) {
            err.println(exception.getMessage());
            return CONFIGURATION_BLOCKED;
        } catch (IllegalArgumentException exception) {
            err.println(exception.getMessage());
            err.print(usage());
            return USAGE_ERROR;
        } catch (RuntimeException exception) {
            err.println(exception.getMessage());
            return RUNTIME_FAILURE;
        }
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: fulcrum [--profile=<profile>] [--tier=<tier>] [--role=<role>] [--mode=<plan|run>]",
                "               [--probe-host=<host>] [--probe-port=<port>] [--run-for=<duration>]",
                "",
                "Profiles: single-machine, small-production, large-production",
                "Tiers: in-memory, slim, full-engine (single-machine only; default in-memory)",
                "Roles: authority-service, controller-service, worker-agent, paper-agent, velocity-agent, all",
                "Default: --profile=single-machine --role=all --mode=plan",
                "");
    }
}
