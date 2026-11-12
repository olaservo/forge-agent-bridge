package forge.agent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import forge.GuiDesktop;
import forge.gamemodes.match.HostedMatch;

/**
 * GuiDesktop without Swing threading. Protocol calls that the desktop runs on the EDT run here on
 * one worker thread, in arrival order, so blocking prompts park that thread while the host waits
 * for the reply, the same way a modal dialog parks the desktop.
 */
final class AgentGuiBase extends GuiDesktop {

    private volatile Thread worker;
    private final ExecutorService edt = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Agent-EDT");
        t.setDaemon(true);
        worker = t;
        return t;
    });

    @Override
    public void invokeInEdtNow(final Runnable proc) {
        proc.run();
    }

    @Override
    public void invokeInEdtLater(final Runnable proc) {
        edt.execute(proc);
    }

    @Override
    public void invokeInEdtAndWait(final Runnable proc) {
        if (isGuiThread()) {
            proc.run();
            return;
        }
        final Future<?> f = edt.submit(proc);
        try {
            f.get();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isGuiThread() {
        return Thread.currentThread() == worker;
    }

    /** The desktop registers the match with its Swing singletons; there are none here. Used by the in-process tests that host. */
    @Override
    public HostedMatch hostMatch() {
        return new HostedMatch();
    }
}
