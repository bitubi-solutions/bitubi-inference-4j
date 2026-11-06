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

/**
 * bitubi-inference-4j: LLM inference engine for Java.
 *
 * <p>The API is the package {@code ao.bitubi.inference4j.sdk}. Every other package is the engine: it is not exported,
 * so code on the module path cannot use it, and it may change without notice. (On the class path Java cannot hide
 * packages; there, treat everything outside {@code sdk} as internal.)
 */
module ao.bitubi.inference4j {
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.google.common;
    requires jctools.core;
    requires jinjava;
    requires net.fellbaum.jemoji;
    requires net.jafama;
    requires org.slf4j;
    requires java.desktop; // javax.sound: Part.audio reads WAV files
    requires java.management;
    requires jdk.incubator.vector;
    requires jdk.jfr;
    requires jdk.management;
    requires jdk.unsupported; // sun.misc.Unsafe for direct buffers

    exports ao.bitubi.inference4j.sdk;

    // Reflection only: Jackson reads model files into the config classes (and walks every type they reach), and the
    // template engine reads the prompt classes. Not an API: the packages stay unexported, so no other code can
    // compile against them and ordinary reflection from outside is refused.
    opens ao.bitubi.inference4j.gguf to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.math to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.model to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.model.bert to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.model.functions to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.model.gemma to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.model.gemma2 to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.model.gpt2 to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.model.granite to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.model.llama to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.model.mistral to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.model.mixtral to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.model.qwen2 to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.safetensors to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.safetensors.prompt to com.fasterxml.jackson.databind, jinjava;
    opens ao.bitubi.inference4j.safetensors.tokenizer to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.tensor to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.tensor.operations to com.fasterxml.jackson.databind;
    opens ao.bitubi.inference4j.util to com.fasterxml.jackson.databind;
}
