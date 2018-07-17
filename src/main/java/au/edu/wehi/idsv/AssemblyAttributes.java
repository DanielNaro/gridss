package au.edu.wehi.idsv;

import au.edu.wehi.idsv.sam.SamTags;
import au.edu.wehi.idsv.util.MessageThrottler;
import com.google.common.collect.Range;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.util.Log;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AssemblyAttributes {
	private static final Log log = Log.getInstance(AssemblyAttributes.class);
	private static final String ID_COMPONENT_SEPARATOR = " ";
	private final SAMRecord record;
	private Collection<AssemblyEvidenceSupport> support = null;
	public static boolean isAssembly(SAMRecord record) {
		return record.getAttribute(SamTags.ASSEMBLY_EVIDENCE_EVIDENCEID) != null;
	}
	public static boolean isUnanchored(SAMRecord record) {
		return record.hasAttribute(SamTags.UNANCHORED);
		//return Iterables.any(record.getCigar().getCigarElements(), ce -> ce.getOperator() == CigarOperator.X);
	}
	public static boolean isAssembly(DirectedEvidence record) {
		if (record instanceof SingleReadEvidence) {
			return isAssembly(((SingleReadEvidence)record).getSAMRecord());
		}
		return false;
	}
	public AssemblyAttributes(SAMRecord record) {
		if (!isAssembly(record)) {
			throw new IllegalArgumentException("record is not an assembly.");
		}
		this.record = record;
	}
	public AssemblyAttributes(SingleReadEvidence record) {
		this(record.getSAMRecord());
	}
	/**
	 * Determines whether the given record is part of the given assembly
	 *
	 * This method is a probabilistic method and it is possible for the record to return true
	 * when the record does not form part of the assembly breakend
	 *
	 * @return true if the record is likely part of the breakend, false if definitely not
	 */
	public boolean isPartOfAssembly(DirectedEvidence e) {
		return getSupport().stream().anyMatch(ee -> ee.getEvidenceID().equals(e.getEvidenceID()));
	}
	private Collection<AssemblyEvidenceSupport> getSupport() {
		if (support == null) {
			if (!record.hasAttribute(SamTags.ASSEMBLY_EVIDENCE_CATEGORY)) {
				String msg = "Fatal error: GRIDSS assembly annotation format has changed in version 1.8. Please delete the assembly bam file, assembly working directory and regenerate.";
				log.error(msg);
				throw new RuntimeException(msg);
			}
			byte[] type = record.getSignedByteArrayAttribute(SamTags.ASSEMBLY_EVIDENCE_TYPE);
			int[] category = record.getSignedIntArrayAttribute(SamTags.ASSEMBLY_EVIDENCE_CATEGORY);
			int[] intervalStart = record.getSignedIntArrayAttribute(SamTags.ASSEMBLY_EVIDENCE_OFFSET_START);
			int[] intervalEnd = record.getSignedIntArrayAttribute(SamTags.ASSEMBLY_EVIDENCE_OFFSET_END);
			float[] qual = record.getFloatArrayAttribute(SamTags.ASSEMBLY_EVIDENCE_QUAL);
			String[] evidenceId = record.getStringAttribute(SamTags.ASSEMBLY_EVIDENCE_EVIDENCEID).split(ID_COMPONENT_SEPARATOR);
			String[] fragmentId = record.getStringAttribute(SamTags.ASSEMBLY_EVIDENCE_FRAGMENTID).split(ID_COMPONENT_SEPARATOR);

			List<AssemblyEvidenceSupport> support = new ArrayList<>();
			for (int i = 0; i < category.length; i++) {
				support.add(new AssemblyEvidenceSupport(
						AssemblyEvidenceSupport.SupportType.value(type[i]),
						Range.closed(intervalStart[i], intervalEnd[i]),
						evidenceId[i],
						fragmentId[i],
						category[i],
						qual[i]
				));
			}
		}
		return support;
	}
	private static int maxReadLength(Collection<DirectedEvidence> support) {
		return support.stream()
				.mapToInt(e -> e instanceof NonReferenceReadPair ? ((NonReferenceReadPair)e).getNonReferenceRead().getReadLength() : ((SingleReadEvidence)e).getSAMRecord().getReadLength())
				.max()
				.orElse(0);
	}
	private static float strandBias(Collection<DirectedEvidence> support) {
		if (support.size() == 0) {
			return 0;
		}
		return (float)support.stream()
				.mapToDouble(e -> e.getStrandBias())
				.sum() / support.size();
	}
	private static int maxLocalMapq(Collection<DirectedEvidence> support) {
		return support.stream()
				.mapToInt(e -> e.getLocalMapq())
				.max()
				.orElse(0);
	}
	/**
	 * Annotates an assembly with summary information regarding the reads used to produce the assembly
	 */
	public static void annotateAssembly(ProcessingContext context, SAMRecord record, List<DirectedEvidence> support, List<AssemblyEvidenceSupport> aes) {
		if (support == null) {
			if (!MessageThrottler.Current.shouldSupress(log, "assemblies with no support")) {
				log.error("No support for assembly " + record.getReadName());
			}
			support = Collections.emptyList();
		}
		if (support.size() != aes.size()) {
			throw new IllegalArgumentException("support and aes sizes do not match");
		}
		byte[] type = new byte[support.size()];
		int[] category = new int[support.size()];
		int[] intervalStart = new int[support.size()];
		int[] intervalEnd = new int[support.size()];
		float[] qual = new float[support.size()];
		StringBuilder evidenceId = new StringBuilder();
		StringBuilder fragmentId = new StringBuilder();
		for (int i = 0; i < aes.size(); i++) {
			AssemblyEvidenceSupport s = aes.get(i);
			category[i] = s.getCategory();
			intervalStart[i] = s.getAssemblyContigOffset().lowerEndpoint();
			intervalEnd[i] = s.getAssemblyContigOffset().upperEndpoint();
			qual[i] = s.getQual();
			if (i != 0) {
				evidenceId.append(ID_COMPONENT_SEPARATOR);
				fragmentId.append(ID_COMPONENT_SEPARATOR);
			}
			evidenceId.append(s.getEvidenceID());
			evidenceId.append(s.getFragmentID());
		}
		ensureUniqueEvidenceID(record.getReadName(), support);
		record.setAttribute(SamTags.ASSEMBLY_EVIDENCE_TYPE, type);
		record.setAttribute(SamTags.ASSEMBLY_EVIDENCE_CATEGORY, category);
		record.setAttribute(SamTags.ASSEMBLY_EVIDENCE_EVIDENCEID, evidenceId.toString());
		record.setAttribute(SamTags.ASSEMBLY_EVIDENCE_FRAGMENTID, fragmentId.toString());
		record.setAttribute(SamTags.ASSEMBLY_EVIDENCE_OFFSET_START, intervalStart);
		record.setAttribute(SamTags.ASSEMBLY_EVIDENCE_OFFSET_END, intervalEnd);
		record.setAttribute(SamTags.ASSEMBLY_EVIDENCE_QUAL, qual);
		record.setAttribute(SamTags.ASSEMBLY_MAX_READ_LENGTH, maxReadLength(support));
		record.setAttribute(SamTags.ASSEMBLY_STRAND_BIAS, strandBias(support));
		// TODO: proper mapq model
		record.setMappingQuality(maxLocalMapq(support));
		if (record.getMappingQuality() < context.getConfig().minMapq) {
			if (!MessageThrottler.Current.shouldSupress(log, "below minimum mapq")) {
				log.warn(String.format("Sanity check failure: %s has mapq below minimum", record.getReadName()));
			}
		}
	}
	private static boolean ensureUniqueEvidenceID(String assemblyName, Collection<DirectedEvidence> support) {
		boolean isUnique = true;
		Set<String> map = new HashSet<String>();
		for (DirectedEvidence id : support) {
			if (map.contains(id.getEvidenceID())) {
				if (!MessageThrottler.Current.shouldSupress(log, "duplicated evidenceIDs")) {
					log.error("Found evidenceID " + id.getEvidenceID() + " multiple times in assembly " + assemblyName);
				}
				isUnique = false;
			}
			map.add(id.getEvidenceID());
		}
		return isUnique;
	}
	private Stream<AssemblyEvidenceSupport> filterSupport(Range<Integer> assemblyContigOffset, Set<Integer> supportingCategories, Set<AssemblyEvidenceSupport.SupportType> supportTypes) {
		Stream<AssemblyEvidenceSupport> stream = getSupport().stream();
		if (assemblyContigOffset != null) {
			stream = stream.filter(s -> s.getAssemblyContigOffset().isConnected(assemblyContigOffset));
		}
		if (supportingCategories != null) {
			stream = stream.filter(s -> supportingCategories.contains(s.getCategory()));
		}
		if (supportTypes != null) {
			stream = stream.filter(s -> supportTypes.contains(s.getSupportType()));
		}
		return stream;
	}
	public Collection<String> getEvidenceIDs(Range<Integer> assemblyContigOffset, Set<Integer> supportingCategories, Set<AssemblyEvidenceSupport.SupportType> supportTypes) {
		return filterSupport(assemblyContigOffset, supportingCategories, supportTypes)
				.map(s -> s.getEvidenceID())
				.collect(Collectors.toList());
	}
	public Set<String> getOriginatingFragmentID(Range<Integer> assemblyContigOffset, Set<Integer> supportingCategories, Set<AssemblyEvidenceSupport.SupportType> supportTypes) {
		return filterSupport(assemblyContigOffset, supportingCategories, supportTypes)
				.map(s -> s.getFragmentID())
				.collect(Collectors.toSet());
	}
	public int getMinQualPosition(Range<Integer> assemblyContigOffset, Set<Integer> supportingCategories, Set<AssemblyEvidenceSupport.SupportType> supportTypes) {
		if (assemblyContigOffset == null) {
			throw new NullPointerException("assemblyContigOffset is required.");
		}
		float best = getSupportingQualScore(assemblyContigOffset.lowerEndpoint(), supportingCategories, supportTypes);
		int bestPos = assemblyContigOffset.lowerEndpoint();
		for (int i = assemblyContigOffset.lowerEndpoint() + 1; i <= assemblyContigOffset.upperEndpoint(); i++) {
			float current = getSupportingQualScore(i, null, null);
			if (current > best) {
				best = current;
				bestPos = i;
			}
		}
		return bestPos;
	}
	public int getSupportingReadCount(int assemblyContigOffset, Set<Integer> supportingCategories, Set<AssemblyEvidenceSupport.SupportType> supportTypes) {
		return (int)filterSupport(Range.closed(assemblyContigOffset, assemblyContigOffset), supportingCategories, supportTypes).count();
	}
	public float getSupportingQualScore(int assemblyContigOffset, Set<Integer> supportingCategories, Set<AssemblyEvidenceSupport.SupportType> supportTypes) {
		return (float)filterSupport(Range.closed(assemblyContigOffset, assemblyContigOffset), supportingCategories, supportTypes).mapToDouble(s -> s.getQual()).sum();
	}
	public int getAssemblyMaxReadLength() {
		return record.getIntegerAttribute(SamTags.ASSEMBLY_MAX_READ_LENGTH);
	}
	public BreakendDirection getAssemblyDirection() {
		Character c = (Character)record.getAttribute(SamTags.ASSEMBLY_DIRECTION);
		if (c == null) return null;
		return BreakendDirection.fromChar(c);
	}
	public double getStrandBias() {
		return AttributeConverter.asDouble(record.getAttribute(SamTags.ASSEMBLY_STRAND_BIAS), 0);
	}
}
