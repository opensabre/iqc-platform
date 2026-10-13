package io.github.opensabre.iqc.conversation;

import io.github.opensabre.iqc.conversation.dao.*;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.governance.IqcException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.file.*;

/** Original-conversation attachment operations; HTTP resource permissions complement mandatory row scope. */
@Service @RequiredArgsConstructor
public class ConversationAudioService {
    public record Metadata(String conversationId,String mediaType,long sizeBytes,long durationMs) { }
    private final ConversationMapper conversations;
    private final ConversationMessageMapper messages;
    private final IqcDataScope scope;
    private final ConversationUploadProperties properties;
    private Conversation authorized(String id) {
        var row=conversations.selectById(id);
        if(row==null)throw IqcException.notFound("会话不存在");
        if(!scope.canView(row.getCreatedBy(),row.getOwnerGroupId()))throw IqcException.accessDenied("无权访问该会话音频");
        return row;
    }
    private ConversationAudioStorage storage() throws IOException {
        if(properties.getAudioDirectory()==null || properties.getAudioDirectory().isBlank())throw IqcException.invalidState("音频持久目录尚未配置");
        return new ConversationAudioStorage(Path.of(properties.getAudioDirectory()),properties.getMaxAudioSizeBytes());
    }
    public Metadata metadata(String id) {return metadata(authorized(id));}
    private Metadata metadata(Conversation row) {
        var audio=ProductionInputSnapshotService.Audio.from(row);
        if(audio==null)throw IqcException.notFound("会话尚未绑定音频");
        return new Metadata(row.getId(),audio.mediaType(),audio.sizeBytes(),audio.durationMs());
    }
    /** Rechecks row scope immediately before opening verified bytes; callers never supply a storage key. */
    public InputStream open(String id) throws IOException {
        var row=authorized(id);var metadata=metadata(row);
        return storage().open(new ConversationAudioStorage.Stored(row.getAudioSha256(),metadata.sizeBytes()));
    }
    /** Validates actual bounded PCM bytes before immutable publication and binds only an unbound original row. */
    @Transactional
    public Metadata upload(String id,InputStream input) throws IOException {
        var row=authorized(id);var storage=storage();
        var staging=Files.createTempFile("iqc-audio-validation-",".wav");
        try {
            long size=0;byte[] buffer=new byte[8192];
            try(var out=Files.newOutputStream(staging)) {
                for(int count;(count=input.read(buffer))!=-1;) {
                    if(count==0)continue;
                    if(count>properties.getMaxAudioSizeBytes()-size)throw IqcException.invalidArgument("音频超过大小限制");
                    out.write(buffer,0,count);size+=count;
                }
            }
            ConversationWavMetadata media;
            try(var bytes=Files.newInputStream(staging)){media=ConversationWavMetadata.inspect(bytes,size);}
            catch(IOException invalidMedia){throw IqcException.invalidArgument("音频不是有效的PCM WAV文件");}
            var transcript=messages.selectList(Wrappers.<io.github.opensabre.iqc.conversation.model.ConversationMessage>lambdaQuery()
                    .eq(io.github.opensabre.iqc.conversation.model.ConversationMessage::getConversationId,id).last("LIMIT 10001"));
            if(transcript.size()>10000 || transcript.stream().anyMatch(message->message.getEndMs()!=null && message.getEndMs()>media.durationMs()))
                throw IqcException.invalidArgument("转写区间超过实际音频时长");
            ConversationAudioStorage.Stored stored;
            try(var bytes=Files.newInputStream(staging)){stored=storage.save(bytes);}
            if(row.getAudioSha256()!=null) {
                if(!row.getAudioSha256().equals(stored.sha256()) || !metadata(row).equals(new Metadata(id,media.mediaType(),stored.sizeBytes(),media.durationMs())))
                    throw IqcException.invalidState("已有音频不能替换，请使用新的会话修订");
                return metadata(row);
            }
            if(conversations.bindAudio(id,row.getSourceFingerprint(),stored.sha256(),stored.sizeBytes(),media.mediaType(),media.durationMs())!=1) {
                var actual=authorized(id);
                if(!stored.sha256().equals(actual.getAudioSha256()) || !metadata(actual).equals(new Metadata(id,media.mediaType(),stored.sizeBytes(),media.durationMs())))
                    throw IqcException.invalidState("会话音频绑定已变化");
                return metadata(actual);
            }
            return new Metadata(id,media.mediaType(),stored.sizeBytes(),media.durationMs());
        } finally {Files.deleteIfExists(staging);}
    }
}
