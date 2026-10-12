package io.github.opensabre.iqc.conversation;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Immutable content-addressed bytes on an application-managed persistent volume; callers own authorization. */
public final class ConversationAudioStorage {
    public record Stored(String sha256,long sizeBytes) { }
    private final Path root;
    private final long maximumBytes;
    public ConversationAudioStorage(Path directory,long maximumBytes) throws IOException {
        if(maximumBytes<1)throw new IllegalArgumentException("Audio size limit must be positive");
        Files.createDirectories(directory);
        root=directory.toRealPath();this.maximumBytes=maximumBytes;
    }
    private static MessageDigest digest() {
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private Path path(String hash) {
        if(hash==null || !hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid audio digest");
        return root.resolve(hash+".audio");
    }
    /** Streams bounded bytes, then publishes without replacing an existing digest; never closes caller input. */
    public Stored save(InputStream input) throws IOException {
        if(input==null)throw new IllegalArgumentException("Missing audio input");
        Path temporary=Files.createTempFile(root,".upload-",".tmp");
        try {
            var digest=digest();long size=0;byte[] buffer=new byte[8192];
            try(var output=Files.newOutputStream(temporary)) {
                for(int count;(count=input.read(buffer))!=-1;) {
                    if(count==0)continue;
                    if(count>maximumBytes-size)throw new IOException("Audio exceeds configured size limit");
                    output.write(buffer,0,count);digest.update(buffer,0,count);size+=count;
                }
            }
            if(size==0)throw new IOException("Audio input is empty");
            var stored=new Stored(HexFormat.of().formatHex(digest.digest()),size);
            // A hard link atomically publishes complete bytes and fails if the address already exists.
            try{Files.createLink(path(stored.sha256()),temporary);}
            catch(FileAlreadyExistsException duplicate){try(var existing=open(stored)){/* Verify before reusing. */}}
            return stored;
        } finally {Files.deleteIfExists(temporary);}
    }
    /** Verifies identity on the same descriptor returned to the reader; rejects symbolic links and corrupt files. */
    public InputStream open(Stored stored) throws IOException {
        if(stored==null || stored.sizeBytes()<1 || stored.sizeBytes()>maximumBytes)throw new IllegalArgumentException("Invalid audio size");
        var channel=Files.newByteChannel(path(stored.sha256()),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
        try {
            if(channel.size()!=stored.sizeBytes())throw new IOException("Stored audio size changed");
            var digest=digest();var buffer=java.nio.ByteBuffer.allocate(8192);
            while(channel.read(buffer)!=-1){buffer.flip();digest.update(buffer);buffer.clear();}
            if(!HexFormat.of().formatHex(digest.digest()).equals(stored.sha256()))throw new IOException("Stored audio digest changed");
            channel.position(0);return Channels.newInputStream(channel);
        } catch(IOException|RuntimeException failure){channel.close();throw failure;}
    }
}
