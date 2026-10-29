package io.github.opensabre.iqc.conversation;

import java.io.*;
import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic actual PCM bytes verify duration and hostile structural inputs without external binaries. */
class ConversationWavMetadataTest {
    private byte[] wav() {
        var b=ByteBuffer.allocate(16044).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16036).put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16);
        b.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16000);return b.array();
    }
    private ConversationWavMetadata inspect(byte[] bytes) throws IOException {return ConversationWavMetadata.inspect(new ByteArrayInputStream(bytes),bytes.length);}
    @Test void derivesDurationFromActualPcmFrames() throws Exception {
        var actual=inspect(wav());assertEquals("audio/wav",actual.mediaType());assertEquals(1000,actual.durationMs());assertEquals(8000,actual.sampleRate());
    }
    @Test void rejectsTruncationFalseByteRateAndNonPcm() {
        var bytes=wav();assertThrows(IOException.class,()->inspect(java.util.Arrays.copyOf(bytes,bytes.length-1)));
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(28,1);assertThrows(IOException.class,()->inspect(bytes));
        var nonPcm=wav();nonPcm[20]=3;assertThrows(IOException.class,()->inspect(nonPcm));
    }
    @Test void rejectsForgedDataBoundsAndTrailingBytes() {
        var bytes=wav();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(40,-1);
        assertThrows(IOException.class,()->inspect(bytes));
        var extra=java.util.Arrays.copyOf(wav(),16045);assertThrows(IOException.class,()->inspect(extra));
    }
}
