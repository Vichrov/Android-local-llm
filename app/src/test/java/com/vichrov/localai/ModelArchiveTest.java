package com.vichrov.localai;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.zip.*;
import static org.junit.Assert.*;
public class ModelArchiveTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private File archive(String path) throws Exception {
        File zip=temp.newFile();
        try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(zip))) {
            z.putNextEntry(new ZipEntry(path)); z.write(new byte[]{1,2,3}); z.closeEntry();
        }
        return zip;
    }
    @Test public void rejectsTraversal() throws Exception {
        File dest=temp.newFolder();
        try { ModelStore.unzip(archive("../escape"),dest); fail("Traversal must fail"); }
        catch(IOException expected) { assertFalse(new File(dest.getParentFile(),"escape").exists()); }
    }
    @Test public void extractsNestedModel() throws Exception {
        File dest=temp.newFolder(); ModelStore.unzip(archive("model/am/final.mdl"),dest);
        assertEquals(3,new File(dest,"model/am/final.mdl").length());
    }
}
