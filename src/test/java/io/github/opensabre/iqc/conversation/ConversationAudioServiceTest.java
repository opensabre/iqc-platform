package io.github.opensabre.iqc.conversation;
import io.github.opensabre.iqc.conversation.dao.*;
import io.github.opensabre.iqc.conversation.model.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.governance.IqcException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ConversationAudioServiceTest {
 @TempDir Path directory;
 ConversationMapper conversations;ConversationMessageMapper messages;IqcDataScope scope;ConversationAudioService service;
 @BeforeEach void setup(){conversations=mock(ConversationMapper.class);messages=mock(ConversationMessageMapper.class);scope=mock(IqcDataScope.class);
 var row=new Conversation();row.setId("c");row.setCreatedBy("owner");row.setOwnerGroupId("group");row.setSourceFingerprint("revision");
 when(conversations.selectById("c")).thenReturn(row);when(scope.canView("owner","group")).thenReturn(true);when(messages.selectList(any())).thenReturn(List.of());
 var properties=new ConversationUploadProperties();properties.setAudioDirectory(directory.toString());service=new ConversationAudioService(conversations,messages,scope,properties);
 }
 byte[] wav(){var b=ByteBuffer.allocate(16044).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16036).put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));b.putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16);b.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16000);return b.array();}
 @Test void publishesActualValidatedBytesAndBindsActualDuration() throws Exception {
 when(conversations.bindAudio(eq("c"),eq("revision"),anyString(),eq(16044L),eq("audio/wav"),eq(1000L))).thenReturn(1);
 var metadata=service.upload("c",new ByteArrayInputStream(wav()));assertEquals(1000,metadata.durationMs());assertEquals(16044,metadata.sizeBytes());
 try(var files=Files.list(directory)){var stored=files.toList();assertEquals(1,stored.size());assertArrayEquals(wav(),Files.readAllBytes(stored.get(0)));}
 }
 @Test void rejectsInvalidMediaBeforeBindingOrPublication() throws Exception {
 assertThrows(IqcException.class,()->service.upload("c",new ByteArrayInputStream(new byte[]{1,2,3})));
 verify(conversations,never()).bindAudio(anyString(),anyString(),anyString(),anyLong(),anyString(),anyLong());try(var files=Files.list(directory)){assertEquals(0,files.count());}
 }
 @Test void rejectsOutOfBoundsTranscriptBeforePublication() throws Exception {
 var segment=new ConversationMessage();segment.setEndMs(1001L);when(messages.selectList(any())).thenReturn(List.of(segment));
 assertThrows(IqcException.class,()->service.upload("c",new ByteArrayInputStream(wav())));
 verify(conversations,never()).bindAudio(anyString(),anyString(),anyString(),anyLong(),anyString(),anyLong());try(var files=Files.list(directory)){assertEquals(0,files.count());}
 }
 @Test void rejectsForeignScopeBeforeReadingInput() throws Exception {
 when(scope.canView("owner","group")).thenReturn(false);var input=mock(InputStream.class);
 assertThrows(IqcException.class,()->service.upload("c",input));verifyNoInteractions(input,messages);
 assertThrows(IqcException.class,()->service.metadata("c"));assertThrows(IqcException.class,()->service.open("c"));
 }
}
