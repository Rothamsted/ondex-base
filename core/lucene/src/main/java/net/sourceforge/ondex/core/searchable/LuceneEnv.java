package net.sourceforge.ondex.core.searchable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.machinezoo.noexception.Exceptions;
import net.sourceforge.ondex.core.Attribute;
import net.sourceforge.ondex.core.AttributeName;
import net.sourceforge.ondex.core.ConceptAccession;
import net.sourceforge.ondex.core.ConceptName;
import net.sourceforge.ondex.core.ONDEXConcept;
import net.sourceforge.ondex.core.ONDEXEntity;
import net.sourceforge.ondex.core.ONDEXGraph;
import net.sourceforge.ondex.core.ONDEXRelation;
import net.sourceforge.ondex.core.util.BitSetFunctions;
import net.sourceforge.ondex.event.ONDEXEvent;
import net.sourceforge.ondex.event.ONDEXListener;
import net.sourceforge.ondex.event.type.DataFileErrorEvent;
import net.sourceforge.ondex.event.type.EventType;
import net.sourceforge.ondex.event.type.EventType.Level;
import net.sourceforge.ondex.event.type.GeneralOutputEvent;
import net.sourceforge.ondex.exception.type.AccessDeniedException;
import uk.ac.ebi.utils.exceptions.ExceptionUtils;
import uk.ac.ebi.utils.runcontrol.PercentProgressLogger;

/**
 * This class idxSearcher the entry point for the indexed ONDEX graph representation. It
 * initialises the LUCENE Index system.
 * 
 * @author taubertj, reviewed in 2017, 2019 by brandizi (mainly to migrate to Lucene 6.x)
 */
public class LuceneEnv implements ONDEXLuceneFields 
{

	/**
	 * Collects Bits from a given LUCENE field and adds them to a BitSet.
	 * 
	 * Marco Brandizi: these collectors were adapted from Lucene 3.6 to 6, maybe we don't need collectors and
	 * simply search/check idxSearcher enough.
	 * 
	 * See Lucene docs for details.
	 * 
	 * @author hindlem, taubertj
	 */
	private class DocIdCollector extends SimpleCollector 
	{
		private final BitSet bits;
		protected int docBase;

		public DocIdCollector ( IndexReader indexReader ) {
			bits = new BitSet ( indexReader.maxDoc() );
		}


		@Override
		public void collect ( int doc ) {
			bits.set ( doc + docBase );
		}

		public BitSet getBits() {
			return bits;
		}
		
		@Override
		protected void doSetNextReader ( LeafReaderContext context ) throws IOException
		{
			this.docBase = context.docBase;
		}

		@Override
		public boolean needsScores () {
			return false;
		}
	}

	/**
	 * Collects documents and their scores from a search.
	 * 
	 * @see DocIdCollector
	 * 
	 * @author hindlem, brandizi
	 */
	private class ScoreCollector extends DocIdCollector 
	{
		private Scorer scorer;

		/**
		 * Collects scores for each doc.
		 */
		private final Map<Integer, Float> docScores;

		public ScoreCollector ( IndexReader indexReader ) 
		{
			super ( indexReader );
			docScores = new HashMap<> ( indexReader.maxDoc() );
		}

		@Override
		public void collect ( int doc )
		{
			try {
				super.collect ( doc );
				docScores.put ( doc + docBase, scorer.score() );
			}
			catch ( IOException ex ) {
				throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
			}
		}

		/**
		 * @return a map going from do IDs to scores.
		 */
		public Map<Integer, Float> getScores() {
			return docScores;
		}

		@Override
		public void setScorer ( Scorer scorer ) {
			this.scorer = scorer;
		}
		
		@Override
		public boolean needsScores () {
			return true;
		}		
	}
	
	/**
	 * true if the class was instantiated with the instruction to create/replace a new index.
	 */
	private boolean createNewIndex;

	/**
	 * The index directory handler required by Lucene 
	 */
	private Directory idxDirectory;

	/**
	 * Lucene index writer
	 */
	private IndexWriter idxWriter;

	/**
	 * The index directory location. This idxSearcher used for logging and alike.
	 */
	private String indexDirPath = "";

	/**
	 * index searcher
	 */
	private IndexSearcher idxSearcher;
	
	/**
	 * index reader
	 */
	private IndexReader idxReader;

	/**
	 * contains all registered listeners
	 */
	private final Set<ONDEXListener> listeners = new HashSet<>();

	/**
	 * wrapped LUCENE ONDEX graph
	 */
	private LuceneONDEXGraph og = null;

	// contains all used DataSources for concept accessions
	private Set<String> listOfConceptAccDataSources = new HashSet<>();

	// contains all used attribute names for concepts
	private Set<String> listOfConceptAttrNames = new HashSet<>();

	// contains all used attribute names for relations
	private Set<String> listOfRelationAttrNames = new HashSet<>();
	
	/**
	 * global analyser used for the index
	 */
	public final static Analyzer DEFAULTANALYZER = new StandardAnalyzer();

	/** A marker used to mark a metadata document. */
	private static final String LASTDOCUMENT_FIELD = "LASTDOCUMENT_FIELD";

	/**
	 * remove double spaces
	 */
	private final static Pattern DOUBLESPACECHARS = Pattern.compile("\\s{2,}");

	/**
	 * empty BitSet for empty results
	 */
	private final static BitSet EMPTYBITSET = new BitSet(0);

	private final static Map<Integer, Float> EMPTYSCOREMAP = new HashMap<>( 0 );

	/* Options to reflect these names are set in the class initialising block, see below */
	private final static FieldType FIELD_TYPE_STORED_INDEXED_NO_NORMS = new FieldType ( TextField.TYPE_STORED );
	private final static FieldType FIELD_TYPE_STORED_INDEXED_VECT_STORE = new FieldType ( TextField.TYPE_STORED );
	private final static FieldType FIELD_TYPE_STORED_INDEXED_UNCHANGED = new FieldType ( StringField.TYPE_STORED );


	/**
	 * threaded indexing of graph
	 */
	private static final ExecutorService EXECUTOR;

	/**
	 * Allows only the id of a document to be loaded
	 */
	private static Set<String> ID_FIELDS = new HashSet<> ( Arrays.asList ( CONID_FIELD, RELID_FIELD ) ); 

	// pre-compiled patterns for text stripping
	private final static Pattern patternNonWordChars = Pattern.compile("\\W");

	/**
	 * This idxSearcher the RAM allocated for Lucene buffering, during indexing operation. It idxSearcher expressed as a fraction of
	 * Runtime.getRuntime ().maxMemory (). 
	 * 
	 */
	private final static double RAM_BUFFER_QUOTA = 0.1;
	
	/** 
	 * <p>Establishes a lock when opening/closing index objects. They are thread-safe per se, but we keep their
	 * opening/closing consistent.</p>
	 * 
	 * <p>This is managed by openXXX/closeXXX methods, using the 
	 * <a href = "https://www.geeksforgeeks.org/java-singleton-design-pattern-practices-examples">double-check lazy init</a>.</p> 
	 *
	 */
	private ReentrantReadWriteLock indexDemarcationgLock = new ReentrantReadWriteLock ();

	private Logger log = LoggerFactory.getLogger ( this.getClass () );
	
	static
	{
		{ 
			FieldType f =  FIELD_TYPE_STORED_INDEXED_NO_NORMS;
			f.setOmitNorms ( true );
			f.freeze ();
		}
		
		{
			FieldType f = FIELD_TYPE_STORED_INDEXED_VECT_STORE;
			f.setStoreTermVectors ( true );
			f.freeze ();
		}
		{
			FieldType f = FIELD_TYPE_STORED_INDEXED_UNCHANGED;
			f.setIndexOptions ( IndexOptions.DOCS_AND_FREQS_AND_POSITIONS );
			f.setOmitNorms ( true );
			f.freeze ();
		}
				
		EXECUTOR = Executors.newCachedThreadPool ();
		Runtime.getRuntime ().addShutdownHook ( 
			new Thread ( 
				() -> { if ( EXECUTOR != null ) EXECUTOR.shutdownNow (); } 
			) 
		); 
	}
		
	
	
	/**
	 * Optimises the RAM to be used for Lucene indexes, based on {@link #RAM_BUFFER_QUOTA} and
	 * max memory available.
	 */
	private static long getOptimalRamBufferSize ()
	{
		return Math.round ( RAM_BUFFER_QUOTA * Runtime.getRuntime ().maxMemory () / ( 1 << 20 ) );		
	}
	
	/**
	 * Strips text to be inserted into the index.
	 * 
	 * @param text
	 *            String
	 * @return String
	 */
	public static String stripText(String text) 
	{
		// trim and lower case
		text = text.trim().toLowerCase();

		// replace all non-word characters with space
		text = patternNonWordChars.matcher(text).replaceAll(SPACE);

		// replace double spaces by single ones
		text = DOUBLESPACECHARS.matcher(text).replaceAll(SPACE);

		return text;
	}

	
	/**
	 * Constructor which initialises an empty LuceneEnv.
	 * 
	 * @param indexDirPath
	 *            String
	 * @param createNewIndex
	 *            boolean
	 */
	public LuceneEnv(String indexDirPath, boolean create) 
	{
		this.indexDirPath = indexDirPath;
		this.createNewIndex = create;

		if (create) new File(indexDirPath).mkdirs();
	}

	/**
	 * Adds a ONDEX listener to the list.
	 * 
	 * @param l
	 *            ONDEXListener
	 */
	public void addONDEXListener(ONDEXListener l) {
		listeners.add(l);
	}

	/**
	 * Close all open index handles.
	 */
	public void closeAll () 
	{
		WriteLock writeLock = this.indexDemarcationgLock.writeLock ();
		writeLock.lock ();
		try {
			this.closeIdxWriter ();
		}
		finally {
			writeLock.unlock ();
		}
	}

	/**
	 * Flushes and commits a potentially open index writer.
	 */
	public void closeIdxWriter () 
	{
		// Double-check lazy access
		if ( this.idxWriter == null ) return;
		
		WriteLock writeLock = this.indexDemarcationgLock.writeLock ();
		writeLock.lock ();
		
		try 
		{
			// Double-check lazy access
			if ( this.idxWriter == null ) return;

			// add last document to index
			addMetadataToIndex ();

			this.idxWriter.commit ();
			this.idxWriter.close ();
			this.idxWriter = null;
			
			this.closeIdxReader (); // Just to be sure it's re-read
		} 
		catch (IOException ex) {
			fireEventOccurred ( new DataFileErrorEvent ( ex.getMessage(), "[LucenceEnv - closeIndex]" ) );
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
		} 
		finally {
			writeLock.unlock ();
		}
	}

	/**
	 * @param cid
	 *            the concept id to check for
	 * @return if one or more indexes of this concept exist in the index
	 */
	public boolean conceptExistsInIndex(int cid) 
	{
		DocIdCollector collector = null;
		try {
			openIdxReader ();
			collector = new DocIdCollector(idxSearcher.getIndexReader());
			idxSearcher.search( new TermQuery(new Term(CONID_FIELD, String.valueOf(cid))), collector);
		} 
		catch (IOException ex) {
			throw new UncheckedIOException ( 
				String.format ( "I/O error while doing conceptExistsInIndex(%s): %s", cid, ex.getMessage () ), 
				ex 
			);
		}
		return collector.getBits().length() > 0;
	}

	/**
	 * Typical usage get the number of Publications (Concept) with Abstracts
	 * (Attribute) that contain the word "regulates". Return the number of
	 * Concepts that contain this word.
	 * 
	 * @param an
	 *            the Attribute attribute to search within
	 * @param word
	 *            the word/term to search for
	 * @return int
	 */
	public int getFrequenceyOfWordInConceptAttribute(AttributeName an, String word) 
	{
		String fieldname = CONATTRIBUTE_FIELD + DELIM + an.getId();
		Term term = new Term(fieldname, word);
		try {
			this.openIdxReader ();
			return idxReader.docFreq( term );
		} 
		catch (IOException ex) 
		{
			fireEventOccurred(
				new DataFileErrorEvent(ex.getMessage(), "[LuceneEnv - getFrequenceyOfWordInConceptAttribute]")
			);
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
		}
	}

	/**
	 * Faster method than getFrequenceyOfWordInConceptAttribute(AttributeName
	 * an, String word) as calls to IO are less (-: Typical usage get the number
	 * of Publications (Concept) with Abstracts (Attribute) that contain the
	 * word "regulates". Returns the number of Concepts that contain this word.
	 * 
	 * @param an
	 *            the Attribute attribute to search within
	 * @param word
	 *            the word/term to search for
	 * @return int[]
	 */
	public int[] getFrequenceyOfWordInConceptAttribute(AttributeName an, String[] word) 
	{
		String fieldname = CONATTRIBUTE_FIELD + DELIM + an.getId();

		try {
			this.openIdxReader ();			
			int[] freqs = new int[word.length];
			for (int i = 0; i < word.length; i++) {
				freqs[i] = idxReader.docFreq ( new Term ( fieldname, word[i] ) );
			}

			// Returns the number of documents containing the terms.
			return freqs;
		} 
		catch (IOException ex) 
		{
			fireEventOccurred(
				new DataFileErrorEvent(ex.getMessage(), "[LuceneEnv - getFrequenceyOfWordInConceptAttribute]")
			);
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
		}
	}

	/**
	 * Returns the number of Relations that contain this word.
	 * 
	 * @param an
	 *            the Attribute attribute to search within
	 * @param word
	 *            the word/term to search for
	 * @return int
	 */
	public int getFrequenceyOfWordInRelationAttribute(AttributeName an, String word)
	{
		String fieldname = RELATTRIBUTE_FIELD + DELIM + an.getId();
		Term term = new Term(fieldname, word);
		try {
			// Returns the number of documents containing the term.
			this.openIdxReader ();						
			return idxReader.docFreq ( term );
		} 
		catch (IOException ex) {
			fireEventOccurred ( 
				new DataFileErrorEvent(ex.getMessage(), "[LuceneEnv - getFrequenceyOfWordInRelationAttribute]") 
			);
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
			
		}
	}

	/**
	 * Returns the number of Relations that contain these words.
	 * 
	 * @param an
	 *            the Attribute attribute to search within
	 * @param word
	 *            the word/term to search for
	 * @return int[]
	 */
	public int[] getFrequenceyOfWordInRelationAttribute(AttributeName an, String[] word) 
	{
		String fieldname = RELATTRIBUTE_FIELD + DELIM + an.getId();

		try {
			this.openIdxReader ();
			int[] freqs = new int[word.length];
			for (int i = 0; i < word.length; i++) {
				freqs[i] = idxReader.docFreq ( new Term(fieldname, word[i]) );
			}

			// Returns the number of documents containing the terms.
			return freqs;
		}
		catch (IOException ex) {
			fireEventOccurred(
				new DataFileErrorEvent(ex.getMessage(), "[LuceneEnv - getFrequenceyOfWordInRelationAttribute]")
			);
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
		}
	}

	public Set<String> getListOfConceptAccDataSources() {
		return listOfConceptAccDataSources;
	}

	public Set<String> getListOfConceptAttrNames() {
		return listOfConceptAttrNames;
	}

	public Set<String> getListOfRelationAttrNames() {
		return listOfRelationAttrNames;
	}

	/**
	 * Returns the actual LuceneONDEXGraph as an AbstractONDEXGraph.
	 * 
	 * @return AbstractONDEXGraph
	 */
	public ONDEXGraph getONDEXGraph() {
		return this.og;
	}

	/**
	 * Returns the list of ONDEX listener listeners.
	 * 
	 * @return ONDEXListener[]
	 */
	public ONDEXListener[] getONDEXListeners() {
		return listeners.toArray(new ONDEXListener[listeners.size()]);
	}

	/**
	 * Open index for writing.
	 */
	public void openIdxWriter ()
	{
		// Double-check lazy init, see above
		if ( this.idxWriter != null ) return;

		WriteLock writeLock = this.indexDemarcationgLock.writeLock ();
		writeLock.lock ();
		
		try
		{
			// Double-check lazy init, see above
			if ( this.idxWriter != null ) return;
			
			// Just in case it's not flushed
			this.closeIdxReader ();
			
			
			this.idxDirectory = FSDirectory.open ( new File ( indexDirPath ).toPath () );
			
			IndexWriterConfig writerConfig = new IndexWriterConfig ( DEFAULTANALYZER );
			writerConfig.setOpenMode ( OpenMode.CREATE_OR_APPEND );
			// set RAM buffer, hopefully speeds up things
			writerConfig.setRAMBufferSizeMB ( getOptimalRamBufferSize () );

			this.idxWriter = new IndexWriter ( idxDirectory, writerConfig );
			
			// deletes the last record that has attribute names,
			// that will have to be rebuilt
			idxWriter.deleteDocuments ( new Term ( LASTDOCUMENT_FIELD, "true" ) );
			idxWriter.commit ();
			log.info ( "Index opened, old metadata deletions: ", idxWriter.hasDeletions () );
		}
		catch ( IOException ex ) {
			fireEventOccurred ( new DataFileErrorEvent ( ex.getMessage (), "[LucenceEnv - openIndex]" ) );
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
		}
		finally {
			writeLock.unlock ();
		}
	}
	
	
	/**
	 * This was added by brandizi in 2017, to ensure {@link #idxReader} and {@link #idxSearcher} are open, before starting using them
	 * in an operation. 
	 */
	private void openIdxReader () throws IOException
	{
		ReadLock readLock = this.indexDemarcationgLock.readLock ();
		readLock.lock ();
		try
		{
			if ( this.idxDirectory == null )
				this.idxDirectory = FSDirectory.open ( new File ( indexDirPath ).toPath () );
	
			DirectoryReader newReader = null;
			
			if ( this.idxReader == null )
				newReader = DirectoryReader.open ( this.idxDirectory );
			else
			{
				try {
					newReader = DirectoryReader.openIfChanged ( (DirectoryReader) idxReader );
				}
				catch ( AlreadyClosedException ex ) {
					// It seems there isn't a method to know if the reader was already closed, apart from a listener, which
					// would be a bit more cumbersome here and not so useful
					newReader = DirectoryReader.open ( idxDirectory );
				}
			}
			if ( newReader != null )
			{
				if ( this.idxReader != null ) this.idxReader.close ();
				this.idxReader = newReader;
				this.idxSearcher = new IndexSearcher ( this.idxReader );
			}
		}
		finally {
			readLock.unlock ();
		}		
	}	
	
	/**
	 * This is normally  not needed, except by {@link #openIdxWriter()}, which invokes it to invalidated the current
	 * reader objects and ensure they're refreshed.
	 */
	private void closeIdxReader () throws IOException
	{
		ReadLock readLock = this.indexDemarcationgLock.readLock ();
		readLock.lock ();
		
		try
		{
			if ( this.idxReader != null ) {
				this.idxReader.close ();
				this.idxReader = null;
			}
			this.idxSearcher = null;
			if ( this.idxDirectory != null ) {
				this.idxDirectory.close ();
				this.idxDirectory = null;
			}
		}
		finally {
			readLock.unlock ();
		}
	}
	
	
	/**
	 * <p>Execute an action, which presumably will do something with the current {@link LuceneEnv}, and 
	 * then invokes {@link #closeAll()}.</p>
	 * 
	 * <p>This is some syntactic sugar that avoids try/finally.</p>
	 *  
	 */
	public <T> T wrapIdxFunction ( Supplier<T> operation )
	{
		try {
			return operation.get ();
		}
		finally {
			this.closeAll ();
		}
	}

	/**
	 * Variant of {@link #wrapIdxFunction(Supplier)}.
	 */
	public void wrapIdxOperation ( Runnable operation ) {
		this.wrapIdxFunction ( () -> { operation.run (); return null; });
	}

	
	/**
	 * @param rid
	 *            the relation id to check for
	 * @return if one or more indexes of this concept exist in the index
	 */
	public boolean relationExistsInIndex ( int rid )
	{
		DocIdCollector collector = null;
		try 
		{
			this.openIdxReader ();
			collector = new DocIdCollector ( idxSearcher.getIndexReader () );
			idxSearcher.search ( new TermQuery ( new Term ( RELID_FIELD, String.valueOf ( rid ) ) ), collector );
		}
		catch ( IOException ex ) {
			throw new UncheckedIOException ( 
				String.format ( "I/O error while doing relationExistsInIndex(%s): %s", rid, ex.getMessage () ), 
				ex 
			);
		}
		return ( collector.getBits ().length () > 0 );
	}

	/**
	 * Removes the selected concept from the index NB this idxSearcher an expensive
	 * operation where possible group deletes together and @see
	 * removeConceptsFromIndex(int[] cids)
	 * 
	 * @param cid
	 *            the conceptId to remove from the index
	 * @return sucess?
	 */
	public boolean removeConceptFromIndex(int cid) {
		return removeConceptsFromIndex(new int[] { cid });
	}
	
	/**
	 * Removes the selected concepts from the index
	 * 
	 * @param cids
	 *            the conceptIds to remove from the index
	 * @return the number of concepts removed
	 */
	public boolean removeConceptsFromIndex(int[] cids) 
	{
		return updadeIndex ( () -> 
		{
			Query[] terms = new Query[cids.length];
			for (int i = 0; i < terms.length; i++) {
				terms[i] = new TermQuery(new Term(CONID_FIELD,
						String.valueOf(cids[i])));
				log.trace ( "Term removed:", terms[i].toString());
			}
			Exceptions.sneak ().get (	() -> idxWriter.deleteDocuments ( terms ) );
			return idxWriter.hasDeletions();			
		});
	}

	/**
	 * Removes a ONDEX listener listener from the list.
	 * 
	 * @param l
	 *            ONDEXListener
	 */
	public void removeONDEXListener(ONDEXListener l) {
		listeners.remove(l);
	}

	/**
	 * Removes the selected relation from the index NB this idxSearcher an expensive
	 * operation where possible group deletes together and @see
	 * removeRelationsFromIndex(int[] rids)
	 * 
	 * @param rid
	 *            the relationId to remove from the index
	 * @return success?
	 */
	public boolean removeRelationFromIndex(int rid) {
		return removeRelationsFromIndex(new int[] { rid });
	}

	/**
	 * Removes the selected relations from the index
	 * 
	 * @param rids
	 *            the relationIds to remove from the index
	 * @return the number of relations removed
	 */
	public boolean removeRelationsFromIndex(int[] rids) 
	{
		return updadeIndex ( () -> 
		{
			Term[] terms = new Term[rids.length];
			for (int i = 0; i < terms.length; i++) {
				terms[i] = new Term(RELID_FIELD, String.valueOf(rids[i]));
			}
			Exceptions.sneak ().get ( () -> idxWriter.deleteDocuments(terms) );
			return idxWriter.hasDeletions();
		});		
	}

	/**
	 * TODO: comment me!
	 */
	private <E extends ONDEXEntity> ScoredHits<E> searchScoredEntity ( 
		Query q, String field, Class<E> returnValueClass 
	)
	{
		try
		{
			openIdxReader ();
			ScoreCollector collector = new ScoreCollector ( idxSearcher.getIndexReader () );
			idxSearcher.search ( q, collector );
			Set<E> view;
			Map<Integer, Float> doc2Scores = collector.getScores ();
			Map<Integer, Float> id2scores = new HashMap<> ();
			BitSet bs = collector.getBits ();
			if ( bs.length () > 0 )
			{
				BitSet set = new BitSet ( bs.length () );
				// iterator of document indices
				for ( int i = bs.nextSetBit ( 0 ); i >= 0; i = bs.nextSetBit ( i + 1 ) )
				{
					Document document = idxSearcher.doc ( i, ID_FIELDS );
					float score = doc2Scores.get ( i );
					int entityId = Optional.ofNullable ( document.get ( field ) )
						.map ( Integer::valueOf )
						.orElseThrow ( () -> new NullPointerException ( String.format ( 
							"Internal error: for some reason I have a null ID for the Lucene field: \"%s\" and the query \"%s\"", 
							field, 
							q.toString () 
						)));
					id2scores.put ( entityId, score );
					set.set ( entityId );
				}
				view = BitSetFunctions.create ( og, returnValueClass, set );
				return new ScoredHits<> ( view, id2scores );
			} 
			else
			{
				view = BitSetFunctions.create ( og, returnValueClass, EMPTYBITSET );
				return new ScoredHits<> ( view, EMPTYSCOREMAP );
			}
		}
		catch ( IOException ex ) {
			fireEventOccurred ( new DataFileErrorEvent ( ex.getMessage (), "[LuceneEnv - searchScoredEntity]" ) );
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
		}
	}
	
	
	/**
	 * Searches in Concepts and returns found Concepts.
	 * 
	 * @param q
	 *            Query
	 * @return ScoredHits<ONDEXConcept>
	 */
	public ScoredHits<ONDEXConcept> scoredSearchInConcepts(Query q)  {
		return searchScoredEntity ( q, CONID_FIELD, ONDEXConcept.class );
	}

	/**
	 * Searches in Relations and returns found Relations.
	 * 
	 * @param q
	 *            Query
	 * @return ScoredHits<ONDEXRelation>
	 */
	public ScoredHits<ONDEXRelation> scoredSearchInRelations(Query q) {
		return searchScoredEntity ( q, RELID_FIELD, ONDEXRelation.class );
	}

	
	/**
	 * Searches an {@link ONDEXEntity}, by first searching the entity in Lucene, via q, and then picking it from the
	 * {@link #getONDEXGraph() managed Ondex graph}, using the Lucene's ID field named 'field'.
	 * 
	 */
	private <E extends ONDEXEntity> Set<E> searchEntity ( Query q, String field, Class<E> returnValueClass )
	{
		try
		{
			this.openIdxReader ();
			DocIdCollector collector = new DocIdCollector ( idxSearcher.getIndexReader () );
			idxSearcher.search ( q, collector );

			BitSet bs = collector.getBits ();
			if ( bs.length () == 0 ) return BitSetFunctions.create ( og, returnValueClass, EMPTYBITSET );

			BitSet set = new BitSet ( bs.length () );
			// iterator of document indices
			for ( int i = bs.nextSetBit ( 0 ); i >= 0; i = bs.nextSetBit ( i + 1 ) )
			{
				// retrieve associated document
				Document document = idxSearcher.doc ( i, ID_FIELDS );
				// get concept ID from document
				
				int entityId = Optional.ofNullable ( document.get ( field ) )
					.map ( Integer::valueOf )
					.orElseThrow ( () -> new NullPointerException ( String.format ( 
						"Internal error: for some reason I have a null ID for the Lucene field: \"%s\" and the query \"%s\"", 
						q.toString (), 
						field 
					)));
				
				set.set ( entityId );
			}
			return BitSetFunctions.create ( og, returnValueClass, set );
		}
		catch ( IOException ex ) {
			fireEventOccurred ( new DataFileErrorEvent ( ex.getMessage (), "[LuceneEnv - searchEntity]" ) );
			log.error ( "Error while searching: " + q + " over field " + field + ": " + ex.getMessage (), ex );
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );			
		}
	}
	
	
	
	/**
	 * Searches in Concepts and returns found Concepts.
	 * 
	 * @param q
	 *            Query
	 * @return Set<ONDEXConcept>
	 */
	public Set<ONDEXConcept> searchInConcepts(Query q) {
		return searchEntity ( q, CONID_FIELD, ONDEXConcept.class );
	}

	/**
	 * Searches in Relations and returns found Relations.
	 * 
	 * @param q
	 *            Query
	 * @return Set<ONDEXRelation>
	 */
	public Set<ONDEXRelation> searchInRelations(Query q) {
		return searchEntity ( q, RELID_FIELD, ONDEXRelation.class );
	}

	/**
	 * Base method to get a single concept or relation by using the 'iri' attribute.  
	 */
	private <E extends ONDEXEntity> E getEntityByIRI ( String iri, Function<Query, Set<E>> searcher )
	{
		try
		{
			openIdxReader ();
			PhraseQuery q = new PhraseQuery ( "iri", iri );
			Set<E> results = searcher.apply ( q );
			if ( results == null ) return null;
			int size = results.size ();
			if ( size == 0 ) return null;
			E result = results.iterator ().next ();
			if ( size > 1 ) log.warn ( 
				"I've found {} instances of {} for the URI '{}'", size, result.getClass ().getSimpleName (), iri 
			);
			return result;
		}
		catch ( IOException ex )
		{
			throw ExceptionUtils.buildEx ( 
				UncheckedIOException.class, ex, "Error while using lucene to lookup for <%s>: %s", iri, ex.getMessage () 
			);
		} 
	}
	
	/**
	 * These IRI-based fetching methods are used with the new architecture, to bridge graph databases and in-memory Ondex
	 * graphs.
	 */
	public ONDEXConcept getConceptByIRI ( String iri ) {
		return getEntityByIRI ( iri, this::searchInConcepts );
	}
	
	/**
	 * These IRI-based fetching methods are used with the new architecture, to bridge graph databases and in-memory Ondex
	 * graphs.
	 */
	public ONDEXRelation getRelationByIRI ( String iri ) {
		return getEntityByIRI ( iri, this::searchInRelations );
	}
	
	
	private <E extends ONDEXEntity> ScoredHits<E> searchScoredEntity ( 
		Query q, String field, Class<E> returnedValueClass, int limit 
	)
	{
		try
		{
			this.openIdxReader ();
			final BitSet bits = new BitSet ();
			TopDocs hits = idxSearcher.search ( q, limit );
			Map<Integer, Float> scores = new HashMap<> ();
			for ( int i = 0; i < hits.scoreDocs.length; i++ )
			{
				int docId = hits.scoreDocs[ i ].doc;
				float score = hits.scoreDocs[ i ].score;
				Document document = idxSearcher.doc ( docId, ID_FIELDS );
								
				Integer entityId = Optional.ofNullable ( document.get ( field ) )
					.map ( Integer::valueOf )
					.orElse ( null );
				
				if ( entityId != null ) {
					bits.set ( entityId );
					scores.put ( entityId, score );
				}
			}

			Set<E> view = BitSetFunctions.create ( og, returnedValueClass, bits );
			return new ScoredHits<> ( view, scores );
		}
		catch ( IOException ex ) {
			fireEventOccurred ( new DataFileErrorEvent ( ex.getMessage (), "[LuceneEnv - searchScoredEntity]" ) );
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
		}
	}
	
	
	
	/**
	 * Searches the top n hits for query in Concepts.
	 * 
	 * @param q
	 *            Query
	 * @param n
	 *            int
	 * @return ScoredHits<ONDEXConcept>
	 */
	public ScoredHits<ONDEXConcept> searchTopConcepts(Query q, int n) {
		return searchScoredEntity ( q, CONID_FIELD, ONDEXConcept.class, n );
	}

	/**
	 * Searches the top n hits for query in Relations.
	 * 
	 * @param q
	 *            Query
	 * @param n
	 *            int
	 * @return ScoredHits<ONDEXRelation>
	 */
	public ScoredHits<ONDEXRelation> searchTopRelations(Query q, int n) {
		return searchScoredEntity ( q, RELID_FIELD, ONDEXRelation.class, n );
	}

	/**
	 * Takes a given AbstractONDEXGraph and builds the index around it.
	 * 
	 * @param aog
	 *            AbstractONDEXGraph
	 * @throws AccessDeniedException
	 */
	public void setONDEXGraph(ONDEXGraph aog) throws AccessDeniedException {

		GeneralOutputEvent so = new GeneralOutputEvent(
			"Using Lucene with index dir: " + this.indexDirPath,
			"[LuceneEnv - setONDEXGraph]",
			Level.INFO
		);
		fireEventOccurred(so);
		
		// store an immutable version of the graph
		this.og = new LuceneONDEXGraph(aog);

		// start indexing
		indexONDEXGraph( aog );
	}

	/**
	 * Updates or adds new concepts to the index
	 * 
	 * @param concepts
	 *            the relations to add to the index
	 * @throws AccessDeniedException
	 */
	public void updateConceptsToIndex(Set<ONDEXConcept> concepts) throws AccessDeniedException 
	{
		updateIndexVoid ( () -> 
		{
			for ( ONDEXConcept concept : concepts )
			{
				// try a delete this idxSearcher quicker than reopening the "idxSearcher"
				Exceptions.sneak ().run ( 
					() -> idxWriter.deleteDocuments ( new Term ( CONID_FIELD, String.valueOf ( concept.getId () ) ) ) 
				);
				addConceptToIndex ( concept );
			}
		});		
	}

	/**
	 * Update or add a concept to the index NB this idxSearcher an expensive operations,
	 * so try to use the batch job @see updateConceptsToIndex(Set<ONDEXConcept>
	 * concepts)
	 * 
	 * @param concept
	 *            the concept to add to the index
	 * @throws AccessDeniedException
	 */
	public void updateConceptToIndex ( ONDEXConcept concept ) throws AccessDeniedException
	{
		updateIndexVoid ( () -> 
		{
			boolean exists = conceptExistsInIndex ( concept.getId () );
			if ( exists ) Exceptions.sneak ().run ( 
				() -> idxWriter.deleteDocuments ( new Term ( CONID_FIELD, String.valueOf ( concept.getId () ) ) ) 
			);
			addConceptToIndex ( concept );
		});
	}

	/**
	 * Updates or adds new relations to the index
	 * 
	 * @param relations
	 *            the relations to add to the index
	 * @throws AccessDeniedException
	 */
	public void updateRelationsToIndex ( Set<ONDEXRelation> relations ) throws AccessDeniedException
	{
		updateIndexVoid ( () ->
		{
			for ( ONDEXRelation relation : relations )
			{
				Exceptions.sneak ().run ( () -> 
					idxWriter.deleteDocuments ( new Term ( RELID_FIELD, String.valueOf ( relation.getId () ) ) )
				);
				addRelationToIndex ( relation );
			}
		});
	}

	/**
	 * Updates or adds a relations to an Index NB this idxSearcher an expensive
	 * operations, so try to use the batch job @see
	 * updateRelationsToIndex(Set<ONDEXRelation> relations)
	 * 
	 * @param relation
	 *            ondex relation to add to the index
	 * @throws AccessDeniedException
	 */
	public void updateRelationToIndex ( ONDEXRelation relation ) throws AccessDeniedException
	{
		updateIndexVoid ( () -> 
		{
			boolean exists = relationExistsInIndex ( relation.getId() );
			if ( exists )
				Exceptions.sneak ().run ( () ->
					idxWriter.deleteDocuments ( new Term ( RELID_FIELD, String.valueOf ( relation.getId () ) ) )				
				);
			addRelationToIndex ( relation );
		});
	}

	private Document getCommonFields ( ONDEXEntity e )
	{
		AttributeName iriAttrType = this.og.getMetaData ().getAttributeName ( "iri" );
		
		String iri = iriAttrType == null 
			? null 
			: Optional.ofNullable ( e.getAttribute ( iriAttrType ) )
				.map ( attr -> (String) attr.getValue () )
				.orElse ( null );
		
		Document doc = new Document ();
		if ( iri != null ) doc.add ( new Field ( "iri", iri, FIELD_TYPE_STORED_INDEXED_UNCHANGED ) );
		
		return doc;
	}
	
	
	/**
	 * Add the given ONDEXConcept to the current index.
	 * 
	 * @param c
	 *            ONDEXConcept to add to index
	 * @throws AccessDeniedException
	 */
	private void addConceptToIndex(ONDEXConcept c) throws AccessDeniedException 
	{
		// ensures duplicates are not written to the Index
		Set<String> cacheSet = new HashSet<> ( 100 );

		// get textual properties
		String conceptID = String.valueOf ( c.getId () );
		String parserID = c.getPID ();
		String annotation = c.getAnnotation ();
		String description = c.getDescription ();

		// get all properties iterators
		Set<ConceptAccession> it_ca = c.getConceptAccessions ();
		if ( it_ca.size () == 0 )
		{
			it_ca = null;
		}
		Set<ConceptName> it_cn = c.getConceptNames ();
		if ( it_cn.size () == 0 )
		{
			it_cn = null;
		}
		Set<Attribute> it_attribute = c.getAttributes ();
		if ( it_attribute.size () == 0 )
		{
			it_attribute = null;
		}

		// leave if there are no properties
		if ( it_ca == null && it_cn == null && it_attribute == null && annotation.length () == 0
				&& description.length () == 0 )
		{
			return; // there idxSearcher nothing to index, no document should be created!
		}

		// createNewIndex a new document for each concept and sets fields
		Document doc = this.getCommonFields ( c );
		
		doc.add ( new Field ( CONID_FIELD, conceptID, FIELD_TYPE_STORED_INDEXED_UNCHANGED ) );
		doc.add ( new Field ( PID_FIELD, parserID, StringField.TYPE_STORED ) );
		
		doc.add ( new Field ( CC_FIELD, c.getOfType ().getId (), FIELD_TYPE_STORED_INDEXED_UNCHANGED ) );
		doc.add ( new Field ( DataSource_FIELD, c.getElementOf ().getId (), FIELD_TYPE_STORED_INDEXED_UNCHANGED ) );

		if ( annotation.length () > 0 )
			doc.add ( new Field ( ANNO_FIELD, LuceneEnv.stripText ( annotation ), FIELD_TYPE_STORED_INDEXED_VECT_STORE ) );

		if ( description.length () > 0 )
			doc.add ( new Field ( DESC_FIELD, LuceneEnv.stripText ( description ), FIELD_TYPE_STORED_INDEXED_VECT_STORE ) );

		// start concept accession handling
		if ( it_ca != null )
		{

			// add all concept accessions for this concept
			for ( ConceptAccession ca : it_ca )
			{
				String accession = ca.getAccession ();
				String elementOf = ca.getElementOf ().getId ();
				Boolean isAmbiguous = ca.isAmbiguous ();
				listOfConceptAccDataSources.add ( elementOf );

				String id = CONACC_FIELD + DELIM + elementOf;

				if ( isAmbiguous )
				{
					id = id + DELIM + AMBIGUOUS;
				}
				// concept accessions should not be ANALYZED?
				doc.add (
					new Field ( id, LuceneEnv.stripText ( accession ), FIELD_TYPE_STORED_INDEXED_VECT_STORE ) 
				);
			}
		}

		// start concept name handling
		if ( it_cn != null )
		{

			// add all concept names for this concept
			for ( ConceptName cn : it_cn )
			{
				String name = cn.getName ();
				cacheSet.add ( LuceneEnv.stripText ( name ) );
			}

			// exclude completely equal concept names from being
			// represented twice
			for ( String aCacheSet : cacheSet )
			{
				doc.add ( new Field ( CONNAME_FIELD, aCacheSet, FIELD_TYPE_STORED_INDEXED_VECT_STORE ) );
			}
			cacheSet.clear ();
		}

		// start concept gds processing
		if ( it_attribute != null )
		{
			// mapping attribute name to gds value
			Map<String, String> attrNames = new HashMap<> ();

			// add all concept gds for this concept
			for ( Attribute attribute : it_attribute )
			{
				if ( attribute.isDoIndex () )
				{
					String name = attribute.getOfType ().getId ();
					listOfConceptAttrNames.add ( name );
					String value = attribute.getValue ().toString ();
					attrNames.put ( name, LuceneEnv.stripText ( value ) );
				}
			}

			// write attribute name specific Attribute fields
			attrNames.forEach ( ( name, value ) -> 
				doc.add ( new Field ( CONATTRIBUTE_FIELD + DELIM + name, value, FIELD_TYPE_STORED_INDEXED_VECT_STORE ) )
			);
			
			attrNames.clear ();
		}

		// store document to index
		try
		{
			idxWriter.addDocument ( doc );
		}
		catch ( IOException ex ) {
			fireEventOccurred ( new DataFileErrorEvent ( ex.getMessage (), "[LuceneEnv - addConceptToIndex]" ) );
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
		}
	}

	/**
	 * Adds sets of used meta data to the index.
	 * WARNING: this assumes the index is already {@link #openIdxWriter() opened}. 
	 */
	private void addMetadataToIndex() {

		// new document for fields
		Document doc = new Document();
		doc.add(new Field(LASTDOCUMENT_FIELD, "true", StringField.TYPE_NOT_STORED ));
		// Attribute fields about the last document were initially not stored. However, this idxSearcher not good for Lucene 6,
		// because it complaints that a field name having both docs where it idxSearcher stored and not stored cannot be used to 
		// build certain searches (https://goo.gl/Ee1sfm)
		//
		for (String name : listOfConceptAttrNames)
			doc.add(new Field(CONATTRIBUTE_FIELD + DELIM + name, name,	FIELD_TYPE_STORED_INDEXED_VECT_STORE ));
		for (String name : listOfRelationAttrNames)
			doc.add(new Field(RELATTRIBUTE_FIELD + DELIM + name, name,	FIELD_TYPE_STORED_INDEXED_VECT_STORE ));
		for (String elementOf : listOfConceptAccDataSources)
			doc.add(new Field(CONACC_FIELD + DELIM + elementOf, elementOf, FIELD_TYPE_STORED_INDEXED_VECT_STORE ));

		// add last document
		try {
			this.idxWriter.addDocument(doc);
		} 
		catch (IOException ex) {
			fireEventOccurred(new DataFileErrorEvent(ex.getMessage(), "[LuceneEnv - addMetadataToIndex]"));
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
		}
	}

	/**
	 * Add the given ONDEXRelation to the current index.
	 * WARNING: this assumes the index is already {@link #openIdxWriter() opened}. 
	 */
	private void addRelationToIndex ( ONDEXRelation r ) throws AccessDeniedException
	{
		// get Relation and RelationAttributes
		Set<Attribute> it_attribute = r.getAttributes ();

		// leave if there idxSearcher nothing to index
		if ( it_attribute.size () == 0 ) return;

		// createNewIndex a Document for each relation and store ids
		Document doc = this.getCommonFields ( r );

		doc.add ( new Field ( RELID_FIELD, String.valueOf ( r.getId () ), FIELD_TYPE_STORED_INDEXED_UNCHANGED ) );
		doc.add ( new Field ( FROM_FIELD, String.valueOf ( r.getFromConcept ().getId () ), FIELD_TYPE_STORED_INDEXED_UNCHANGED ) );
		doc.add ( new Field ( TO_FIELD, String.valueOf ( r.getToConcept ().getId () ), FIELD_TYPE_STORED_INDEXED_UNCHANGED ) );
		doc.add ( new Field ( OFTYPE_FIELD, r.getOfType ().getId (), StringField.TYPE_STORED ) );

		// mapping attribute name to gds value
		Map<String, String> attrNames = new HashMap<> ();

		// add all relation gds for this relation
		for ( Attribute attribute : it_attribute )
		{
			if ( attribute.isDoIndex () )
			{
				String name = attribute.getOfType ().getId ();
				listOfRelationAttrNames.add ( name );
				String value = attribute.getValue ().toString ();
				attrNames.put ( name, LuceneEnv.stripText ( value ) );
			}
		}

		// write attribute name specific Attribute fields
		for ( String name : attrNames.keySet () )
		{
			String value = attrNames.get ( name );
			doc.add ( new Field ( RELATTRIBUTE_FIELD + DELIM + name, value, FIELD_TYPE_STORED_INDEXED_VECT_STORE ) );
		}
		
		attrNames.clear ();

		// store document to index
		try {
			idxWriter.addDocument ( doc );
		}
		catch ( IOException ex ) {
			fireEventOccurred ( new DataFileErrorEvent ( ex.getMessage (), "[LuceneEnv - addRelationToIndex]" ) );
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
		}
	}

	/**
	 * Builds the index for a given ONDEXGraph.
	 */
	private void indexONDEXGraph(final ONDEXGraph graph ) throws AccessDeniedException {

		fireEventOccurred(
			new GeneralOutputEvent(	"Starting the Lucene environment.", "[LuceneEnv - indexONDEXGraph]")
		);

		try 
		{
			// if index idxSearcher new created, fill index
			if ( this.createNewIndex ) 
			{
				// Just in case
				this.closeIdxWriter();
				this.openIdxWriter ();
				
				try
				{
					indexingHelper ( graph.getConcepts (), "concept", this::addConceptToIndex );
					indexingHelper ( graph.getRelations (), "relation", this::addRelationToIndex );
	
					// last Document contains meta data lists
					addMetadataToIndex();
					idxWriter.commit();
				}
				finally {
					this.closeIdxWriter ();
				}
				return;
			} // if ( createNewIndex )

			// if !createNewIndex, read some general data from existing index
			this.openIdxReader ();
			try
			{
				// read in attribute names and accession DataSources
				Document doc = idxReader.document ( idxReader.maxDoc() - 1 );
				for (Object o : doc.getFields()) 
				{
					Field field = (Field) o;
					String name = field.name();
					if (name.startsWith(CONATTRIBUTE_FIELD + DELIM)) {
						listOfConceptAttrNames.add(field.stringValue());
					} else if (name.startsWith(RELATTRIBUTE_FIELD + DELIM)) {
						listOfRelationAttrNames.add(field.stringValue());
					} else if (name.startsWith(CONACC_FIELD + DELIM)) {
						listOfConceptAccDataSources.add(field.stringValue());
					}
				}
			}
			finally {
				// this.closeIdxReader (); // TODO: Not sure it's needed, possibly remove (MB, 2019-10-07)
			}
		} 
		catch (IOException ex) {
			fireEventOccurred(new DataFileErrorEvent(ex.getMessage(), "[LucenceEnv - indexONDEXGraph]"));
			throw new UncheckedIOException ( "Internal error while working with Lucene: " + ex.getMessage (), ex );
		}
	}

	/**
	 * Little helper used by {@link #indexONDEXGraph(ONDEXGraph)} to factorise the submission to the index of 
	 * concepts and relations.
	 * 
	 * It indexes a set of concepts or relations, by means of {@link #addConceptToIndex(ONDEXConcept)} or
	 * {@link #addRelationToIndex(ONDEXRelation)}. {@code label} should be "concept" or "relation" and idxSearcher
	 * used for logging.
	 */
	private <OE extends ONDEXEntity> void indexingHelper ( Set<OE> inputs, String label, Consumer<OE> indexSubmitter )
		throws IOException
	{		
		final int sz = inputs.size ();
		log.info ( "Start indexing the Ondex Graph, {} {}(s) sent to index", sz, label );
			
		PercentProgressLogger progressLogger = new PercentProgressLogger ( 
			"{}% of " + label + "s submitted to index", sz 
		);
		progressLogger.appendProgressReportAction ( 
			Exceptions.sneak ().fromBiConsumer ( (oldp, newp) -> this.idxWriter.commit () )
		);
		
		// TODO: the use of a parallel task manager comes from existing code, what's the point of it?!
		Future<?> task = EXECUTOR.submit ( 
			() -> {
				for ( OE entity : inputs )
				{
					indexSubmitter.accept ( entity );
					progressLogger.updateWithIncrement ();
				}
			}
		);
		
		try {
			task.get();
			this.idxWriter.commit ();
		} 
		catch ( InterruptedException|ExecutionException ex ) {
			throw new RuntimeException ( "Error while indexing Ondex Graph:" + ex.getMessage (), ex );
		}
	}
	
	/**
	 * Notify all listeners that have registered with this class.
	 * 
	 * TODO: In most cases, it should be replaced by exception wrapping/rethrowing and then there 
	 * should be a top-level reporter, dealing with the GUI, as currently this does. 
	 * 
	 * @param e
	 *            type of event
	 */
	private void fireEventOccurred(EventType e) 
	{
		if (listeners.size() > 0) {
			// new ondex event
			ONDEXEvent oe = new ONDEXEvent(this, e);
			// notify all listeners

			for (ONDEXListener listener : listeners) {
				listener.eventOccurred(oe);
			}
		}
	}
	
	/**
	 * Utility that factorises an index updating operation, wrapping it into common pre/post processing, like opening the
	 * index, reopening it at the end.
	 *  
	 * @param action what to do, assuming it will return a result.
	 */
	private <R> R updadeIndex ( Supplier<R> action )
	{
		openIdxWriter ();
		
		try {
			return action.get ();
		}	
		finally {
			closeIdxWriter ();
		}		
	}
	
	/**
	 * Like {@link #updadeIndex(Supplier)}, but doesn't return anything, so it's easier to use this in those lambdas that
	 * don't have anything to return.
	 */
	private void updateIndexVoid ( Runnable action ) {
		this.updadeIndex ( () -> { action.run (); return null; } );
	}

}
