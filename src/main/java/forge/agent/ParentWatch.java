package forge.agent;

import java.util.Optional;

/** The {@code --exit-with-parent} flag of {@link AgentMain} and {@link MatchHost}. */
final class ParentWatch {

    static final String FLAG = "--exit-with-parent";

    private ParentWatch() {
    }

    /**
     * Exits this process when the process that started it ends, however it ends. With no parent to watch,
     * it has ended already. {@code tag} starts the line written to stderr.
     */
    static void exitWithParent(final String tag) {
        final Optional<ProcessHandle> parent = ProcessHandle.current().parent();
        if (parent.isEmpty()) {
            System.err.println(tag + " the parent process is gone, exiting");
            System.exit(0);
        }
        parent.get().onExit().thenRun(() -> {
            System.err.println(tag + " the parent process ended, exiting");
            System.exit(0);
        });
    }
}
