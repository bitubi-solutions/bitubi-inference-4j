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

import ao.bitubi.inference4j.safetensors.DType;
import ao.bitubi.inference4j.sdk.Bi4j;
import ao.bitubi.inference4j.sdk.LoadProgress;
import ao.bitubi.inference4j.server.ModelHost;
import ao.bitubi.inference4j.server.ServerApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import picocli.CommandLine;

@CommandLine.Command(name = "serve", aliases = {
    "restapi" }, description = "Serves the model over an OpenAI compatible API (HTTP starts first, the model loads in the background)", abbreviateSynopsis = true)
public class ServeCommand extends SimpleBaseCommand {
    private static final Pattern OWNER_NAME = Pattern.compile("[\\w.-]+/[\\w.-]+");

    @CommandLine.Option(names = { "--host" }, paramLabel = "ARG", description = "Address to bind (default: ${DEFAULT-VALUE}). Anything but loopback has no authentication", defaultValue = "127.0.0.1")
    String host = "127.0.0.1";

    @CommandLine.Option(names = { "--port" }, paramLabel = "ARG", description = "http port, 0 picks a free one (default: ${DEFAULT-VALUE})", defaultValue = "8080")
    int port = 8080;

    @CommandLine.Option(names = { "--allowed-host" }, split = ",", paramLabel = "ARG", description = "Host header names accepted besides localhost")
    List<String> allowedHosts = List.of();

    @CommandLine.Option(names = { "--context-length" }, paramLabel = "ARG", description = "Most tokens a conversation may hold")
    Integer contextLength;

    @CommandLine.Option(names = { "--threads" }, paramLabel = "ARG", description = "Threads for the math (default: physical cores)")
    Integer threads;

    @CommandLine.ArgGroup(exclusive = false, heading = "Manual working types (at your own risk; default is automatic):%n")
    WorkingTypes workingTypes;

    static class WorkingTypes {
        @CommandLine.Option(names = { "--working-dtype" }, required = true, paramLabel = "ARG", description = "Working memory type")
        DType memory;

        @CommandLine.Option(names = { "--working-qtype" }, required = true, paramLabel = "ARG", description = "Working activation type")
        DType activations;
    }

    /** Serves the static chat page that ships with the CLI until the new UI replaces it. */
    @Configuration
    static class StaticUi implements WebMvcConfigurer {
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/ui/**").addResourceLocations("classpath:/static/ui/");
        }
    }

    /** The name to show in URLs: a wildcard bind address is not something a browser can open. */
    static String displayHost(String host) {
        if (host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]")) return "localhost";
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    static boolean isLoopback(String host) {
        return host.equals("localhost") || host.equals("::1") || host.equals("[::1]") || host.startsWith("127.");
    }

    /** A path that exists, an {@code owner/name} id, or (so the error names it) anything else as a path. */
    Bi4j load(Consumer<LoadProgress> progress) {
        Bi4j.Builder b = Bi4j.builder().modelsDir(modelDirectory.toPath()).onProgress(progress);
        Path asPath = Path.of(modelName);
        if (Files.exists(asPath) || !OWNER_NAME.matcher(modelName).matches()) b.model(asPath);
        else b.model(modelName);
        if (contextLength != null) b.contextLength(contextLength);
        if (threads != null) b.threads(threads);
        if (downloadSection.authToken != null) b.authToken(downloadSection.authToken);
        if (workingTypes != null) b.workingTypes(workingTypes.memory, workingTypes.activations);
        return b.build();
    }

    @Override
    public void run() {
        try {
            if (!isLoopback(host)) {
                System.err.println(
                    "WARNING: no authentication: anyone who can reach this port can use the model and read stored conversations. "
                        + "Requests addressed to another name are refused; allow it with --allowed-host."
                );
            }
            ModelHost modelHost = new ModelHost(this::load);
            new SpringApplicationBuilder(ServerApplication.class, StaticUi.class).initializers(ServerApplication.withModelHost(modelHost))
                .properties(
                    "server.address=" + host,
                    "server.port=" + port,
                    "bi4j.server.allowed-hosts=" + String.join(",", allowedHosts),
                    "logging.level.org.springframework.web=info"
                )
                .listeners((ApplicationReadyEvent e) -> {
                    String base = "http://" + displayHost(host) + ":" + e.getApplicationContext().getEnvironment().getProperty("local.server.port");
                    System.out.println("OpenAI API: " + base + "/v1");
                    System.out.println("API docs:   " + base + "/docs");
                    System.out.println("Model:      " + modelName + " (loading; " + base + "/healthz tells when it is ready)");
                    modelHost.start();
                })
                .run();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }
}
