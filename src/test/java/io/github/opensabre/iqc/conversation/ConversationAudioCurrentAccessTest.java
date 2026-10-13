package io.github.opensabre.iqc.conversation;
import io.github.opensabre.iqc.rest.ConversationAudioController;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.*;
import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.security.context.InternalTokenUserContext;
import io.github.opensabre.iqc.governance.IqcException;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.time.Instant;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Real current-user projection and controller; transport and domain/storage remain mocked here. */
class ConversationAudioCurrentAccessTest {
 final IqcOrganizationClient organization=mock(IqcOrganizationClient.class);
 final IqcDataScope scope=mock(IqcDataScope.class);
 final ConversationAudioService audio=mock(ConversationAudioService.class);
 @SuppressWarnings("unchecked") final Result<IqcOrganizationClient.OrganizationUser> user=mock(Result.class);
 @SuppressWarnings("unchecked") final Result<IqcOrganizationClient.SchedulerAccess> projection=mock(Result.class);
 ConversationAudioController controller;
 @BeforeEach void setup(){when(scope.owner()).thenReturn("owner");when(scope.groupId()).thenReturn("group");when(organization.getUserByUniqueId("owner")).thenReturn(user);when(user.getData()).thenReturn(new IqcOrganizationClient.OrganizationUser("user","name","owner","group","team"));when(organization.currentActionAccess("user")).thenReturn(projection);controller=new ConversationAudioController(audio,new ScheduledActorAccessService(organization,scope,mock(InternalTokenUserContext.class)));}
 void grant(Set<String> resources,String group,Instant checked){when(projection.getData()).thenReturn(new IqcOrganizationClient.SchedulerAccess("iqc-scheduler-access-v1","user","owner",true,group,Set.of("ADMIN"),resources,checked));}
 @Test void readGrantDoesNotAuthorizeMetadataOrUpload() throws Exception {
 grant(Set.of("iqc:audio:read"),"group",Instant.now());
 assertThrows(IqcException.class,()->controller.metadata("conversation"));var file=new MockMultipartFile("file","a.wav","audio/wav",new byte[]{1});assertThrows(IqcException.class,()->controller.upload("conversation",file));verifyNoInteractions(audio);verify(organization,never()).schedulerAccess(anyString());
 }
 @Test void metadataGrantDoesNotAuthorizeBytes() throws Exception {
 grant(Set.of("iqc:audio:view"),"group",Instant.now());assertThrows(IqcException.class,()->controller.content("conversation",null,new MockHttpServletResponse()));verifyNoInteractions(audio);
 }
 @Test void staleProjectionAndGroupRevocationRejectBeforeDomainAccess() {
 grant(Set.of("iqc:audio:view"),"group",Instant.now().minusSeconds(60));assertThrows(IqcException.class,()->controller.metadata("conversation"));
 grant(Set.of("iqc:audio:view"),"new-group",Instant.now());assertThrows(IqcException.class,()->controller.metadata("conversation"));verifyNoInteractions(audio);
 }
 @Test void exactCurrentGrantReachesOriginalMetadataOnly() {
 grant(Set.of("iqc:audio:view"),"group",Instant.now());var metadata=new ConversationAudioService.Metadata("conversation","audio/wav",16044,1000);when(audio.metadata("conversation")).thenReturn(metadata);assertSame(metadata,controller.metadata("conversation"));verify(audio).metadata("conversation");verify(organization,never()).schedulerAccess(anyString());
 }
}
