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
package ao.bitubi.inference4j.gguf;

import ao.bitubi.inference4j.math.ActivationFunction;
import ao.bitubi.inference4j.model.qwen2.Qwen2Config;
import java.util.Map;

public final class GgufConfigFactory {
    private GgufConfigFactory() {}
    public static Qwen2Config create(Map<String, Object> m) {
        if (!"qwen2".equals(m.get("general.architecture"))) throw new IllegalArgumentException("Unsupported GGUF architecture: " + m.get("general.architecture"));
        return new Qwen2Config(i(m,"qwen2.context_length"), i(m,"qwen2.embedding_length"), i(m,"qwen2.feed_forward_length"),
            i(m,"qwen2.attention.head_count"), i(m,"qwen2.attention.head_count_kv"), i(m,"qwen2.block_count"),
            f(m,"qwen2.attention.layer_norm_rms_epsilon"), ((java.util.List<?>)m.get("tokenizer.ggml.tokens")).size(),
            i(m,"tokenizer.ggml.bos_token_id"), i(m,"tokenizer.ggml.eos_token_id"), ActivationFunction.Type.SILU,
            ((Number)m.getOrDefault("qwen2.rope.freq_base", 1000000d)).doubleValue());
    }
    private static int i(Map<String,Object> m,String k) { Object v=m.get(k); if (!(v instanceof Number)) throw new IllegalArgumentException("Missing/invalid GGUF metadata: "+k); return ((Number)v).intValue(); }
    private static float f(Map<String,Object> m,String k) { Object v=m.get(k); if (!(v instanceof Number)) throw new IllegalArgumentException("Missing/invalid GGUF metadata: "+k); return ((Number)v).floatValue(); }
}
