package com.vichrov.localai;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import java.io.*;
import static org.junit.Assert.*;
public class GigaArchiveTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private File archive(String path,boolean link) throws Exception {
        File f=temp.newFile();
        try(TarArchiveOutputStream out=new TarArchiveOutputStream(new BZip2CompressorOutputStream(new FileOutputStream(f)))) {
            TarArchiveEntry e=link?new TarArchiveEntry(path,TarConstants.LF_SYMLINK):new TarArchiveEntry(path);
            if(link)e.setLinkName("../../outside"); else e.setSize(3);
            out.putArchiveEntry(e); if(!link)out.write(new byte[]{1,2,3}); out.closeArchiveEntry();
        }
        return f;
    }
    @Test public void acceptsOnlyExpectedModelRoot() throws Exception {
        File dest=temp.newFolder(); ModelStore.unpackGiga(archive(ModelStore.GIGA_ROOT+"/tokens.txt",false),dest);
        assertEquals(3,new File(dest,ModelStore.GIGA_ROOT+"/tokens.txt").length());
    }
    @Test public void rejectsTraversal() throws Exception {
        try { ModelStore.unpackGiga(archive(ModelStore.GIGA_ROOT+"/../../outside",false),temp.newFolder()); fail(); }
        catch(IOException expected) { }
    }
    @Test public void rejectsLinks() throws Exception {
        try { ModelStore.unpackGiga(archive(ModelStore.GIGA_ROOT+"/link",true),temp.newFolder()); fail(); }
        catch(IOException expected) { }
    }
}
