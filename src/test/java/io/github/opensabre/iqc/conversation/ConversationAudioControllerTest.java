package io.github.opensabre.iqc.conversation;

import io.github.opensabre.iqc.rest.ConversationAudioController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual servlet range bytes and headers; the owning service and permission aspect need separate integration checks. */
class ConversationAudioControllerTest {
    private ConversationAudioService service(){var service=mock(ConversationAudioService.class);when(service.metadata("conversation")).thenReturn(new ConversationAudioService.Metadata("conversation","audio/wav",4,1000));return service;}
    @Test void fullAndBoundedAndSuffixRangesReturnExactBytes() throws Exception {
        for(String range:new String[]{null,"bytes=1-2","bytes=-2","bytes=2-"}) {
            var service=service();when(service.open("conversation")).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3,4}));
            var response=new MockHttpServletResponse();new ConversationAudioController(service).content("conversation",range,response);
            assertEquals(range==null?200:206,response.getStatus());assertEquals("private, no-store",response.getHeader("Cache-Control"));
            assertArrayEquals(range==null?new byte[]{1,2,3,4}:range.equals("bytes=1-2")?new byte[]{2,3}:new byte[]{3,4},response.getContentAsByteArray());
            assertEquals(response.getContentAsByteArray().length,response.getContentLength());
        }
    }
    @Test void invalidAndMultipleRangesNeverOpenBytes() throws Exception {
        for(String range:new String[]{"bytes=4-","bytes=2-1","bytes=-0","bytes=0-1,2-3","garbage"}) {
            var service=service();var response=new MockHttpServletResponse();new ConversationAudioController(service).content("conversation",range,response);
            assertEquals(416,response.getStatus());assertEquals("bytes */4",response.getHeader("Content-Range"));verify(service,never()).open(anyString());
        }
    }
    @Test void scopeDenialFromOwningServiceNeverOpensBytes() throws Exception {
        var service=service();when(service.metadata("conversation")).thenThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("denied"));
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->new ConversationAudioController(service).content("conversation",null,new MockHttpServletResponse()));
        verify(service,never()).open(anyString());
    }
}
