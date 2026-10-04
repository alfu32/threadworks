# Threadwork MCP Agent Guide

This guide is the operating contract for an agent that can use the Threadwork MCP server without access to the Threadwork source code. The live open document is the source of truth.

## Mental model

Threadwork is a topology-first IDE. A project is a `ThreadworkDocument` containing one root entity and a graph of entities:

- `Processor`: executable component or service/library component.
- `Group`: composite/container; hierarchy is structural.
- `Type`: shared wire type with a compiler-neutral `typeDefinition`.
- `Link`: directed edge with endpoint ports and optional data/capability semantics.
- `Note`: explanatory content.

Every entity has a stable opaque `id`. Names are for humans; IDs are for mutations. Links are also entities and have their own IDs.

The document model also stores text sections:

- `source` / declaration: implementation or compiler-facing source.
- `specification`: behavior and contract in Markdown.
- `tests`: JSON test data or test cases.
- `initialization`: optional setup code.

## Safe MCP session

1. Call `initialize` when using a fresh MCP session.
2. Call `threadwork.get_design` before making structural changes. Use `threadwork.get_fragment` or `threadwork.get_entity` for focused inspection.
3. Call `threadwork.list_technologies` before assigning compiler metadata.
4. Use repository-backed mutation tools: `create_entity`, `update_entity`, `create_link`, `update_link`, `move_entity`, and `delete_entity`.
5. Use entity IDs from the response; never infer or invent IDs.
6. Call `threadwork.validate_design` after structural or type/link changes.
7. Use `threadwork.analyze_network` to confirm counts and topology.
8. Use `threadwork.get_command_catalog` before invoking a desktop command. A command can be listed but disabled because the required UI selection/state is missing.

## Important mutation rule

An `update_entity` text patch replaces the editable text object. When changing one text section, send the complete text payload and preserve the other sections. In practice, include `declaration`, `declarationLanguageId`, `specification`, `specificationLanguageId`, `tests`, and `testsLanguageId` together. Otherwise a later specification or test update can erase the source declaration.

Example:

```json
{
  "nodeId": "node-id-from-inspection",
  "patch": {
    "text": {
      "declaration": "int run(void) { return 0; }",
      "declarationLanguageId": "c",
      "specification": "Runs the component and returns zero on success.",
      "specificationLanguageId": "markdown",
      "tests": "{\"cases\":[]}",
      "testsLanguageId": "json"
    }
  }
}
```

## Designing a workflow

For each processing node, define the contract before implementation:

1. Name the node with a stable role, such as `read_csv_file`, `extract_data`, or `write_result`.
2. Add explicit input/output ports. Port direction is `Input` or `Output`.
3. Add one or more shared `Type` entities when a wire carries structured data.
4. Set each data link's `typeDefinitionId` to the built-in type ID or the stable Type entity ID.
5. Create data links from output port to input port.
6. Put behavior in `specification`, examples and edge cases in JSON `tests`, and implementation in the declaration/source section.
7. Keep service/library dependencies as separate library nodes with `lib` links rather than mixing service code into processing nodes.

Data links use `interactionKind: "data"`. Service dependencies use `interactionKind: "lib"`; the provider is the link source and the processing node is the target.

## C implementation conventions

Use technology metadata:

```json
{
  "languageId": "c",
  "technologyId": "c-native",
  "compilerId": "c-compiler",
  "fileExtension": "c",
  "contentType": "text/x-c"
}
```

The C compiler treats service-library declarations specially:

- System `#include` directives belong in service/library nodes.
- Processing-node declarations should contain workflow logic and should not own system includes.
- A library card must contain real function definitions, not only prototypes or signatures.
- The provider function is named normally in the library, for example `read_file_text`.
- A library link gives the processing node a qualified callable alias, for example `csv_reader_io__read_file_text`.
- Data flow uses generated `push(port, &value)`, `pop(port, &value)`, and `threadwork_buffer_count(port)` operations.
- Library functions must document ownership and clean up allocated memory on all failure paths.

For a CSV workflow, a reasonable pair of shared types is:

- `CsvUsers`: owns a `char***` cell table, row/column counts, and the detected city-column index.
- `CityCount`: contains a city string and its aggregate user count.

The file library should own complete file reads/writes. The table library should own parsing, table allocation/freeing, city-column detection, and aggregation. Processing nodes should orchestrate those methods through library links.

## Layout and readability

Layout is presentation data, not topology. For a compact jagged layout:

- Keep the primary flow left-to-right.
- Stagger the transform node vertically so input/output labels and links do not collide.
- Give a library one output service port per dependent role when multiple flyout labels would share one row.
- Keep shared types in a separate lane below the flow.
- Update `layout` only after the graph is structurally valid, then validate again.

## Fiches, About, and persistence

Component fiches are UI selections, not implicit MCP selections. Check the command catalog and enablement before invoking fiche or AI commands. The MCP resource `threadwork://agent-guide` is the authoritative copy of this guide.

MCP edits change the open in-memory document. An untitled document has no `.orch` path, so the desktop may ask for Save As when persistence or generation needs a file location. MCP does not silently choose a path; the user must choose where to save the project.

## Completion checklist

- Inspect the current design first.
- Use stable IDs for every mutation.
- Keep specifications, tests, and declarations together in text patches.
- Keep includes and reusable service methods in library cards.
- Use explicit types and link interaction kinds.
- Validate with zero errors.
- Confirm network counts and expected dependencies.
- If generating, invoke only an enabled command and report whether it was executed.
