# Threadwork Design Guide

## The manifesto

Threadwork is a topology-first application for designing, explaining,
implementing, testing, and generating software systems.

Its central design artifact is not a file tree. It is a typed network of
responsibilities:

```text
libraries define capabilities and algorithms
processing nodes employ those capabilities
types define the values crossing boundaries
links show data, errors, and dependencies
composites organize the design at multiple levels
```

The guide is the default way to think in Threadwork. It is guidance, not a
rigid law: a designer or developer may make an explicit exception when the
trade-off is understood and documented.

## What Threadwork makes visible

Threadwork supports a software process in which a person or coding agent can:

1. State the problem and its intended behavior.
2. Break the responsibility into specialized operations and failure domains.
3. Define the data and error contracts crossing those boundaries.
4. Sketch who calls whom, who uses what, and how results and failures travel.
5. Implement reusable algorithms in libraries and their application in
   processing nodes.
6. Attach specifications, examples, tests, and implementation text to the
   same model.
7. Validate, analyze, compile, generate documentation, and hand the design to
   another human or machine without losing its structure.

The topology is therefore an executable explanation of the system. Source
code is an implementation of the explanation, not a replacement for it.

## The core ontology

Threadwork uses one serializable node model with several semantic roles:

- A **processing node** is a small application boundary. It receives inputs,
  invokes a capability, and routes the operation outcome.
- A **library** is a reusable capability provider. It owns an algorithm,
  technical operation, or object/class implementation. It may expose ordinary
  functions, methods, or OOP-style implementation blocks.
- A **type** is a named contract for data crossing a wire. It can represent a
  record, class-like object, collection, map, or compiler-specific value.
- A **link** is a first-class relationship. It records who calls whom, who uses
  what, and which typed value or error crosses the boundary.
- A **composite** is a node whose internal topology can be opened when the
  next level of responsibility needs to be understood.

The model does not force functional programming or object-oriented
programming. It supports both: types and libraries can express classes and
methods, while the graph makes the main workflow and its boundaries visible.

## The totalized operation model

An operation has two semantically important outcomes:

```text
Operation<T, E> = Success(T) | Failure(E)
```

`T` is the principal result. `E` is the operation's declared error category.
The error is not an invisible second return channel. Threadwork makes it
explicit in the model, in the processing node contract, and in the topology.

### Library rule

A library function may be unable to perform its normal responsibility, but it
must remain in control of that outcome. It returns or records a typed error
instead of escaping through an unclassified exception, ambiguous sentinel, or
hidden side effect.

The library owns:

- the algorithm or technical operation;
- resource ownership and cleanup;
- low-level failure detection;
- conversion of those failures into its declared error family.

In C this can be represented by a result struct, an error enum plus output
parameters, or an equivalent explicit ABI. The representation may vary; the
semantic contract does not.

### Processing-node rule

A processing node should be so small that it does not introduce a new
business operation. It should:

1. receive the inputs;
2. invoke the library capability;
3. forward the principal result;
4. forward, classify, or route the operation error.

Wiring, configuration, and model errors still exist. They are design or
runtime diagnostics, not a reason to hide an additional business operation in
the node. If a node performs substantial validation, transformation, retry,
fallback, or policy, that behavior deserves its own explicit capability or
processing boundary.

## The decomposition rule

Decompose a problem by responsibility and failure domain, not by the number of
functions or files in an implementation.

A processing node normally has:

- one specialized responsibility;
- one principal result contract;
- one coherent error category;
- one clear policy for forwarding or handling that error.

Split a box when it combines unrelated failure points. Fetching data,
transforming it, and transmitting it may all be part of one user request, but
they generally have different algorithms, results, recovery policies, and
error families. They should usually be separate library capabilities and
processing nodes.

Do not split merely because an implementation contains several private helper
functions. Keep implementation detail inside the library when its intermediate
steps have no independent contract, failure route, or design significance.

The practical question is:

> If this operation fails, can the caller name one coherent error family and
> one responsible capability?

If not, the box is probably hiding multiple responsibilities.

## Three layers of meaning

Threadwork separates three concerns that are often mixed in ordinary source
code:

### Business definition

The library defines what an operation means and how it is performed. This is
where reusable algorithms, class methods, parsing, file I/O, aggregation,
serialization, and resource management belong.

### Business employment

The processing node chooses when and with which inputs the operation is used.
It binds ports, parameters, and types; it invokes the library; and it exposes
the operation's result and error to the rest of the design.

### Business topology

The network shows the larger responsibility breakdown. Its links express data
flow, error flow, capability use, dependency, and communication. The topology
answers:

```text
who calls whom?
who uses what?
what data is produced?
what can go wrong?
where does that error go?
what happens after it is handled?
```

## Design directives

These are the normal defaults for a Threadwork design:

1. **Specialisation** — give each library and processing node one meaningful
   capability or role.
2. **Single responsibility** — keep one principal result and one coherent
   error family at a processing boundary.
3. **Explicit outcomes** — model unsuccessful operations as typed results,
   errors, and links rather than hidden control flow.
4. **Definition/employment separation** — keep reusable algorithms in
   libraries and their use in processing nodes.
5. **Typed boundaries** — use shared Type entities for values that cross a
   meaningful wire or capability boundary.
6. **Visible topology** — use links to show data, errors, calls, dependencies,
   and recovery paths.
7. **Recursive clarity** — keep a capability as one box at the current level,
   but open it into a composite when its internal responsibilities or failure
   domains must be understood.
8. **No silent loss** — every expected result and expected error should be
   forwarded, handled, or deliberately terminated.

These directives are design pressure, not an automatic prohibition. A single
transaction, adapter, or compiler primitive may intentionally contain several
internal steps when they share one result, one failure family, and one recovery
policy.

## A design-agent checklist

Before implementing a workflow, an agent should be able to answer:

- What is the principal result of each processing node?
- What is its single coherent error category?
- Which library defines the operation being employed?
- Which Type entities describe the data and error contracts?
- Which links carry success data, errors, or capabilities?
- Where does every expected error go?
- Is any node secretly performing more than one operation?
- Can the design be understood without reading the implementation first?

The guide should make the intended architecture obvious to a human designer
and to a coding agent, while leaving room for informed engineering judgment.

## Relationship to the repository

This document is the canonical design guide. The MCP agent guide is its
machine-facing delivery copy with additional protocol and mutation rules. The
README and specifications summarize or specialize this guide; they should not
contradict it.
