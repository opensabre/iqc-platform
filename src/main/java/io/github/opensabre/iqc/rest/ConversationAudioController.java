package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.*;
import io.github.opensabre.iqc.conversation.ConversationAudioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpRange;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;

/** Sensitive original bytes always pass both independent resource permission and original conversation scope. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/iqc/conversations/{id}/audio")
public class ConversationAudioController {
    private final ConversationAudioService service;
    private final io.github.opensabre.iqc.schedule.ScheduledActorAccessService actors;
    private void requireCurrent(String resource) {
        var bound=actors.capture();
        var current=actors.resolveCurrent(bound,java.util.Set.of(resource));
        if(current==null || current.all()!=bound.all() || !java.util.Objects.equals(current.groupId(),bound.groupId()))
            throw io.github.opensabre.iqc.governance.IqcException.accessDenied("音频访问范围已变化，请重新认证");
    }
    @PostMapping(consumes="multipart/form-data")
    @ResourcePermission(code="iqc:audio:upload",name="上传原始音频",type="iqc",description="绑定经过实际媒体校验的原始音频")
    @Audit(operationType=OperationType.UPLOAD,description="上传会话原始音频",module="IQC_AUDIO",request=false,response=false)
    public ConversationAudioService.Metadata upload(@PathVariable String id,@RequestPart("file") MultipartFile file) throws IOException {
        requireCurrent("iqc:audio:upload");
        try(var input=file.getInputStream()){return service.upload(id,input);}
    }
    @GetMapping
    @ResourcePermission(code="iqc:audio:view",name="查看音频信息",type="iqc",description="查看授权会话的音频元数据")
    @Audit(operationType=OperationType.QUERY,description="查看会话音频信息",module="IQC_AUDIO",request=false,response=false)
    public ConversationAudioService.Metadata metadata(@PathVariable String id){requireCurrent("iqc:audio:view");return service.metadata(id);}
    @GetMapping("/content")
    @ResourcePermission(code="iqc:audio:read",name="读取原始音频",type="iqc",description="敏感原始音频读取及Range播放")
    @Audit(operationType=OperationType.DOWNLOAD,description="读取会话原始音频",module="IQC_AUDIO",request=false,response=false)
    public void content(@PathVariable String id,@RequestHeader(value="Range",required=false) String range,HttpServletResponse response) throws IOException {
        requireCurrent("iqc:audio:read");
        var metadata=service.metadata(id);long size=metadata.sizeBytes(),start=0,end=size-1;
        response.setHeader("Cache-Control","private, no-store");response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Accept-Ranges","bytes");
        if(range!=null) {
            try {
                var ranges=HttpRange.parseRanges(range);
                if(ranges.size()!=1)throw new IllegalArgumentException("Only one bounded range is supported");
                start=ranges.getFirst().getRangeStart(size);end=ranges.getFirst().getRangeEnd(size);
                if(start<0 || start>=size || end<start || end>=size)throw new IllegalArgumentException("Invalid range");
            } catch(IllegalArgumentException invalid) {
                response.setStatus(416);response.setHeader("Content-Range","bytes */"+size);return;
            }
        }
        // Remain on the authenticated request thread and close the verified descriptor even on disconnect.
        try(var input=service.open(id)) {
            response.setStatus(range==null?200:206);response.setContentType(metadata.mediaType());
            if(range!=null)response.setHeader("Content-Range","bytes "+start+"-"+end+"/"+size);
            response.setContentLengthLong(end-start+1);input.skipNBytes(start);
            var output=response.getOutputStream();byte[] buffer=new byte[8192];long remaining=end-start+1;
            while(remaining>0) {
                int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
                if(count<0)throw new EOFException("Verified audio shortened during read");
                if(count==0)continue;output.write(buffer,0,count);remaining-=count;
            }
        }
    }
}
