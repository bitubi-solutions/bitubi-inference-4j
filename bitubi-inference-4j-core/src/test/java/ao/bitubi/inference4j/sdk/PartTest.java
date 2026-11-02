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

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PartTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void imageTypeComesFromTheBytes() {
        assertEquals("image/png", Part.Image.of(new byte[] { (byte) 0x89, 'P', 'N', 'G', 1 }).mimeType());
        assertEquals("image/jpeg", Part.Image.of(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0 }).mimeType());
        assertEquals("image/webp", Part.Image.of("RIFF0000WEBPVP8 ".getBytes()).mimeType());
        assertThrows(IllegalArgumentException.class, () -> Part.Image.of("hello".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> Part.Image.of(new byte[0]));
    }

    @Test
    public void imageIsAValueTheCallerCannotChange() {
        byte[] png = { (byte) 0x89, 'P', 'N', 'G', 1 };
        Part.Image a = Part.Image.of(png);
        png[4] = 2;
        a.data()[4] = 3;
        assertEquals(1, a.data()[4]);
        assertEquals(a, Part.Image.of(new byte[] { (byte) 0x89, 'P', 'N', 'G', 1 }));
    }

    @Test
    public void stereoWavBecomesMonoSamples() throws Exception {
        // Two frames of 16-bit stereo: (16384, 0) and (-32768, -32768)
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 16384).putShort((short) 0).putShort((short) -32768).putShort((short) -32768);
        AudioFormat f = new AudioFormat(16000, 16, 2, true, false);
        Path wav = temporary.newFile("a.wav").toPath();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(b.array()), f, 2), AudioFileFormat.Type.WAVE, wav.toFile());

        Part.Audio audio = Part.audio(wav);
        assertEquals(16000, audio.sampleRate());
        assertArrayEquals(new float[] { 0.25f, -1f }, audio.samples(), 0f);
    }

    @Test
    public void unknownAudioFileFailsWithAHint() throws Exception {
        Path notAudio = temporary.newFile("a.mp3").toPath();
        java.nio.file.Files.writeString(notAudio, "not audio");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Part.audio(notAudio));
        assertTrue(e.getMessage().contains("WAV"));
    }

    @Test
    public void messageReportsItsModalitiesAndText() {
        Message m = Message.user(
            Part.text("What is this?"),
            Part.Image.of(new byte[] { (byte) 0x89, 'P', 'N', 'G' }),
            Part.text("Be brief.")
        );
        assertEquals(EnumSet.of(Modality.TEXT, Modality.IMAGE), m.modalities());
        assertEquals("What is this?\nBe brief.", m.text());
        assertThrows(IllegalArgumentException.class, () -> new Message(Message.Role.USER, List.of()));
        assertThrows(NullPointerException.class, () -> Message.user((String) null));
    }
}
