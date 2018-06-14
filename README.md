# ctx

A `Ctx` represents the context in which an operation occurs. It is used as a share abstraction to tunnel
information between libraries or components without those libraries or components needing to be aware
of what is being tunneled. It is a practical hack, and care should be used to avoid using it except where
it is needed. Examples of context propagation include request ids, timeouts, and shared cancellations.

Ctx is heavily influenced by Golang's `context` package and is *very* similar to 
[GRPC's Context](https://grpc.io/grpc-java/javadoc/io/grpc/Context.html), which it predates.

# Using `Ctx`

## Timeouts and Cancellations

The most common application level use of Ctx is to handle high level timeouts and cancellations. Cancellation allows
a coordinated cancellation of arbitrary downstream operations. For example, if there are three concurrent API calls
being made, and they must ALL succeed for any to succeed, the cancellation functionality can be used when one fails
to trigger cancellation on the other two. To accomplish this, you will want to create a child context of the
current context, then pass that child context to the operations which you want to provide coordinated cancellation for.

Timeouts are just a cancellation on a timer. Because it is the most common coordinated cancellation, direct support
exists in Ctx to support it. After the timeout has elapsed, the context will be cancelled.

## Ctx Lifecycle

A Ctx is `alive` or `cancelled`. By default, a ctx is `alive`. if it is cancelled, it becomes cancelled. Once a context
has been cancelled, it remains cancelled.

Multiple contexts can share the same liveness, and transition at the same time. A context derived from another by adding 
a value to it is a "peer" to the context from which it was derived. A context created via `createChild` is a child. Peer 
contexts share the same liveness, and cancellation propagates to child contexts, but not up to parent contexts. This 
mechanism allows for hierarchical cancellation, and for contexts which are manipulated after being created to not lose 
their coordinated liveness.

If a context with the same values, but an independent lifecycle is needed, this can be created via `Ctx#newRoot()`.

## Thread Attachment

Because one of the key purposes of a context is to tunnel information between libraries, contexts support
being attached to threads. The correct way to use this is to call `Ctx#attach()` to attach a particular
context to the current thread, then `Ctx#detach()` to later detach it. 

## Propagating Context

Because context often needs to propagate across threads we have convenience methods to assist with this. `Ctx` has
a static method to wrap an `ExecutorService` such that any context bound to a thread submitting a job will be
propagated onto the thread executing the job.

Similarly, there are instance methods to wrap `Runnable` and `Callable` instances so that they will have that specific
context bound to the thread they are eventually executed on. These wrappers restore any pre-existing context
to the thread after execution.

## Server Side

- Propagate context at the earliest reasonable point from incoming requests into a `Ctx`.
- Prefer passing `Ctx` explicitely rather than infecting the current thread.
- If explicit is not possible, infect the current thread and document that this happens!

## Clients

- Propagate context onto outgoing requests at the latest reasonable point.
- Prefer to receive `Ctx` explicitly rather than via attachment.
- Set request timeouts (via a deadline) on downstream calls based on SLA or time remaining, whichever is lower.
- Hook into CANCEL lifecycle hook to free up resources and abort early when appropriate.

# Features Missing Right Now

## Data Serialization

Require data be serializable to Map<String, String> for external propagation. This needs to be managed carefully so we 
don't wind up propagating too much, if folks take to abusing `Ctx` to be quasi-dynamic scoping.

In order to support dumb data elements, we probably want to support serializers registered on the context or globally, 
as well as serialization-aware data types on keys. This allows a custom type to decide for itself how to serialize, and 
built in types to make use of registered serializers.

Alternately, `jackson-datatype-ctx` though that seems overkill :-)

# Possible Changes

## Propagation of Dynamic Timeouts

If a deadline is set on a request, we should pass that information downstream so that the target of an RPC can make use 
of that information to optimize their timeouts.

Once this mechanism is determined, possibly just an `Timeout` header, consider detecting the header and scheduling 
cancellation appropriately at time of RPC receipt.

## Plugin Data

Expose lifecycle events to keys -- this makes data into fully lifecycle aware plugin type things. It would allow 
deadline and lifecycle to be plugins (if plugins could interact). Right now this seems to be over-eager generalization, 
but it might be useful if we find a third thing that would make use of it. Going down this path implies keys might only 
be types, not name and type, as they currently are.
