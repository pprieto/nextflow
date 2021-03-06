package nextflow.extension
import static java.util.Arrays.asList

import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.Dataflow
import groovyx.gpars.dataflow.DataflowQueue
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.DataflowVariable
import groovyx.gpars.dataflow.DataflowWriteChannel
import groovyx.gpars.dataflow.expression.DataflowExpression
import groovyx.gpars.dataflow.operator.ChainWithClosure
import groovyx.gpars.dataflow.operator.DataflowEventAdapter
import groovyx.gpars.dataflow.operator.DataflowEventListener
import groovyx.gpars.dataflow.operator.DataflowProcessor
import nextflow.Channel
import nextflow.Global
import nextflow.Session
import nextflow.dag.NodeMarker

/**
 * This class provides helper methods to implement nextflow operators
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
class DataflowHelper {

    private static Session getSession() { Global.getSession() as Session }

    /**
     * Check if a {@code DataflowProcessor} is active
     *
     * @param operator A {@code DataflowProcessor} instance
     * @return {@code true} when the operator is still active or {@code false} otherwise eg. it received a poison pill
     */
    static boolean isProcessorActive( DataflowProcessor operator ) {
        def clazz = operator.class
        while( clazz != DataflowProcessor )
            clazz = operator.class.superclass
        def field = clazz.getDeclaredField('actor')
        field.setAccessible(true)
        def actor = field.get(operator)
        def method = actor.getClass().getMethod('isActive')
        return method.invoke(actor)
    }

    /*
     * The default operators listener when no other else is specified
     */
    @PackageScope
    static DEF_ERROR_LISTENER = new DataflowEventAdapter() {
        @Override
        public boolean onException(final DataflowProcessor processor, final Throwable e) {
            DataflowExtensions.log.error("@unknown", e)
            session.abort(e)
            return true;
        }
    }

    /**
     * Creates a new {@code Dataflow.operator} adding the created instance to the current session list
     *
     * @see nextflow.Session#allOperators
     *
     * @param params The map holding inputs, outputs channels and other parameters
     * @param code The closure to be executed by the operator
     */
    @PackageScope
    static DataflowProcessor newOperator( Map params, Closure code ) {

        // -- add a default error listener
        if( !params.containsKey('listeners') ) {
            // add the default error handler
            params.listeners = [ DEF_ERROR_LISTENER ]
        }

        final op = Dataflow.operator(params, code)
        NodeMarker.appendOperator(op)
        if( session && session.allOperators != null ) {
            session.allOperators.add(op)
        }

        return op
    }

    /**
     * Creates a new {@code Dataflow.operator} adding the created instance to the current session list
     *
     * @see nextflow.Session#allOperators
     *
     * @param inputs The list of the input {@code DataflowReadChannel}s
     * @param outputs The list of list output {@code DataflowWriteChannel}s
     * @param code The closure to be executed by the operator
     */
    @PackageScope
    static DataflowProcessor newOperator( List inputs, List outputs, Closure code ) {
        newOperator( inputs: inputs, outputs: outputs, code )
    }

    /**
     * Creates a new {@code Dataflow.operator} adding the created instance to the current session list
     *
     * @see nextflow.Session#allOperators
     *
     * @param input An instance of {@code DataflowReadChannel} representing the input channel
     * @param output An instance of {@code DataflowWriteChannel} representing the output channel
     * @param code The closure to be executed by the operator
     */
    @PackageScope
    static DataflowProcessor newOperator( DataflowReadChannel input, DataflowWriteChannel output, Closure code ) {
        newOperator(input, output, DEF_ERROR_LISTENER, code )
    }

    /**
     * Creates a new {@code Dataflow.operator} adding the created instance to the current session list
     *
     * @see nextflow.Session#allOperators
     *
     * @param input An instance of {@code DataflowReadChannel} representing the input channel
     * @param output An instance of {@code DataflowWriteChannel} representing the output channel
     * @param listener An instance of {@code DataflowEventListener} listening to operator's events
     * @param code The closure to be executed by the operator
     */
    @PackageScope
    static DataflowProcessor newOperator( DataflowReadChannel input, DataflowWriteChannel output, DataflowEventListener listener, Closure code ) {

        if( !listener )
            listener = DEF_ERROR_LISTENER

        def params = [:]
        params.inputs = [input]
        params.outputs = [output]
        params.listeners = [listener]

        final op = Dataflow.operator(params, code)
        NodeMarker.appendOperator(op)
        if( session && session.allOperators != null ) {
            session.allOperators << op
        }
        return op
    }

    /*
     * the list of valid subscription handlers
     */
    static private VALID_HANDLERS = [ 'onNext', 'onComplete', 'onError' ]

    /**
     * Verify that the map contains only valid names of subscribe handlers.
     * Throws an {@code IllegalArgumentException} when an invalid name is specified
     *
     * @param handlers The handlers map
     */
    @PackageScope
    static checkSubscribeHandlers( Map handlers ) {

        if( !handlers ) {
            throw new IllegalArgumentException("You must specify at least an event between: onNext, onComplete, onError")
        }

        handlers.keySet().each {
            if( !VALID_HANDLERS.contains(it) )  throw new IllegalArgumentException("Not a valid handler name: $it")
        }

    }

    /**
     * Subscribe *onNext*, *onError* and *onComplete*
     *
     * @param source
     * @param closure
     * @return
     */
    static public final <V> DataflowProcessor subscribeImpl(final DataflowReadChannel<V> source, final Map<String,Closure> events ) {
        checkSubscribeHandlers(events)

        def error = false
        def stopOnFirst = source instanceof DataflowExpression
        def listener = new DataflowEventAdapter() {

            @Override
            public void afterStop(final DataflowProcessor processor) {
                if( !events.onComplete || error ) return
                try {
                    events.onComplete.call(processor)
                }
                catch( Exception e ) {
                    DataflowExtensions.log.error("@unknown", e)
                    session.abort(e)
                }
            }

            @Override
            public boolean onException(final DataflowProcessor processor, final Throwable e) {
                error = true
                if( !events.onError ) {
                    log.error("@unknown", e)
                    session.abort(e)
                }
                else {
                    events.onError.call(e)
                }
                return true
            }
        }


        final Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("inputs", [source])
        parameters.put("outputs", [])
        parameters.put('listeners', [listener])

        newOperator(parameters) {
            if( events.onNext ) {
                events.onNext.call(it)
            }
            if( stopOnFirst ) {
                ((DataflowProcessor) getDelegate()).terminate()
            }
        }
    }


    static <V> DataflowProcessor chainImpl(final DataflowReadChannel<?> source, final DataflowReadChannel<V> target, final Map params, final Closure<V> closure) {

        final Map<String, Object> parameters = new HashMap<String, Object>(params)
        parameters.put("inputs", asList(source))
        parameters.put("outputs", asList(target))

        newOperator(parameters, new ChainWithClosure<V>(closure))
    }

    /**
     * Implements the {@code #reduce} operator
     *
     * @param channel
     * @param seed
     * @param closure
     * @return
     */
    static <V> DataflowProcessor reduceImpl(final DataflowReadChannel<?> channel, final DataflowVariable result, def seed, final Closure<V> closure) {

        // the *accumulator* value
        def accum = seed

        // intercepts operator events
        def listener = new DataflowEventAdapter() {
            /*
             * call the passed closure each time
             */
            public void afterRun(final DataflowProcessor processor, final List<Object> messages) {
                final item = messages.get(0)
                final value = accum == null ? item : closure.call(accum, item)

                if( value == Channel.VOID ) {
                    // do nothing
                }
                else if( value == Channel.STOP ) {
                    processor.terminate()
                }
                else {
                    accum = value
                }
            }

            /*
             * when terminates bind the result value
             */
            public void afterStop(final DataflowProcessor processor) {
                result.bind(accum)
            }

            public boolean onException(final DataflowProcessor processor, final Throwable e) {
                log.error("@unknown", e)
                session.abort(e)
                return true;
            }
        }

        chainImpl(channel, new DataflowQueue(), [listeners: [listener]], {true})
    }


}
