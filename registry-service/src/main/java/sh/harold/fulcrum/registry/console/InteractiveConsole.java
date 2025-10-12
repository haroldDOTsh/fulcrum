package sh.harold.fulcrum.registry.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interactive console for the registry service
 */
public class InteractiveConsole implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(InteractiveConsole.class);

    private final CommandRegistry commandRegistry;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread consoleThread;

    public InteractiveConsole(CommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
    }

    /**
     * Start the interactive console
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            consoleThread = new Thread(this, "Registry-Console");
            consoleThread.setDaemon(false);
            consoleThread.start();
            LOGGER.info("Interactive console started");
        }
    }

    /**
     * Stop the interactive console
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (consoleThread != null) {
                consoleThread.interrupt();
            }
            LOGGER.info("Interactive console stopped");
        }
    }

    @Override
    public void run() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("Registry Service Interactive Console");
        System.out.println("Type 'help' for available commands");
        System.out.println();

        while (running.get()) {
            try {
                System.out.print("registry> ");
                System.out.flush();

                String input = reader.readLine();
                if (input == null) {
                    // EOF reached (Ctrl+D on Unix, Ctrl+Z on Windows)
                    break;
                }

                input = input.trim();
                if (input.isEmpty()) {
                    continue;
                }

                // Execute command
                commandRegistry.executeCommand(input);
                System.out.println();

            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.error("Error reading console input", e);
                }
            } catch (Exception e) {
                LOGGER.error("Unexpected error in console", e);
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}