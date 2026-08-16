package io.github.rjaros87.lifecycle;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleCommandTest {

    @Test
    void helpExitsSuccessfully() {
        int exitCode = new CommandLine(new LifecycleCommand()).execute("--help");

        assertEquals(0, exitCode);
    }

    @Test
    void allThreeSubcommandsAreRegistered() {
        CommandLine cmd = new CommandLine(new LifecycleCommand());

        assertTrue(cmd.getSubcommands().containsKey("health"));
        assertTrue(cmd.getSubcommands().containsKey("shutdown"));
        assertTrue(cmd.getSubcommands().containsKey("pre-stop"));
    }

    @Test
    void runningWithoutASubcommandPrintsUsageAndSucceeds() {
        int exitCode = new CommandLine(new LifecycleCommand()).execute();

        assertEquals(0, exitCode);
    }
}
