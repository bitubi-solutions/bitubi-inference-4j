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

/** A kind of input a model can read. Today every model reads {@link #TEXT} only. */
public enum Modality {
    /** Written text. */
    TEXT,
    /** A picture (PNG, JPEG, GIF or WebP). */
    IMAGE,
    /** Sound as mono PCM samples. */
    AUDIO
}
