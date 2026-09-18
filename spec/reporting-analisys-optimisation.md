Implement a comprehensive graph-analysis system in **Threadwork** for the existing processing-network model.

## Context

Threadwork models executable processing networks composed primarily of:

* **Processing nodes**

  * participate in runtime data flow;
  * consume and/or produce data;
  * may have self-links;
  * may emit errors;
  * may depend on library nodes.

* **Library nodes**

  * provide reusable functionality to processing nodes;
  * normally participate through dependency relationships rather than runtime data flow.

The graph is directed.

A direct link from a node to itself is legal.

Self-links are relatively uncommon but meaningful. In the principal-data graph they can represent explicit state/memorization/feedback behavior.

The analysis UI already has or will have an **Analysis tab**. Extend this tab with structured analysis sections described below.

The analysis system must not merely calculate metrics. Its output should follow this hierarchy:

1. **Indicators**
2. **Qualitative analysis**
3. **Recommendations**
4. **Available actions**

Actions may be:

* informational/manual;
* navigation/selection actions;
* model transformations;
* automatic layout/reordering operations.

Actions must never silently alter model semantics.

---

# 1. Graph representation

Represent the Threadwork network as three separate Boolean directed adjacency matrices sharing exactly the same node index mapping.

Given nodes:

$$
V=\{v_0,\dots,v_{n-1}\}
$$

construct:

### Principal data matrix

$$
P_{ij}=1
$$

iff a principal/normal data link exists from node `i` to node `j`.

### Error data matrix

$$
E_{ij}=1
$$

iff an error-data link exists from node `i` to node `j`.

### Dependency matrix

$$
D_{ij}=1
$$

iff node `i` depends on node `j`.

For Threadwork, dependency edges will normally represent processing nodes using library nodes, although the implementation should not unnecessarily assume this is the only legal dependency topology.

All three matrices are directed.

Diagonal values are meaningful and MUST NOT be discarded:

```text
P[i][i]
E[i][i]
D[i][i]
```

Self-links must participate in appropriate analysis.

Also derive a non-semantic structural matrix:

$$
U=P\lor E\lor D
$$

`U` is not another link type.

It means only:

> some relationship exists between these two nodes.

Use it where useful for general clustering, connectedness and structural layout.

Keep the matrix/index abstraction separate from the UI and from the underlying Threadwork model so other algorithms can reuse it.

---

# 2. Analysis architecture

Implement analysis as modular analyzers rather than one monolithic procedure.

Suggested conceptual structure:

```text
GraphSnapshot
    nodes
    principalMatrix
    errorMatrix
    dependencyMatrix
    structuralMatrix

AnalysisEngine
    PrincipalFlowAnalyzer
    ErrorFlowAnalyzer
    DependencyAnalyzer
    CrossLayerAnalyzer
    SubsystemAnalyzer
    QualityAnalyzer
```

Exact names should follow the existing Threadwork architecture and conventions.

Do not duplicate graph traversal implementations across analyzers.

Create reusable graph utilities for things such as:

* incoming edges;
* outgoing edges;
* degree;
* reachability;
* transitive closure or equivalent reachability query;
* strongly connected components;
* weakly connected components where useful;
* topological sorting;
* condensation graphs;
* shortest path / minimum hop distance where useful;
* cycle detection;
* graph permutations/reordering.

For potentially large graphs, avoid eagerly computing dense matrix powers.

The matrix model is conceptually useful, but use normal graph algorithms and sparse representations where they are more efficient.

---

# 3. Analysis result model

All analyzers should emit structured results rather than formatted text directly.

Create an analysis-result abstraction capable of expressing something similar to:

```text
AnalysisSection
    id
    title
    summary
    indicators[]
    findings[]
    recommendations[]
    actions[]
```

A finding should contain at least:

```text
Finding
    id
    severity
    category
    title
    description
    affectedNodes[]
    affectedLinks[]
    evidence / metrics
```

Suggested severity levels:

```text
INFO
NOTICE
WARNING
PROBLEM
```

Do not treat unusual topology automatically as an error.

For example, cycles or self-links can be completely intentional.

Recommendations should therefore distinguish:

```text
observation
possible concern
probable structural issue
```

where appropriate.

---

# 4. Analysis tab structure

Add analysis chapters/sections in approximately this order:

```text
Overview
Principal Data Flow
Error Flow
Dependencies
Subsystems / Workflows
Cross-Layer Analysis
Structural Quality
Available Actions
```

Each section follows the pattern:

```text
Indicators
Qualitative Analysis
Recommendations
Actions
```

Sections should be collapsible if that fits the existing UI.

Findings should support interaction with the model.

Clicking a finding should preferably:

* select affected nodes;
* highlight affected links;
* center/focus them in the editor;
* allow the user to return to the complete graph.

---

# 5. Overview indicators

Provide a compact dashboard of overall graph characteristics.

Include at least:

* total node count;
* processing node count;
* library node count;
* principal-link count;
* error-link count;
* dependency-link count;
* self-link count by type;
* isolated node count;
* principal-data workflow/component count;
* number of principal strongly connected components containing cycles;
* dependency SCC count;
* error sinks/collectors count where detectable;
* nodes participating in principal processing without error routing;
* dependency roots / highly depended-upon libraries;
* graph density where meaningful.

Indicators should link to the corresponding detailed analysis.

---

# 6. Principal data-flow analysis

Analyze `P`.

For every node calculate:

```text
principalInDegree
principalOutDegree
principalReachableUpstream
principalReachableDownstream
```

Use topology to identify candidates for qualitative node roles.

Examples:

### Generator/source

```text
in == 0
out > 0
```

### Sink

```text
in > 0
out == 0
```

### Pipeline/transformer candidate

```text
in == 1
out == 1
```

### Fan-out

```text
in <= 1
out > 1
```

### Merge/mixer candidate

```text
in > 1
out == 1
```

### Router/complex processing node

```text
in > 1
out > 1
```

These classifications are topological candidates, not absolute semantic truth.

Reflect that in the UI.

For example:

> "Topology resembles a fan-out node"

rather than asserting semantics that cannot be inferred.

---

# 7. Principal reachability

Determine principal-data reachability.

For any node `i`, support:

```text
upstream(i)
downstream(i)
```

Use this to identify:

* long processing chains;
* unreachable processing regions;
* disconnected workflows;
* nodes that dominate large downstream regions;
* terminal sinks;
* generators;
* isolated workflows.

Expose commands such as:

```text
Show upstream nodes
Show downstream nodes
Select workflow
Focus workflow
```

---

# 8. Principal feedback and state analysis

Detect:

* direct self-links;
* cycles;
* strongly connected components.

A principal self-link:

```text
P[i][i] == 1
```

should be reported as:

> explicit self-feedback / memorization candidate

Do not flag it as inherently problematic.

For principal SCCs containing more than one node, describe them as feedback/cyclic processing subsystems.

Examples of findings:

```text
Node X contains explicit self-feedback.

Nodes A, B and C form a cyclic principal-data subsystem.

Workflow W is completely feed-forward.

Workflow W contains 3 independent feedback regions.
```

Potential actions:

```text
Select feedback subsystem
Focus feedback subsystem
Show cycle paths
Cluster SCC together visually
```

---

# 9. Error-flow analysis

Analyze `E` independently from principal flow.

Calculate:

```text
errorInDegree
errorOutDegree
errorReachability
errorSCCs
```

Identify:

* nodes producing error routes;
* nodes receiving errors;
* dedicated error sinks;
* error collectors;
* fan-in error handlers;
* chained error propagation;
* error cycles;
* self-error loops;
* processing nodes without an error route.

Where appropriate, treat a mature processing node as normally expected to provide error routing.

Do NOT hard-code that expectation as universally invalid if absent.

Expose it as a configurable structural-quality rule if possible.

For example:

> "Processing node X participates in principal flow but has no outgoing error route."

Potential recommendation:

> "Consider connecting the node to an appropriate error handler if failures can occur here."

---

# 10. Error reachability and termination

Determine which handler/sink can eventually receive an error originating from each processing node.

Identify:

* errors that eventually reach a terminal handler;
* errors that branch to multiple handlers;
* error paths that never reach an error sink;
* error SCCs that can circulate indefinitely;
* isolated error-handling regions.

Example findings:

```text
42 of 45 active processing nodes have a route to an error sink.

3 processing nodes have no error route.

Error collector H receives errors directly or indirectly from 28 nodes.

Nodes X, Y and Z form a cyclic error-routing component.
```

Possible actions:

```text
Select nodes without error routing
Show route to handler
Select common error collector
Focus error subsystem
```

---

# 11. Dependency analysis

Analyze `D` using the convention:

```text
D[i][j] == 1
```

means:

> node `i` depends on node `j`.

Calculate:

```text
directDependencies(i)
directDependents(i)
transitiveDependencies(i)
transitiveDependents(i)
```

Identify:

* processing nodes with many dependencies;
* library nodes used by many processing nodes;
* unused library nodes;
* dependency roots/foundational libraries;
* deep dependency chains;
* dependency cycles;
* mutually dependent groups;
* unexpected processing-to-processing dependencies if relevant;
* libraries depending on processing nodes if that is structurally unusual.

Examples:

```text
Library L is used by 17 processing nodes.

Library L is not referenced by any node.

Node X has 12 direct dependencies.

Node X transitively depends on 34 nodes.

Libraries A, B and C form a dependency cycle.
```

Potential actions:

```text
Select users of library
Select dependencies
Select transitive dependencies
Select transitive dependents
Focus dependency chain
Highlight dependency cycle
```

---

# 12. Dependency SCCs and condensation graph

Find strongly connected components in `D`.

Collapse SCCs into a condensation DAG.

Use this to derive a dependency hierarchy.

Report mutually dependent groups separately.

For acyclic dependency regions, derive:

* dependency levels;
* roots;
* leaves;
* depth.

Potential UI action:

```text
Arrange by dependency level
```

where nodes are laid out or ordered according to the condensation DAG.

This must be a visual/layout operation only unless the existing Threadwork model explicitly assigns semantic meaning to child ordering.

---

# 13. Subsystem and workflow discovery

Use principal data flow as the primary source for identifying executable workflows.

Detect separate weakly connected principal-data components.

Treat each as a workflow/subsystem candidate.

Within each workflow detect:

* generators;
* sinks;
* fan-outs;
* merges;
* feedback SCCs;
* error topology;
* dependency footprint.

Provide summaries such as:

```text
Workflow 1
  14 processing nodes
  2 generators
  1 sink
  2 fan-outs
  1 merge
  1 feedback SCC
  3 libraries
  1 error collector
```

The workflow labels may initially be generated names:

```text
Workflow 1
Workflow 2
...
```

unless existing model metadata provides better names.

---

# 14. Common structural clustering

Use:

$$
U=P\lor E\lor D
$$

for architecture-level clustering where useful.

Do NOT use `U` to make semantic statements about data flow or dependencies.

Its purpose is structural grouping only.

Possible analysis:

* completely isolated nodes;
* tightly connected structural groups;
* graph components;
* candidates for visual clustering.

---

# 15. Shared graph reordering

Support calculating a common node permutation:

$$
Q
$$

and conceptually applying it consistently to:

$$
P'=QPQ^T
$$

$$
E'=QEQ^T
$$

$$
D'=QDQ^T
$$

The same ordering must always be applied to all graph layers.

Possible ordering strategies:

```text
By workflow
By principal SCC
By dependency hierarchy
By structural cluster
By source-to-sink flow
```

Do not independently reorder the three graph layers.

Their common node identity must remain obvious.

---

# 16. Reordering Threadwork child nodes

One concrete action should be:

> Reorder children to cluster separated workflows.

If a parent node contains multiple child processing nodes belonging to distinct detected workflows, offer an action that reorders the children so workflow members become contiguous.

For example:

```text
Before:

A1
B1
C1
A2
C2
B2
A3

After:

A1
A2
A3

B1
B2

C1
C2
```

where A/B/C denote detected workflow membership.

Important:

* preserve graph semantics;
* change only child ordering/layout metadata;
* preserve relative order inside each workflow where possible;
* preferably make the operation undoable;
* show a preview or description before applying;
* do not automatically execute model transformations when analysis runs.

If child order itself has execution semantics anywhere in Threadwork, detect that and do not apply unsafe automatic ordering.

---

# 17. Cross-layer analysis

The most valuable analysis comes from comparing `P`, `E` and `D`.

Implement analysis of relationships between layers.

---

## 17.1 Principal versus error links

Calculate conceptually:

$$
P\land E
$$

Identify node pairs connected both by principal and error links.

Measure whether error flow tends to:

* follow principal flow;
* diverge toward dedicated error infrastructure;
* converge on common handlers.

Possible findings:

```text
Most error links follow the same topology as principal flow.

Workflow W routes errors to a dedicated handler subsystem.

Node X sends both principal and error data to Y.

Three workflows share one error collector.
```

---

# 18. Error coverage of principal processing

For every node participating in principal processing:

```text
principalInDegree + principalOutDegree > 0
```

check whether it has an outgoing error route.

Also check the stronger condition:

> can its error flow eventually reach an error sink/handler?

Report coverage indicators such as:

```text
Error-route coverage: 94%

47 principal-processing nodes
44 reach an error handler
3 have no error route
```

Allow clicking the indicator to select the uncovered nodes.

---

# 19. Principal flow versus dependencies

Compare principal connectivity with dependency connectivity.

Identify cases such as:

### Dependency without runtime communication

```text
D[i][j] == 1
```

while no principal path exists between the regions.

This can be a perfectly valid structural/library dependency.

Describe it rather than flagging it.

### Heavy shared dependencies

If several otherwise separate workflows depend on the same library, identify the library as a shared architectural dependency.

Example:

> "Workflows 1, 2 and 4 are operationally separated but all depend on library L."

This is an important architectural observation.

---

# 20. Cross-workflow dependencies

Once workflows have been detected from principal flow, analyze dependencies across those workflow boundaries.

Examples:

```text
Workflow A depends on 3 libraries also used by Workflow B.

Workflow A has a direct dependency on processing node X from Workflow B.

Library L couples four otherwise independent workflows.
```

Distinguish library sharing from processing-flow coupling.

Do not call shared library usage an error.

---

# 21. Cross-workflow error flow

Detect error links crossing principal-workflow boundaries.

This may reveal shared error infrastructure.

Example:

```text
Workflows A, B and C are independent in principal data flow but converge on Error Handler H.
```

This should be reported positively/descriptively, not as a warning unless a specific structural rule is violated.

---

# 22. Structural quality analysis

Add a section synthesizing configurable structural checks.

Initial rules can include:

### Processing node without error route

Candidate warning.

### Unused library

Candidate notice.

### Completely isolated node

Candidate notice/problem depending on context.

### Dependency cycle

Notice or warning.

### Error cycle without reachable sink

Warning.

### Extremely high fan-out

Notice.

### Extremely high dependency fan-in

Notice identifying architectural centrality.

### Principal workflow with no sink

Potential warning unless cyclic execution makes this intentional.

### Principal workflow with no generator

Potential warning unless externally activated.

### Self-links

Informational, never inherently problematic.

### Cross-workflow processing dependency

Notice/warning depending on Threadwork semantics.

Keep thresholds configurable.

Avoid magic constants scattered throughout the implementation.

---

# 23. Recommendations

Each significant finding may provide one or more recommendations.

Examples:

```text
Finding:
Processing node X has no error route.

Recommendation:
Consider connecting X to an existing error handler used by its workflow.
```

```text
Finding:
Library L is unused.

Recommendation:
Verify whether L is obsolete or reserved for future use.
```

```text
Finding:
Children of Parent P belong to four distinct workflows but are interleaved.

Recommendation:
Group children by workflow to improve structural readability.
```

```text
Finding:
Dependency SCC contains A, B and C.

Recommendation:
Review whether this mutual dependency is intentional.
```

Recommendations should explain what the analysis means, not merely repeat the metric.

---

# 24. Actions

Recommendations can expose actions.

Separate actions into:

### Inspection actions

Examples:

```text
Select affected nodes
Highlight links
Focus nodes
Show upstream
Show downstream
Show dependencies
Show dependents
Show path
Show cycle
```

### Layout actions

Examples:

```text
Cluster workflow
Cluster SCC
Arrange source-to-sink
Arrange by dependency depth
Reorder children by workflow
Move error handlers together
```

### Model-editing actions

Only when safe and semantically unambiguous.

Examples might later include:

```text
remove unused library
```

but do NOT initially implement potentially destructive automated fixes unless Threadwork already has safe command/undo infrastructure.

Model-changing actions must go through the normal Threadwork command/undo mechanism.

---

# 25. Action preview and undo

For every action that modifies the model:

1. determine affected nodes;
2. calculate proposed result;
3. present a short explanation;
4. execute through the application's normal command model;
5. support undo/redo.

Analysis itself must remain read-only.

Do not mutate the graph during analysis.

---

# 26. Analysis dependencies

Some findings depend on previous analysis.

For example:

```text
principal components
    ↓
workflow classification
    ↓
cross-workflow dependencies
    ↓
workflow clustering recommendations
```

Design the internal analysis pipeline so derived analyses can reuse previous results.

Avoid recomputing SCCs, reachability, components, etc.

Consider an immutable cached `AnalysisContext` generated from one model revision.

If the model changes, invalidate/recompute analysis.

---

# 27. Performance

The conceptual model uses adjacency matrices, but Threadwork graphs may become sufficiently large that dense `N × N` storage is undesirable.

Therefore:

* preserve the formal three-matrix model;
* internally use sparse adjacency lists/sets where appropriate;
* avoid `O(n³)` algorithms unless justified;
* use Tarjan or Kosaraju for SCCs;
* use BFS/DFS for reachability queries;
* compute full transitive closure only if graph size makes it reasonable;
* cache expensive derived structures per model revision.

Analysis should not freeze the UI.

Follow existing Threadwork concurrency conventions.

---

# 28. UI presentation

The Analysis tab should make the progression obvious:

```text
INDICATORS
    factual measurements

QUALITATIVE ANALYSIS
    interpretation of topology

RECOMMENDATIONS
    what the user may want to inspect or improve

ACTIONS
    things Threadwork can perform
```

Example:

```text
Subsystems / Workflows

Indicators
----------
Workflows: 4
Largest workflow: 17 nodes
Feedback workflows: 1
Independent workflows: 3

Qualitative Analysis
--------------------
Parent "Main" contains four separate principal-data workflows.
Their child nodes are currently interleaved in the model ordering.

Recommendations
---------------
Grouping children by workflow would make the model structure easier
to inspect without changing graph semantics.

Actions
-------
[Select workflows]
[Preview grouping]
[Reorder children by workflow]
```

Another example:

```text
Error Flow

Indicators
----------
Processing nodes: 24
Nodes with error routes: 22
Error coverage: 91.7%
Error collectors: 2

Qualitative Analysis
--------------------
Most nodes eventually route errors to Handler A.
Nodes Parser2 and Writer3 have no detectable error route.

Recommendations
---------------
Verify whether Parser2 and Writer3 can fail and whether they should
connect to the workflow's existing error infrastructure.

Actions
-------
[Select uncovered nodes]
[Highlight error network]
[Show Handler A reachability]
```



---

# 29. Architecture visualization hooks
!!!! please no graph visualisation , the canvas is enough. maybe use a matrix visualisation ( node names should be written rotated by 90 degrees on columns headers)



Do not build an entirely separate graph renderer for analysis.

Reuse the main Threadwork editor wherever possible.

Analysis should return node/link identities so the editor can:

* select;
* highlight;
* dim unrelated nodes;
* focus;
* temporarily visualize paths or components.

Keep analysis independent of rendering.

!!!! please no graph visualisation , the canvas is enough. maybe use a matrix visualisation ( node names should be written rotated by 90 degrees on columns headers)

---

# 30. Initial implementation priority

Implement incrementally.

### Phase 1 — graph snapshot and indicators

Implement:

* extraction of `P`, `E`, `D`;
* common node indexing;
* degree calculations;
* components;
* SCCs;
* basic Overview UI.

### Phase 2 — qualitative analysis

Implement:

* principal node-role candidates;
* workflows;
* principal feedback analysis;
* dependency analysis;
* error coverage;
* error collectors;
* unused libraries;
* cross-workflow dependency analysis.

### Phase 3 — recommendations and inspection actions

Implement:

* structured findings;
* recommendations;
* select/highlight/focus actions;
* upstream/downstream/dependency exploration.

### Phase 4 — layout/model actions

Implement:

* workflow clustering;
* SCC clustering;
* dependency-level arrangement;
* child reordering by workflow;
* preview;
* undo/redo integration.

---

# 31. Tests

Add tests for both algorithms and Threadwork interpretation.

At minimum test:

* directed edges;
* self-links;
* isolated node;
* simple linear workflow;
* fan-out;
* merge;
* multiple disconnected workflows;
* simple principal cycle;
* principal self-loop;
* dependency chain;
* dependency SCC;
* unused library;
* common library used by multiple workflows;
* processing node with no error link;
* shared error handler;
* error cycle;
* error cycle with no sink;
* separate principal/error topology;
* common permutation/reordering;
* child workflow clustering.

Construct small deterministic graphs so expected properties are explicit.

---

# 32. Important semantic rules

Preserve these assumptions:

1. All graphs are directed.
2. Self-links are legal and meaningful.
3. Principal data, error data and dependencies are distinct relationship types.
4. Never collapse them into one semantic edge type.
5. `U = P ∨ E ∨ D` is structural only.
6. Processing nodes participate in executable data networks.
7. Library nodes are primarily used through dependency links.
8. Separate principal-data components are workflow/subsystem candidates.
9. Cycles are not automatically errors.
10. Self-links are not automatically errors.
11. Missing error routing is a quality observation, not necessarily an invalid model.
12. Analysis must distinguish factual indicators from qualitative interpretation.
13. Recommendations must follow from explicit analysis findings.
14. Analysis is read-only.
15. Model-changing actions require explicit user invocation.
16. Any model/layout transformation must support the existing undo/redo mechanism.
17. The same node mapping/order must be maintained across `P`, `E`, and `D`.

The end result should make the Analysis tab function as an architectural assistant for a Threadwork network:

```text
measure
    ↓
recognize structure
    ↓
describe structure
    ↓
identify noteworthy properties
    ↓
recommend useful inspection/improvement
    ↓
offer safe actions
```

Do not limit the implementation to displaying raw graph-theory metrics. The purpose is to translate graph structure into useful Threadwork-specific architectural information and, where appropriate, safe operations on the model.

