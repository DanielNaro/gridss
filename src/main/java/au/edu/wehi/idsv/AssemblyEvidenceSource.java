package au.edu.wehi.idsv;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileHeader.SortOrder;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import au.edu.wehi.idsv.debruijn.anchored.DeBruijnAnchoredAssembler;
import au.edu.wehi.idsv.debruijn.subgraph.DeBruijnSubgraphAssembler;
import au.edu.wehi.idsv.util.AsyncBufferedIterator;
import au.edu.wehi.idsv.util.AutoClosingIterator;
import au.edu.wehi.idsv.util.AutoClosingMergedIterator;
import au.edu.wehi.idsv.util.FileHelper;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;


public class AssemblyEvidenceSource extends EvidenceSource {
	private static final Log log = Log.getInstance(AssemblyEvidenceSource.class);
	private final List<SAMEvidenceSource> source;
	private final int maxSourceFragSize;
	private final FileSystemContext fsc;
	/**
	 * Generates assembly evidence based on the given evidence
	 * @param evidence evidence for creating assembly
	 * @param intermediateFileLocation location to store intermediate files
	 */
	public AssemblyEvidenceSource(ProcessingContext processContext, List<SAMEvidenceSource> evidence, File intermediateFileLocation) {
		super(processContext, intermediateFileLocation);
		this.fsc = processContext.getFileSystemContext();
		this.source = evidence;
		int max = 0;
		for (SAMEvidenceSource s : evidence) {
			max = Math.max(max, s.getMaxConcordantFragmentSize());
		}
		maxSourceFragSize = max;
	}
	public void ensureAssembled() {
		ensureAssembled(null);
	}
	public void ensureAssembled(ExecutorService threadpool) {
		if (!isProcessingComplete()) {
			process(threadpool);
		}
	}
	public CloseableIterator<SAMRecordAssemblyEvidence> iterator(final boolean includeFiltered) {
		if (processContext.shouldProcessPerChromosome()) {
			// Lazily iterator over each input
			return new PerChromosomeAggregateIterator<SAMRecordAssemblyEvidence>(processContext.getReference().getSequenceDictionary(), new Function<String, Iterator<SAMRecordAssemblyEvidence>>() {
				@Override
				public Iterator<SAMRecordAssemblyEvidence> apply(String chr) {
					return perChrIterator(includeFiltered, chr);
				}
			});
		} else {
			return singleFileIterator(includeFiltered);
		}
	}
	public CloseableIterator<SAMRecordAssemblyEvidence> iterator(boolean includeFiltered, String chr) {
		if (processContext.shouldProcessPerChromosome()) {
			return perChrIterator(includeFiltered, chr);
		} else {
			return new ChromosomeFilteringIterator<SAMRecordAssemblyEvidence>(singleFileIterator(includeFiltered), processContext.getDictionary().getSequence(chr).getSequenceIndex(), true);
		}
	}
	protected CloseableIterator<SAMRecordAssemblyEvidence> perChrIterator(boolean includeFiltered, String chr) {
		FileSystemContext fsc = processContext.getFileSystemContext();
		return samAssemblyRealignIterator(
				includeFiltered,
				fsc.getAssemblyRawBamForChr(input, chr),
				fsc.getRealignmentBamForChr(input, chr),
				chr);
	}
	protected CloseableIterator<SAMRecordAssemblyEvidence> singleFileIterator(boolean includeFiltered) {
		FileSystemContext fsc = processContext.getFileSystemContext();
		return samAssemblyRealignIterator(
				includeFiltered,
				fsc.getAssemblyRawBam(input),
				fsc.getRealignmentBam(input),
				"");
	}
	private CloseableIterator<SAMRecordAssemblyEvidence> samAssemblyRealignIterator(boolean includeFiltered, File breakend, File realignment, String chr) {
		if (!isProcessingComplete()) {
			log.error("Assemblies not yet generated.");
			throw new RuntimeException("Assemblies not yet generated");
		}
		CloseableIterator<SAMRecordAssemblyEvidence> local = localSamIterator(breakend, realignment);
		CloseableIterator<SAMRecordAssemblyEvidence> it = local;
		return new AsyncBufferedIterator<SAMRecordAssemblyEvidence>(it, "Assembly " + chr);
	}
	private CloseableIterator<SAMRecordAssemblyEvidence> localSamIterator(File breakend, File realignment) {
		List<Closeable> toClose = new ArrayList<>();
		CloseableIterator<SAMRecord> realignedIt; 
		if (isRealignmentComplete()) {
			realignedIt = processContext.getSamReaderIterator(realignment);
			toClose.add(realignedIt);
		} else {
			log.debug(String.format("Assembly realignment for %s not completed", breakend));
			realignedIt = new AutoClosingIterator<SAMRecord>(ImmutableList.<SAMRecord>of().iterator());
		}
		CloseableIterator<SAMRecord> rawReaderIt = processContext.getSamReaderIterator(breakend, SortOrder.coordinate);
		toClose.add(rawReaderIt);
		CloseableIterator<SAMRecordAssemblyEvidence> evidenceIt = new SAMRecordAssemblyEvidenceIterator(
				processContext, this,
				new AutoClosingIterator<SAMRecord>(rawReaderIt, ImmutableList.<Closeable>of(realignedIt)),
				realignedIt,
				false);
		toClose.add(evidenceIt);
		// Change sort order to breakend position order
		CloseableIterator<SAMRecordAssemblyEvidence> sortedIt = new AutoClosingIterator<SAMRecordAssemblyEvidence>(new DirectEvidenceWindowedSortingIterator<SAMRecordAssemblyEvidence>(
				processContext,
				(int)((2 + processContext.getAssemblyParameters().maxSubgraphFragmentWidth + processContext.getAssemblyParameters().subgraphAssemblyMargin) * maxSourceFragSize),
				evidenceIt), toClose);
		return sortedIt;
	}
	private boolean isProcessingComplete() {
		boolean done = true;
		if (processContext.shouldProcessPerChromosome()) {
			for (SAMSequenceRecord seq : processContext.getReference().getSequenceDictionary().getSequences()) {
				done &= IntermediateFileUtil.checkIntermediate(fsc.getAssemblyRawBamForChr(input, seq.getSequenceName()));
				if (!done) return false;
				done &= IntermediateFileUtil.checkIntermediate(fsc.getRealignmentFastqForChr(input, seq.getSequenceName()));
				if (!done) return false;
			}
		} else {
			done &= IntermediateFileUtil.checkIntermediate(fsc.getAssemblyRawBam(input));
			if (!done) return false;
			done &= IntermediateFileUtil.checkIntermediate(fsc.getRealignmentFastq(input));
			if (!done) return false;
		}
		return done;
	}
	protected void process(ExecutorService threadpool) {
		if (isProcessingComplete()) return;
		log.info("START evidence assembly ", input);
		for (SAMEvidenceSource s : source) {
			if (!s.isComplete(ProcessStep.EXTRACT_READ_PAIRS) ||
				!s.isComplete(ProcessStep.EXTRACT_SOFT_CLIPS)) {
				throw new IllegalStateException(String.format("Unable to perform assembly: evidence extraction not complete for %s", s.getSourceFile()));
			}
		}
		final SAMSequenceDictionary dict = processContext.getReference().getSequenceDictionary();
		if (processContext.shouldProcessPerChromosome()) {
			final List<Callable<Void>> workers = new ArrayList<>();
			for (int i = 0; i < dict.size(); i++) {
				final String seq = dict.getSequence(i).getSequenceName();
				
				if (IntermediateFileUtil.checkIntermediate(fsc.getAssemblyRawBamForChr(input, seq))
					&& IntermediateFileUtil.checkIntermediate(fsc.getRealignmentFastqForChr(input, seq))) {
					log.debug("Skipping assembly for ", seq, " as output already exists.");
				} else {
					workers.add(new Callable<Void>() {
						@Override
						public Void call() {
							log.info("Starting ", seq, " breakend assembly");
							CloseableIterator<DirectedEvidence> merged = null;
							List<CloseableIterator<DirectedEvidence>> toMerge = new ArrayList<>();
							try {
								for (SAMEvidenceSource bam : source) {
									CloseableIterator<DirectedEvidence> it = bam.iterator(true, true, false, seq);
									toMerge.add(it);
								}
								merged = new AutoClosingMergedIterator<DirectedEvidence>(toMerge, DirectedEvidenceOrder.ByNatural);
								new ContigAssembler(merged, processContext.getFileSystemContext().getAssemblyRawBamForChr(input, seq), processContext.getFileSystemContext().getRealignmentFastqForChr(input, seq)).run();
								merged.close();
							} catch (Exception e) {
								log.error(e, "Error performing ", seq, " breakend assembly");
								throw new RuntimeException(e);
							} finally {
								CloserUtil.close(merged);
								CloserUtil.close(toMerge);
							}
							log.info("Completed ", seq, " breakend assembly");
							return null;
						}
					});
				}
			}
			if (threadpool != null) {
				log.info("Performing multithreaded assembly");
				try {
					for (Future<Void> future : threadpool.invokeAll(workers)) {
						// Throws any exceptions back up the call stack
						try {
							future.get();
						} catch (ExecutionException e) {
							throw new RuntimeException(e);
						}
					}
				} catch (InterruptedException e) {
					String msg = "Interrupted while assembly in progress";
					log.error(e, msg);
					throw new RuntimeException(msg, e);
				}
			} else {
				log.info("Performing singlethreaded assembly");
				for (Callable<Void> c : workers) {
					try {
						c.call();
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			}
		} else {
			List<CloseableIterator<DirectedEvidence>> toMerge = new ArrayList<>();
			CloseableIterator<DirectedEvidence> merged = null;
			try {
				for (SAMEvidenceSource bam : source) {
					CloseableIterator<DirectedEvidence> it = bam.iterator(true, true, false);
					toMerge.add(it);
				}
				merged = new AutoClosingMergedIterator<DirectedEvidence>(toMerge, DirectedEvidenceOrder.ByNatural);
				new ContigAssembler(merged, processContext.getFileSystemContext().getAssemblyRawBam(input), processContext.getFileSystemContext().getRealignmentFastq(input)).run();
				merged.close();
			} finally {
				CloserUtil.close(merged);
				CloserUtil.close(toMerge);
			}
		}
		log.info("SUCCESS evidence assembly ", input);
	}
	private class ContigAssembler implements Runnable {
		private Iterator<DirectedEvidence> it;
		private File breakendOutput;
		private File realignmentFastq;
		private FastqBreakpointWriter fastqWriter = null;
		//private VariantContextWriter vcfWriter = null;
		//private Queue<AssemblyEvidence> resortBuffer = new PriorityQueue<AssemblyEvidence>(32, DirectedEvidence.ByStartEnd);
		private SAMFileWriter writer = null;
		private Queue<SAMRecordAssemblyEvidence> resortBuffer = new PriorityQueue<SAMRecordAssemblyEvidence>(32, SAMRecordAssemblyEvidence.BySAMCoordinate);
		private long maxAssembledPosition = Long.MIN_VALUE;
		private long lastFlushedPosition = Long.MIN_VALUE;
		private long lastProgress = 0;
		public ContigAssembler(Iterator<DirectedEvidence> it, File breakendOutput, File realignmentFastq) {
			this.it = it;
			this.breakendOutput = breakendOutput;
			this.realignmentFastq = realignmentFastq;
		}
		@Override
		public void run() {
			try {
				FileSystemContext.getWorkingFileFor(breakendOutput).delete();
				FileSystemContext.getWorkingFileFor(realignmentFastq).delete();
				//writer = processContext.getVariantContextWriter(FileSystemContext.getWorkingFileFor(breakendVcf), true); 
				writer = processContext.getSamFileWriterFactory(true).makeSAMOrBAMWriter(new SAMFileHeader() {{
						setSequenceDictionary(processContext.getReference().getSequenceDictionary());
						setSortOrder(SortOrder.coordinate);
					}}, true, FileSystemContext.getWorkingFileFor(breakendOutput));
				fastqWriter = new FastqBreakpointWriter(processContext.getFastqWriterFactory().newWriter(FileSystemContext.getWorkingFileFor(realignmentFastq)));
				ReadEvidenceAssembler assembler = getAssembler();
				final ProgressLogger progress = new ProgressLogger(log);
				while (it.hasNext()) {
					DirectedEvidence readEvidence = it.next();
					if (readEvidence instanceof NonReferenceReadPair) {
						progress.record(((NonReferenceReadPair)readEvidence).getLocalledMappedRead());
					} else if (readEvidence instanceof SoftClipEvidence) {
						progress.record(((SoftClipEvidence)readEvidence).getSAMRecord());
					}
					// Need to process assembly evidence first since assembly calls are made when the
					// evidence falls out of scope so processing a given position will emit evidence
					// for a previous position (for it is only at this point we know there is no more
					// evidence for the previous position).
					processAssembly(assembler.addEvidence(readEvidence));
					
					if (maxAssembledPosition / 1000000 > lastProgress / 1000000) {
						lastProgress = maxAssembledPosition;
						log.info(String.format("Assembly at %s:%d %s",
								processContext.getDictionary().getSequence(processContext.getLinear().getReferenceIndex(lastProgress)).getSequenceName(),
								processContext.getLinear().getReferencePosition(lastProgress),
								assembler.getStateSummaryMetrics()));
					}
				}
				processAssembly(assembler.endOfEvidence());
				flushWriterQueueBefore(Long.MAX_VALUE);
				fastqWriter.close();
				writer.close();
				FileHelper.move(FileSystemContext.getWorkingFileFor(breakendOutput), breakendOutput, true);
				FileHelper.move(FileSystemContext.getWorkingFileFor(realignmentFastq), realignmentFastq, true);
			} catch (IOException e) {
				log.error(e, "Error assembling breakend ", breakendOutput);
				throw new RuntimeException("Error assembling breakend", e);
			} finally {
				if (fastqWriter != null) fastqWriter.close();
				if (writer != null) writer.close();
			}
		}
		private void processAssembly(Iterable<AssemblyEvidence> evidenceList) {
	    	if (evidenceList != null) {
		    	for (AssemblyEvidence a : evidenceList) {
		    		if (a != null) {
			    		maxAssembledPosition = Math.max(maxAssembledPosition, processContext.getLinear().getStartLinearCoordinate(a.getBreakendSummary()));
			    		resortBuffer.add((SAMRecordAssemblyEvidence)a);
		    		}
		    	}
	    	}
	    	flushWriterQueueBefore(maxAssembledPosition - (long)((processContext.getAssemblyParameters().maxSubgraphFragmentWidth * 2) * getMaxConcordantFragmentSize()));
	    }
		private void flushWriterQueueBefore(long flushBefore) {
			while (!resortBuffer.isEmpty() && processContext.getLinear().getStartLinearCoordinate(resortBuffer.peek().getBreakendSummary()) < flushBefore) {
				long pos = processContext.getLinear().getStartLinearCoordinate(resortBuffer.peek().getBreakendSummary());
				AssemblyEvidence evidence = resortBuffer.poll();
				if (pos < lastFlushedPosition) {
					log.error(String.format("Sanity check failure: assembly breakend %s written out of order.", evidence.getEvidenceID()));
				}
				lastFlushedPosition = pos;
				if (Defaults.WRITE_FILTERED_CALLS || !evidence.isAssemblyFiltered()) {
					writer.addAlignment(((SAMRecordAssemblyEvidence)evidence).getSAMRecord());
					//writer.add(evidence);
					if (processContext.getRealignmentParameters().shouldRealignBreakend(evidence)) {
						fastqWriter.write(evidence);
					}
				}
			}
		}
	}
	private ReadEvidenceAssembler getAssembler() {
    	switch (processContext.getAssemblyParameters().method) {
	    	case DEBRUIJN_PER_POSITION:
	    		return new DeBruijnAnchoredAssembler(processContext, this);
	    	case DEBRUIJN_SUBGRAPH:
	    		return new DeBruijnSubgraphAssembler(processContext, this);
	    	default:
	    		throw new IllegalArgumentException("Unknown assembly method.");
    	}
    }
	@Override
	public int getMaxConcordantFragmentSize() {
		return maxSourceFragSize;
	}
}
