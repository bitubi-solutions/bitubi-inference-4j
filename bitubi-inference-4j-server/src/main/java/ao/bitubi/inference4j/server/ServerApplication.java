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
package ao.bitubi.inference4j.server;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** The Boot application the CLI starts. The {@code ModelHost} bean is registered by the caller. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ServerApplication {
    /** Registers the caller's {@link ModelHost} as the context's bean and closes it with the context. */
    public static ApplicationContextInitializer<ConfigurableApplicationContext> withModelHost(ModelHost host) {
        return ctx -> {
            ctx.getBeanFactory().registerSingleton("modelHost", host);
            ((DefaultListableBeanFactory) ctx.getBeanFactory()).registerDisposableBean("modelHost", host::close);
        };
    }
}
