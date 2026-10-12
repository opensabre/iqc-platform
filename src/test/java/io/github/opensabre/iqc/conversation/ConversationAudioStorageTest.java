package io.github.opensabre.iqc.conversation;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real filesystem identity, cleanup and non-overwrite checks for the persistent-volume adapter. */
class ConversationAudioStorageTest {
    @TempDir Path root;
    private ByteArrayInputStream bytes(){return new ByteArrayInputStream(new byte[]{1,2,3,4});}
    @Test void repeatedBytesReuseOneCompleteImmutableFile() throws Exception {
        var storage=new ConversationAudioStorage(root,4);var first=storage.save(bytes());
        assertEquals(first,storage.save(bytes()));
        try(var input=storage.open(first)){assertArrayEquals(new byte[]{1,2,3,4},input.readAllBytes());}
        try(var files=Files.list(root)){assertEquals(1,files.count());}
    }
    @Test void oversizedEmptyAndInterruptedInputLeaveNoPublishedOrTemporaryFiles() throws Exception {
        var storage=new ConversationAudioStorage(root,3);
        assertThrows(IOException.class,()->storage.save(bytes()));
        assertThrows(IOException.class,()->storage.save(new ByteArrayInputStream(new byte[0])));
        assertThrows(IOException.class,()->storage.save(new InputStream(){public int read() throws IOException{throw new IOException("interrupted");}}));
        try(var files=Files.list(root)){assertEquals(0,files.count());}
    }
    @Test void corruptExistingAddressIsRejectedWithoutOverwrite() throws Exception {
        var storage=new ConversationAudioStorage(root,4);var stored=storage.save(bytes());
        Files.write(root.resolve(stored.sha256()+".audio"),new byte[]{4,3,2,1});
        assertThrows(IOException.class,()->storage.open(stored));
        assertThrows(IOException.class,()->storage.save(bytes()));
        assertArrayEquals(new byte[]{4,3,2,1},Files.readAllBytes(root.resolve(stored.sha256()+".audio")));
    }
    @Test void traversalAndSymbolicLinkAddressesAreRejected() throws Exception {
        var storage=new ConversationAudioStorage(root,4);
        assertThrows(IllegalArgumentException.class,()->storage.open(new ConversationAudioStorage.Stored("../outside",4)));
        var stored=storage.save(bytes());var path=root.resolve(stored.sha256()+".audio");
        var outside=root.resolve("outside");Files.move(path,outside);Files.createSymbolicLink(path,outside);
        assertThrows(IOException.class,()->storage.open(stored));
        assertThrows(IOException.class,()->storage.save(bytes()));
    }
    @Test void concurrentIdenticalUploadsPublishOneCompleteFile() throws Exception {
        var storage=new ConversationAudioStorage(root,4);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<ConversationAudioStorage.Stored> upload=()->storage.save(new FilterInputStream(bytes()) {
            private boolean first=true;
            @Override public int read(byte[] buffer,int offset,int length) throws IOException {
                if(first){first=false;try{barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);}
                    catch(Exception failure){throw new IOException(failure);}}
                return super.read(buffer,offset,length);
            }
        });
        try {
            var a=pool.submit(upload);var b=pool.submit(upload);
            var stored=a.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(stored,b.get(15,java.util.concurrent.TimeUnit.SECONDS));
            try(var input=storage.open(stored)){assertArrayEquals(new byte[]{1,2,3,4},input.readAllBytes());}
            try(var files=Files.list(root)){assertEquals(1,files.count());}
        } finally {pool.shutdownNow();}
    }

}
