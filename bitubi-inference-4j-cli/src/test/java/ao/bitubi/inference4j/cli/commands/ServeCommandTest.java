/*
 * Copyright 2026 Bitubi Solutions
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the License.
 */
package ao.bitubi.inference4j.cli.commands;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

public class ServeCommandTest {

    @Test
    public void parsesTheOptions() {
        ServeCommand c = new ServeCommand();
        new CommandLine(c).parseArgs("/nonexistent", "--port", "0", "--host", "0.0.0.0", "--allowed-host", "a.lan,b.lan");
        assertEquals(0, c.port);
        assertEquals("0.0.0.0", c.host);
        assertEquals(java.util.List.of("a.lan", "b.lan"), c.allowedHosts);
        assertNull(c.workingTypes);
    }

    @Test
    public void restapiIsAnAliasOfServe() {
        CommandLine cli = new CommandLine(new ao.bitubi.inference4j.cli.Bi4jCli());
        cli.addSubcommand("serve", new ServeCommand());
        assertSame(cli.getSubcommands().get("serve"), cli.getSubcommands().get("restapi"));
    }

    @Test
    public void manualWorkingTypesNeedBothFlags() {
        CommandLine cli = new CommandLine(new ServeCommand());
        assertThrows(CommandLine.ParameterException.class, () -> cli.parseArgs("m/n", "--working-dtype", "F32"));
        ServeCommand ok = new ServeCommand();
        new CommandLine(ok).parseArgs("m/n", "--working-dtype", "F32", "--working-qtype", "I8");
        assertNotNull(ok.workingTypes);
    }

    @Test
    public void loopbackDetection() {
        assertTrue(ServeCommand.isLoopback("127.0.0.1"));
        assertTrue(ServeCommand.isLoopback("localhost"));
        assertTrue(ServeCommand.isLoopback("::1"));
        assertFalse(ServeCommand.isLoopback("0.0.0.0"));
        assertFalse(ServeCommand.isLoopback("192.168.1.5"));
    }

    @Test
    public void theBannerNeverShowsAWildcardAddress() {
        assertEquals("localhost", ServeCommand.displayHost("0.0.0.0"));
        assertEquals("localhost", ServeCommand.displayHost("::"));
        assertEquals("127.0.0.1", ServeCommand.displayHost("127.0.0.1"));
        assertEquals("[::1]", ServeCommand.displayHost("::1"));
    }
}
