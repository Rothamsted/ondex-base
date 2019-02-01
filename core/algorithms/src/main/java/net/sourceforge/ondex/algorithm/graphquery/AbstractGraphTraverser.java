package net.sourceforge.ondex.algorithm.graphquery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sourceforge.ondex.algorithm.graphquery.nodepath.EvidencePathNode;
import net.sourceforge.ondex.core.ONDEXConcept;
import net.sourceforge.ondex.core.ONDEXGraph;

/**
 * TODO: comment me!
 *
 * @author brandizi
 * <dl><dt>Date:</dt><dd>28 Jan 2019</dd></dl>
 *
 */
public abstract class AbstractGraphTraverser
{
	private Map<String, Object> options = new HashMap<> ();
	
  protected final Logger log = LoggerFactory.getLogger ( this.getClass () );
	
	
	public AbstractGraphTraverser ()
	{
		this ( null );
	}

	public AbstractGraphTraverser ( Map<String, Object> options )
	{
		super ();
		if ( options != null ) this.options = options;
	}
	

	@SuppressWarnings ( "rawtypes" )
	public abstract List<EvidencePathNode> traverseGraph ( 
		ONDEXGraph graph, ONDEXConcept concept, FilterPaths<EvidencePathNode> filter 
	);

	@SuppressWarnings ( "rawtypes" )
	public Map<ONDEXConcept, List<EvidencePathNode>> traverseGraph (
		ONDEXGraph graph, Set<ONDEXConcept> concepts, FilterPaths<EvidencePathNode> filter
	) 
	{
		return concepts.parallelStream ()
			.collect ( Collectors.toMap ( concept -> concept, concept -> traverseGraph ( graph, concept, filter ) ) );
	}

	
	public Map<String, Object> getOptions () {
		return options;
	}

	public void setOptions ( Map<String, Object> options ) {
		this.options = options;
	}
	
	public void setOption ( String key, Object value ) {
		this.options.put ( key, value );
	}

	@SuppressWarnings ( "unchecked" )
	public <V> V getOption ( String key, V defaultValue ) {
		return (V) this.options.getOrDefault ( key, defaultValue );
	}

	/** null as default value */
	@SuppressWarnings ( "unchecked" )	
	public <V> V getOption ( String key ) {
		return (V) this.options.get ( key );
	}
}