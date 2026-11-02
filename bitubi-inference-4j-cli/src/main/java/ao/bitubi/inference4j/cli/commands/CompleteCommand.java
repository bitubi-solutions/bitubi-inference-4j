/*
 * Copyright 2024 T Jake Luciani
 *
 * The Jlama Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * Modifications Copyright 2026 Bitubi Solutions
 */
package ao.bitubi.inference4j.cli.commands;

import static ao.bitubi.inference4j.model.ModelSupport.loadModel;

import ao.bitubi.inference4j.model.AbstractModel;
import ao.bitubi.inference4j.safetensors.prompt.PromptContext;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import picocli.CommandLine.*;

@Command(name = "complete", description = "Completes a prompt using the specified model", abbreviateSynopsis = true)
public class CompleteCommand extends ModelBaseCommand {

    @Option(names = { "--prompt" }, description = "Text to complete", required = true)
    protected String prompt;

    @Override
    public void run() {
        Path modelPath = SimpleBaseCommand.getModel(
            modelName,
            modelDirectory,
            downloadSection.autoDownload,
            downloadSection.branch,
            downloadSection.authToken
        );

        // One-shot: closing the model deletes the KV page files of this temporary session
        try (
            AbstractModel m = loadModel(
                modelPath.toFile(),
                workingDirectory,
                advancedSection.workingMemoryType,
                advancedSection.workingQuantizationType,
                Optional.ofNullable(advancedSection.modelQuantization),
                Optional.ofNullable(advancedSection.threadCount)
            )
        ) {
            m.generate(UUID.randomUUID(), PromptContext.of(prompt), temperature, tokens, makeOutHandler());
        }
    }
}
