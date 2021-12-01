package gridss;

import au.edu.wehi.idsv.IntermediateFilesTest;
import au.edu.wehi.idsv.alignment.JniAlignerTests;
import com.google.common.io.Files;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertTrue;

public class PrepareReferenceTest extends IntermediateFilesTest {
    @Test
    @Category(JniAlignerTests.class)
    public void should_create_dictionary_bwaimage_gridsscache() throws IOException {
        File fa = testFolder.newFile("test.fa");
        File fai = testFolder.newFile("test.fa.fai");
        File dict = new File(testFolder.getRoot(), "test.fa.dict");
        File img = new File(testFolder.getRoot(), "test.fa.img");
        File cache = new File(testFolder.getRoot(), "test.fa.gridsscache");
        Files.copy(SMALL_FA_FILE, fa);
        Files.copy(new File(SMALL_FA_FILE.getAbsolutePath() + ".fai"), fai);
        PrepareReference cmd = new PrepareReference();
        cmd.instanceMain(new String[] {
                "R=" + fa.getAbsolutePath(),
        });
        assertTrue(dict.exists());
        assertTrue(img.exists());
        assertTrue(cache.exists());
        assertTrue(dict.length() > 0);
        assertTrue(img.length() > 0);
        assertTrue(cache.length() > 0);
    }
}