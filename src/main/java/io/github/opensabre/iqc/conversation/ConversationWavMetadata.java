package io.github.opensabre.iqc.conversation;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Reads actual bounded PCM WAV structure and duration; upload names and declared media types are ignored. */
public record ConversationWavMetadata(String mediaType,long durationMs,int sampleRate,int channels,int bitsPerSample) {
    private static byte[] read(InputStream input,int count) throws IOException {
        var bytes=input.readNBytes(count);if(bytes.length!=count)throw new EOFException("Truncated WAV");return bytes;
    }
    private static String tag(byte[] bytes,int offset){return new String(bytes,offset,4,StandardCharsets.US_ASCII);}
    private static long unsigned(byte[] bytes,int offset){return Integer.toUnsignedLong(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset));}
    /** Validates every chunk boundary and PCM frame before deriving duration; does not close caller input. */
    public static ConversationWavMetadata inspect(InputStream input,long size) throws IOException {
        if(size<44 || size>0xffffffffL+8)throw new IOException("Invalid WAV size");
        var header=read(input,12);
        if(!"RIFF".equals(tag(header,0)) || !"WAVE".equals(tag(header,8)) || unsigned(header,4)!=size-8)
            throw new IOException("Invalid WAV container");
        long position=12,dataBytes=-1;int rate=0,channels=0,bits=0,block=0;boolean format=false;
        while(position<size) {
            if(size-position<8)throw new IOException("Incomplete WAV chunk");
            var chunk=read(input,8);position+=8;long length=unsigned(chunk,4),padded=length+(length&1);
            if(padded>size-position)throw new IOException("WAV chunk exceeds file");
            switch(tag(chunk,0)) {
                case "fmt " -> {
                    if(format || length!=16)throw new IOException("Unsupported or duplicate WAV format");
                    var fmt=ByteBuffer.wrap(read(input,16)).order(ByteOrder.LITTLE_ENDIAN);
                    int encoding=Short.toUnsignedInt(fmt.getShort());channels=Short.toUnsignedInt(fmt.getShort());
                    long sampleRate=Integer.toUnsignedLong(fmt.getInt()),byteRate=Integer.toUnsignedLong(fmt.getInt());
                    block=Short.toUnsignedInt(fmt.getShort());bits=Short.toUnsignedInt(fmt.getShort());
                    if(encoding!=1 || channels<1 || channels>8 || sampleRate<8000 || sampleRate>192000
                            || !java.util.Set.of(8,16,24,32).contains(bits) || block!=channels*(bits/8) || byteRate!=sampleRate*block)
                        throw new IOException("Unsupported or inconsistent PCM WAV");
                    rate=(int)sampleRate;format=true;
                }
                case "data" -> {
                    if(!format || dataBytes!=-1 || length==0 || length%block!=0)throw new IOException("Invalid WAV data frames");
                    dataBytes=length;input.skipNBytes(length);
                }
                default -> input.skipNBytes(length);
            }
            if((length&1)!=0)read(input,1);position+=padded;
        }
        if(!format || dataBytes<0 || input.read()!=-1)throw new IOException("Incomplete or trailing WAV bytes");
        long frames=dataBytes/block,duration=(frames*1000+rate-1)/rate;
        return new ConversationWavMetadata("audio/wav",duration,rate,channels,bits);
    }
}
