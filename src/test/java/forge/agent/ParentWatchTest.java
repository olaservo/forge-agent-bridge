package forge.agent;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * {@code --exit-with-parent} across a real process tree. The test starts a JVM that starts a second
 * one, kills the first the hard way (TerminateProcess on Windows, SIGKILL elsewhere) and looks at the
 * second. Both run {@link #main}, which loads no part of Forge.
 */
public class ParentWatchTest {

    private Process parent;
    private ProcessHandle child;

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (parent != null) {
            parent.destroyForcibly();
        }
        if (child != null) {
            child.destroyForcibly();
        }
    }

    @Test(timeOut = 60_000)
    public void aWatchingProcessExitsWhenItsParentIsKilled() throws Exception {
        startTree("watch");
        parent.destroyForcibly().waitFor();
        child.onExit().get(20, TimeUnit.SECONDS);
        assertFalse(child.isAlive(), "the child ended with its parent");
    }

    @Test(timeOut = 60_000)
    public void withoutTheFlagTheProcessOutlivesItsParent() throws Exception {
        startTree("plain");
        parent.destroyForcibly().waitFor();
        try {
            child.onExit().get(3, TimeUnit.SECONDS);
        } catch (final TimeoutException expected) {
            // still running
        }
        assertTrue(child.isAlive(), "the child is still running 3 s after its parent was killed");
    }

    /** Starts the parent, which starts the child and prints its pid once the child is up. */
    private void startTree(final String mode) throws Exception {
        parent = java("parent", mode).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        final BufferedReader out = new BufferedReader(new InputStreamReader(parent.getInputStream(), StandardCharsets.UTF_8));
        final String pid = out.readLine();
        assertTrue(pid != null, "the parent printed its child's pid");
        child = ProcessHandle.of(Long.parseLong(pid)).orElseThrow();
        assertTrue(child.isAlive(), "the child is running before its parent is killed");
    }

    private static ProcessBuilder java(final String role, final String mode) {
        final String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), ParentWatchTest.class.getName(), role, mode);
    }

    public static void main(final String[] args) throws Exception {
        if ("parent".equals(args[0])) {
            final Process p = java("child", args[1]).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            // The child's first line says it is up, and watching if it was asked to.
            new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)).readLine();
            System.out.println(p.pid());
            System.out.flush();
        } else {
            if ("watch".equals(args[1])) {
                ParentWatch.exitWithParent("[test]");
            }
            System.out.println("up");
            System.out.flush();
        }
        Thread.sleep(600_000);
    }
}
