/*
 * Copyright (c) 2007-2009 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
 *
 * Cascading is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Cascading is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Cascading.  If not, see <http://www.gnu.org/licenses/>.
 */

package cascading.flow;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import cascading.operation.Operation;
import cascading.pipe.Group;
import cascading.pipe.Operator;
import cascading.pipe.Pipe;
import cascading.tap.Tap;
import cascading.tap.TempHfs;
import cascading.tap.hadoop.MultiInputFormat;
import cascading.tuple.Fields;
import cascading.tuple.IndexTuple;
import cascading.tuple.Tuple;
import cascading.tuple.TuplePair;
import cascading.tuple.hadoop.GroupingComparator;
import cascading.tuple.hadoop.GroupingPartitioner;
import cascading.tuple.hadoop.ReverseTupleComparator;
import cascading.tuple.hadoop.ReverseTuplePairComparator;
import cascading.tuple.hadoop.TupleComparator;
import cascading.tuple.hadoop.TuplePairComparator;
import cascading.tuple.hadoop.TupleSerialization;
import cascading.util.Util;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Job;
import org.apache.log4j.Logger;
import org.jgrapht.graph.SimpleDirectedGraph;

/**
 * Class FlowStep is an internal representation of a given Job to be executed on a remote cluster. During
 * planning, pipe assemblies are broken down into "steps" and encapsulated in this class.
 * <p/>
 * FlowSteps are submited in order of dependency. If two or more steps do not share the same dependencies and all
 * can be scheduled simultaneously, the {@link #getSubmitPriority()} value determines the order in which
 * all steps will be submitted for execution.
 */
public class FlowStep implements Serializable
  {
  /** Field LOG */
  private static final Logger LOG = Logger.getLogger( FlowStep.class );

  /** Field properties */
  private Map<Object, Object> properties = null;
  /** Field parentFlowName */
  private String parentFlowName;

  /** Field submitPriority */
  private int submitPriority = 5;

  /** Field name */
  final String name;
  /** Field id */
  private int id;
  /** Field graph */
  final SimpleDirectedGraph<FlowElement, Scope> graph = new SimpleDirectedGraph<FlowElement, Scope>( Scope.class );

  /** Field sources */
  final Map<Tap, String> sources = new HashMap<Tap, String>();   // all sources and all sinks must have same scheme
  /** Field sink */
  protected Tap sink;
  /** Field mapperTraps */
  public final Map<String, Tap> mapperTraps = new HashMap<String, Tap>();
  /** Field reducerTraps */
  public final Map<String, Tap> reducerTraps = new HashMap<String, Tap>();
  /** Field tempSink */
  //  TempHfs tempSink; // used if we need to bypass
  /** Field group */
  public Group group;

  protected FlowStep( String name, int id )
    {
    this.name = name;
    this.id = id;
    }

  /**
   * Method getName returns the name of this FlowStep object.
   *
   * @return the name (type String) of this FlowStep object.
   */
  public String getName()
    {
    return name;
    }

  /**
   * Method getParentFlowName returns the parentFlowName of this FlowStep object.
   *
   * @return the parentFlowName (type Flow) of this FlowStep object.
   */
  public String getParentFlowName()
    {
    return parentFlowName;
    }

  /**
   * Method setParentFlowName sets the parentFlowName of this FlowStep object.
   *
   * @param parentFlowName the parentFlowName of this FlowStep object.
   */
  public void setParentFlowName( String parentFlowName )
    {
    this.parentFlowName = parentFlowName;
    }

  /**
   * Method getStepName returns the stepName of this FlowStep object.
   *
   * @return the stepName (type String) of this FlowStep object.
   */
  public String getStepName()
    {
    return String.format( "%s[%s]", getParentFlowName(), getName() );
    }

  /**
   * Method getSubmitPriority returns the submitPriority of this FlowStep object.
   * <p/>
   * 10 is lowest, 1 is the highest, 5 is the default.
   *
   * @return the submitPriority (type int) of this FlowStep object.
   */
  public int getSubmitPriority()
    {
    return submitPriority;
    }

  /**
   * Method setSubmitPriority sets the submitPriority of this FlowStep object.
   * <p/>
   * 10 is lowest, 1 is the highest, 5 is the default.
   *
   * @param submitPriority the submitPriority of this FlowStep object.
   */
  public void setSubmitPriority( int submitPriority )
    {
    this.submitPriority = submitPriority;
    }

  /**
   * Method getProperties returns the properties of this FlowStep object.
   *
   * @return the properties (type Map<Object, Object>) of this FlowStep object.
   */
  public Map<Object, Object> getProperties()
    {
    if( properties == null )
      properties = new Properties();

    return properties;
    }

  /**
   * Method setProperties sets the properties of this FlowStep object.
   *
   * @param properties the properties of this FlowStep object.
   */
  public void setProperties( Map<Object, Object> properties )
    {
    this.properties = properties;
    }

  /**
   * Method hasProperties returns {@code true} if there are properties associated with this FlowStep.
   *
   * @return boolean
   */
  public boolean hasProperties()
    {
    return properties != null && !properties.isEmpty();
    }

  protected Job getJob() throws IOException
    {
    return getJob( null );
    }

  protected Job getJob( Configuration parentConf ) throws IOException
    {
    Job job = parentConf == null ? new Job() : new Job( parentConf );

    // set values first so they can't break things downstream
    if( hasProperties() )
      {
      for( Map.Entry entry : getProperties().entrySet() )
        job.getConfiguration().set( entry.getKey().toString(), entry.getValue().toString() );
      }

    job.setJobName( getStepName() );

    job.setOutputKeyClass( Tuple.class );
    job.setOutputValueClass( Tuple.class );

    job.setMapperClass( FlowMapper.class );
    job.setReducerClass( FlowReducer.class );

    // set for use by the shuffling phase
    TupleSerialization.setSerializations( job.getConfiguration() );

    initFromSources( job );

    initFromSink( job );

    initFromTraps( job );

    if( sink.getScheme().getNumSinkParts() != 0 )
      {
      // if no reducer, set num map tasks to control parts
      if( group != null )
        job.setNumReduceTasks( sink.getScheme().getNumSinkParts() );
      else
        job.getConfiguration().setInt( "mapred.map.tasks", sink.getScheme().getNumSinkParts() );
      }

    job.setSortComparatorClass( TupleComparator.class );

    if( group == null )
      {
      job.setNumReduceTasks( 0 ); // disable reducers
      }
    else
      {
      // must set map output defaults when performing a reduce
      job.setMapOutputKeyClass( Tuple.class );
      job.setMapOutputValueClass( Tuple.class );

      addComparators( job, "cascading.group.comparator", group.getGroupingSelectors() );

      if( group.isGroupBy() )
        addComparators( job, "cascading.sort.comparator", group.getSortingSelectors() );

      // handles the case the groupby sort should be reversed
      if( group.isSortReversed() )
        job.setSortComparatorClass( ReverseTupleComparator.class );

      if( !group.isGroupBy() )
        job.setMapOutputValueClass( IndexTuple.class );

      if( group.isSorted() )
        {
        job.setPartitionerClass( GroupingPartitioner.class );
        job.setMapOutputKeyClass( TuplePair.class );

        if( group.isSortReversed() )
          job.setSortComparatorClass( ReverseTuplePairComparator.class );
        else
          job.setSortComparatorClass( TuplePairComparator.class );

        // no need to supply a reverse comparator, only equality is checked
        job.setGroupingComparatorClass( GroupingComparator.class );
        }
      }

    // perform last so init above will pass to tasks
    job.getConfiguration().setInt( "cascading.flow.step.id", id );
    job.getConfiguration().set( "cascading.flow.step", Util.serializeBase64( this ) );

    return job;
    }

  private void addComparators( Job job, String property, Map<String, Fields> map ) throws IOException
    {
    Iterator<Fields> fieldsIterator = map.values().iterator();

    if( !fieldsIterator.hasNext() )
      return;

    Fields fields = fieldsIterator.next();

    if( fields.hasComparators() )
      job.getConfiguration().set( property, Util.serializeBase64( fields ) );

    return;
    }

  private void initFromTraps( Job job ) throws IOException
    {
    initFromTraps( job, mapperTraps );
    initFromTraps( job, reducerTraps );
    }

  private void initFromTraps( Job job, Map<String, Tap> traps ) throws IOException
    {
    if( !traps.isEmpty() )
      {
      Job trapJob = new Job( job.getConfiguration() );

      for( Tap tap : traps.values() )
        tap.sinkInit( trapJob );
      }
    }

  private void initFromSources( Job job ) throws IOException
    {
    Configuration[] fromJobs = new Configuration[sources.size()];
    int i = 0;

    for( Tap tap : sources.keySet() )
      {
      Job fromJob = new Job( job.getConfiguration() );
      fromJobs[ i ] = fromJob.getConfiguration();
      tap.sourceInit( fromJob );
      fromJobs[ i ].set( "cascading.step.source", Util.serializeBase64( tap ) );
      i++;
      }

    MultiInputFormat.addInputFormat( job, fromJobs );
    }

  private void initFromSink( Job conf ) throws IOException
    {
    // init sink first so tempSink can take precedence
    if( sink != null )
      sink.sinkInit( conf );
    }

  public Tap getMapperTrap( String name )
    {
    return mapperTraps.get( name );
    }

  public Tap getReducerTrap( String name )
    {
    return reducerTraps.get( name );
    }

  /**
   * Method getPreviousScopes returns the previous Scope instances. If the flowElement is a Group (specifically a CoGroup),
   * there will be more than one instance.
   *
   * @param flowElement of type FlowElement
   * @return Set<Scope>
   */
  public Set<Scope> getPreviousScopes( FlowElement flowElement )
    {
    return graph.incomingEdgesOf( flowElement );
    }

  /**
   * Method getNextScope returns the next Scope instance in the graph. There will always only be one next.
   *
   * @param flowElement of type FlowElement
   * @return Scope
   */
  public Scope getNextScope( FlowElement flowElement )
    {
    Set<Scope> set = graph.outgoingEdgesOf( flowElement );

    if( set.size() != 1 )
      throw new IllegalStateException( "should only be one scope after current flow element: " + flowElement + " found: " + set.size() );

    return set.iterator().next();
    }

  public Set<Scope> getNextScopes( FlowElement flowElement )
    {
    return graph.outgoingEdgesOf( flowElement );
    }

  public FlowElement getNextFlowElement( Scope scope )
    {
    return graph.getEdgeTarget( scope );
    }

  public String getSourceName( Tap source )
    {
    return sources.get( source );
    }

  public Collection<Operation> getAllOperations()
    {
    Set<FlowElement> vertices = graph.vertexSet();
    Set<Operation> operations = new HashSet<Operation>();

    for( FlowElement vertice : vertices )
      {
      if( vertice instanceof Operator )
        operations.add( ( (Operator) vertice ).getOperation() );
      }

    return operations;
    }

  public boolean containsPipeNamed( String pipeName )
    {
    Set<FlowElement> vertices = graph.vertexSet();

    for( FlowElement vertice : vertices )
      {
      if( vertice instanceof Pipe && ( (Pipe) vertice ).getName().equals( pipeName ) )
        return true;
      }

    return false;
    }

  /**
   * Method clean removes any temporary files used by this FlowStep instance. It will log any IOExceptions thrown.
   *
   * @param jobConf of type JobConf
   */
  public void clean( Configuration jobConf )
    {
    Job job;

    try
      {
      job = new Job( jobConf );
      }
    catch( IOException exception )
      {
      throw new FlowException( "unable to create tmp job" );
      }

    if( sink instanceof TempHfs )
      {
      try
        {
        sink.deletePath( job );
        }
      catch( IOException exception )
        {
        logWarn( "unable to remove temporary file: " + sink, exception );
        }
      }
    else
      {
      cleanTap( jobConf, sink );
      }

    for( Tap tap : mapperTraps.values() )
      cleanTap( jobConf, tap );

    for( Tap tap : reducerTraps.values() )
      cleanTap( jobConf, tap );

    }

  private void cleanTap( Configuration conf, Tap tap )
    {
//    try
//      {
//      Hadoop18TapUtil.cleanupTap( conf, tap );
//      }
//    catch( IOException exception )
//      {
    // ignore exception
//      }
    }

  @Override
  public boolean equals( Object object )
    {
    if( this == object )
      return true;
    if( object == null || getClass() != object.getClass() )
      return false;

    FlowStep flowStep = (FlowStep) object;

    if( name != null ? !name.equals( flowStep.name ) : flowStep.name != null )
      return false;

    return true;
    }

  @Override
  public int hashCode()
    {
    return name != null ? name.hashCode() : 0;
    }

  @Override
  public String toString()
    {
    StringBuffer buffer = new StringBuffer();

    buffer.append( getClass().getSimpleName() );
    buffer.append( "[name: " ).append( getName() ).append( "]" );

    return buffer.toString();
    }

  protected FlowStepJob createFlowStepJob( Configuration parentConf ) throws IOException
    {
    return new FlowStepJob( this, getName(), getJob( parentConf ) );
    }

  protected final boolean isInfoEnabled()
    {
    return LOG.isInfoEnabled();
    }

  protected final boolean isDebugEnabled()
    {
    return LOG.isDebugEnabled();
    }

  protected void logDebug( String message )
    {
    LOG.debug( "[" + Util.truncate( getParentFlowName(), 25 ) + "] " + message );
    }

  protected void logInfo( String message )
    {
    LOG.info( "[" + Util.truncate( getParentFlowName(), 25 ) + "] " + message );
    }

  protected void logWarn( String message )
    {
    LOG.warn( "[" + Util.truncate( getParentFlowName(), 25 ) + "] " + message );
    }

  protected void logWarn( String message, Throwable throwable )
    {
    LOG.warn( "[" + Util.truncate( getParentFlowName(), 25 ) + "] " + message, throwable );
    }

  protected void logError( String message, Throwable throwable )
    {
    LOG.error( "[" + Util.truncate( getParentFlowName(), 25 ) + "] " + message, throwable );
    }
  }
