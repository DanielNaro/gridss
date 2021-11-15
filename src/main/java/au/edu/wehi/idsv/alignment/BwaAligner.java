package au.edu.wehi.idsv.alignment;

import au.edu.wehi.idsv.Defaults;
import au.edu.wehi.idsv.sam.SAMRecordUtil;
import htsjdk.samtools.*;
import htsjdk.samtools.fastq.FastqRecord;
import htsjdk.samtools.fastq.FastqWriter;
import htsjdk.samtools.fastq.FastqWriterFactory;
import htsjdk.samtools.util.Log;
import org.broadinstitute.hellbender.utils.bwa.BwaMemAligner;
import org.broadinstitute.hellbender.utils.bwa.BwaMemAlignment;
import org.broadinstitute.hellbender.utils.bwa.BwaMemIndex;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Wrapper for the BwaMemAligner that returns SAMRecords
 */
public class BwaAligner implements Closeable {
    private static final Log log = Log.getInstance(BwaAligner.class);
    private final BwaMemIndex index;
    private final BwaMemAligner aligner;
    private final SAMSequenceDictionary dict;
    private final SAMFileHeader header;
    private final AtomicInteger exportId = new AtomicInteger();

    public BwaMemAligner getAligner() {
        return this.aligner;
    }

    /***
     * Ensure java.io.tmpdir actually exists before attempting
     */
    static {
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        if (!tmpDir.exists()) {
            if (!tmpDir.mkdir()) {
                log.info("Created " + tmpDir);
            }
        }
    }
    public BwaAligner(File reference, SAMSequenceDictionary dict, int threads) {
        this.index = getBwaIndexFor(reference);
        this.dict = dict;
        this.header = getMinimalHeader(dict);
        this.aligner = new BwaMemAligner(this.index);
        // -t
        this.aligner.setNThreadsOption(threads);
        // -L 0,0
        this.aligner.setClip3PenaltyOption(0);
        this.aligner.setClip5PenaltyOption(0);
        // -K 10000000
        this.aligner.setChunkSizeOption(10000000);
        try {
            ensureMatchingReferences(this.index, dict);
        } catch (IllegalArgumentException e) {
            // don't leak the index since it's huge
            close();
            throw e;
        }
    }

    public static void createBwaIndexFor(File reference) throws IOException {
        File image = getBwaIndexFileFor(reference);
        if (!image.exists()) {
            log.warn("Unable to find " + image.toString() + ". Attempting to create.");
            File tmpFile = File.createTempFile(image.getName() + ".tmp", ".img", image.getParentFile());
            if (BwaMemIndex.INDEX_FILE_EXTENSIONS.stream().allMatch(suffix -> new File(reference.getAbsolutePath() + suffix).exists())) {
                log.warn("Found bwa index files. Attempting to create image from bwa index files");
                System.err.flush();
                BwaMemIndex.createIndexImageFromIndexFiles(reference.getAbsolutePath(), tmpFile.getAbsolutePath());

            } else {
                log.warn("Could not find bwa index files. Creating bwa image from reference genome. This is a one-time operation and may take several hours.");
                System.err.flush();
                BwaMemIndex.createIndexImageFromFastaFile(reference.getAbsolutePath(), tmpFile.getAbsolutePath());
            }
            Files.move(tmpFile.toPath(), image.toPath());
            if (image.exists()) {
                log.info("Index creation successful");
            } else {
                String msg = "Index creation failed for index file " + image.toString();
                log.error(msg);
                throw new RuntimeException(msg);
            }
        }
    }

    public static File getBwaIndexFileFor(File reference) {
        File image = new File(reference.getAbsolutePath() + BwaMemIndex.IMAGE_FILE_EXTENSION);
        return image;
    }

    public static BwaMemIndex getBwaIndexFor(File reference) {
        File image = getBwaIndexFileFor(reference);
        log.info("Loading bwa mem index image from " + image);
        System.err.flush(); // ensure our warning error message gets to the console as we're possible about to die in C code
        BwaMemIndex index = new BwaMemIndex(image.getAbsolutePath());
        return index;
    }

    private static SAMFileHeader getMinimalHeader(SAMSequenceDictionary dict) {
        SAMFileHeader header = new SAMFileHeader();
        for (SAMSequenceRecord ref : dict.getSequences()) {
            header.addSequence(ref);
        }
        return header;
    }

    public static void ensureMatchingReferences(BwaMemIndex index, SAMSequenceDictionary dict) {
        String indexNames = index.getReferenceContigNames().stream().collect(Collectors.joining("   "));
        String refNames = dict.getSequences().stream().map(x -> x.getSequenceName()).collect(Collectors.joining("   "));
        if (!indexNames.equals(refNames)) {
            throw new IllegalArgumentException("bwa index and reference genome sequences do not match");
        }
    }

    public List<SAMRecord> align(Collection<FastqRecord> input) {
        List<byte[]> inputs = new ArrayList<>(input.size());
        for (FastqRecord fq : input) {
            inputs.add(fq.getReadBases());
        }
        log.debug(String.format("Aligning %d sequences using BWA JNI", inputs.size()));
        if (Defaults.EXPORT_INPROCESS_ALIGNMENTS) {
            int id = exportId.incrementAndGet();
            String fqFile = String.format("gridss.bwa.export.%d.fq", id);
            String seqFile = String.format("gridss.bwa.export.%d.seq", id);
            log.info("Exporting to " + fqFile);
            try (FastqWriter writer = new FastqWriterFactory().newWriter(new File(fqFile))) {
                for (FastqRecord fq : input) {
                    writer.write(fq);
                }
            }
            try {
                Files.write(
                        new File(seqFile).toPath(),
                        inputs.stream().map(b -> new String(b)).collect(Collectors.toList()),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
            }
        }
        List<List<BwaMemAlignment>> bwaResult = aligner.alignSeqs(inputs);
        if (bwaResult.size() != input.size()) {
            throw new IllegalStateException(String.format("bwa returned alignments for %d reads, when input with %d reads.", bwaResult.size(), input.size()));
        }
        List<SAMRecord> samResult = new ArrayList<>((int)(input.size() * 1.3)); // conservatively guess 30% of alignments are split read alignments
        int i = 0;
        for (FastqRecord fq : input) {
            List<BwaMemAlignment> bma = bwaResult.get(i++);
            List<SAMRecord> alignments = transform(fq, bma);
            samResult.addAll(alignments);
        }
        return samResult;
    }

    public List<SAMRecord> transform(FastqRecord fq, List<BwaMemAlignment> bma) {
        List<SAMRecord> result = new ArrayList<>(bma.size() == 0 ? 1 : bma.size());
        if (bma.size() == 0 || bma.get(0).getRefId() == -1) {
            SAMRecord r = SAMRecordUtil.createSAMRecord(header, fq, false);
            r.setReadUnmappedFlag(true);
            result.add(r);
        } else {
            for (BwaMemAlignment alignment : bma) {
                SAMRecord r = createAlignment(fq, alignment);
                result.add(r);
            }
            SAMRecordUtil.reinterpretAsSplitReadAlignment(result, 25);
        }
        return result;
    }

    private SAMRecord createAlignment(FastqRecord fq, BwaMemAlignment alignment) {
        SAMRecord r = SAMRecordUtil.createSAMRecord(header, fq, (alignment.getSamFlag() & SAMFlag.READ_REVERSE_STRAND.intValue()) != 0);

        r.setFlags(alignment.getSamFlag());
        r.setReferenceIndex(alignment.getRefId());
        r.setAlignmentStart(alignment.getRefStart() + 1);
        r.setCigarString(alignment.getCigar());
        if (r.getCigar().getReadLength() != fq.getReadLength()) {
            // TODO: do I need to add soft clip padding to the alignment cigar?
            int leftClipping = alignment.getSeqStart();
            int rightClipping = fq.getReadLength() - alignment.getSeqEnd();
            //r.setCigarString( CigarUtil.addSoftClipping(leftClipping, rightClipping));
            throw new IllegalStateException(String.format("Read length is %d, cigar is %s", fq.getReadLength(), r.getCigarString()));
        }
        r.setMappingQuality(alignment.getMapQual());
        r.setMateReferenceIndex(alignment.getMateRefId());
        r.setMateReferenceIndex(alignment.getMateRefStart() + 1);
        r.setInferredInsertSize(alignment.getTemplateLen());
        r.setAttribute(SAMTag.MD.name(), alignment.getMDTag());
        r.setAttribute(SAMTag.NM.name(), alignment.getNMismatches());
        r.setAttribute(SAMTag.AS.name(), alignment.getAlignerScore());
        r.setAttribute("XA", alignment.getXATag());
        r.setAttribute("XS", alignment.getSuboptimalScore());
        return r;
    }

    @Override
    public void close() {
        // TODO: work out why we get Exception in thread "feedAligner" java.lang.IllegalStateException: Index image hg19.fa.img can't be closed:  it's in use.
        // this.aligner.close();
        // this.index.close();
    }
}
