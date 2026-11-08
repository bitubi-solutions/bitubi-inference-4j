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
package ao.bitubi.inference4j.sdk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.UnknownHostException;
import org.junit.Test;

public class ModelNotFoundMessageTest {

    @Test
    public void aNetworkOutageDoesNotBlameTheToken() {
        String m = DefaultBi4j.modelNotFoundMessage("a/b", new UnknownHostException("huggingface.co"), false);
        assertFalse(m, m.contains("authToken"));
        assertTrue(m, m.contains("network"));
    }

    @Test
    public void anotherFailureWithoutATokenHintsAtTheToken() {
        String m = DefaultBi4j.modelNotFoundMessage("a/b", new IOException("HTTP 401"), false);
        assertTrue(m, m.contains("authToken"));
        assertFalse(DefaultBi4j.modelNotFoundMessage("a/b", new IOException("HTTP 401"), true).contains("authToken"));
    }
}
