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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * One piece of a message: text, an image or audio. A message holds one or more parts, and a model declares which
 * {@link Modality modalities} it accepts. Parts are immutable values; the array-backed ones copy on the way in and out.
 */
public sealed interface Part permits Part.Text, Part.Image, Part.Audio {

    Modality modality();

    static Text text(String text) {
        return new Text(text);
    }

    /** Reads a PNG, JPEG, GIF or WebP file; the type comes from the file's first bytes, not its name. */
    static Image image(Path file) {
        try {
            return Image.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads a WAV (or other format the JDK decodes) and mixes it down to mono samples in [-1, 1]. */
    static Audio audio(Path file) {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            return Audio.of(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (UnsupportedAudioFileException e) {
            throw new IllegalArgumentException("Unsupported audio file " + file + ": convert it to WAV first", e);
        }
    }

    record Text(String text) implements Part {
        public Text {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public Modality modality() {
            return Modality.TEXT;
        }
    }

    /** Encoded image bytes and their media type, for example {@code image/png}. */
    record Image(byte[] data, String mimeType) implements Part {
        public Image {
            Objects.requireNonNull(mimeType, "mimeType");
            if (data == null || data.length == 0) throw new IllegalArgumentException("image data is empty");
            data = data.clone();
        }

        /** Detects the media type from the first bytes. */
        public static Image of(byte[] data) {
            return new Image(data, sniff(data));
        }

        static String sniff(byte[] d) {
            if (startsWith(d, 0, 0x89, 'P', 'N', 'G')) return "image/png";
            if (startsWith(d, 0, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
            if (startsWith(d, 0, 'G', 'I', 'F', '8')) return "image/gif";
            if (startsWith(d, 0, 'R', 'I', 'F', 'F') && startsWith(d, 8, 'W', 'E', 'B', 'P')) return "image/webp";
            throw new IllegalArgumentException("Unknown image format: use PNG, JPEG, GIF or WebP");
        }

        private static boolean startsWith(byte[] d, int offset, int... magic) {
            if (d == null || d.length < offset + magic.length) return false;
            for (int i = 0; i < magic.length; i++)
                if ((d[offset + i] & 0xFF) != magic[i]) return false;
            return true;
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        @Override
        public Modality modality() {
            return Modality.IMAGE;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Image i && mimeType.equals(i.mimeType) && Arrays.equals(data, i.data);
        }

        @Override
        public int hashCode() {
            return 31 * mimeType.hashCode() + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return "Image[" + mimeType + ", " + data.length + " bytes]";
        }
    }

    /** Mono PCM samples in [-1, 1] at {@code sampleRate} Hz. */
    record Audio(float[] samples, int sampleRate) implements Part {
        public Audio {
            if (samples == null || samples.length == 0) throw new IllegalArgumentException("audio is empty");
            if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
            samples = samples.clone();
        }

        static Audio of(AudioInputStream source) throws IOException {
            AudioFormat f = source.getFormat();
            AudioFormat pcm = new AudioFormat(f.getSampleRate(), 16, f.getChannels(), true, false);
            byte[] bytes;
            try (AudioInputStream in = AudioSystem.getAudioInputStream(pcm, source)) {
                bytes = in.readAllBytes();
            }
            int channels = pcm.getChannels();
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            float[] mono = new float[bytes.length / (2 * channels)];
            for (int i = 0; i < mono.length; i++) {
                float sum = 0;
                for (int c = 0; c < channels; c++)
                    sum += b.getShort() / 32768f;
                mono[i] = sum / channels;
            }
            return new Audio(mono, Math.round(pcm.getSampleRate()));
        }

        public Duration duration() {
            return Duration.ofNanos(Math.round(samples.length * 1e9 / sampleRate));
        }

        @Override
        public float[] samples() {
            return samples.clone();
        }

        @Override
        public Modality modality() {
            return Modality.AUDIO;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Audio a && sampleRate == a.sampleRate && Arrays.equals(samples, a.samples);
        }

        @Override
        public int hashCode() {
            return 31 * sampleRate + Arrays.hashCode(samples);
        }

        @Override
        public String toString() {
            return "Audio[" + samples.length + " samples, " + sampleRate + " Hz]";
        }
    }
}
