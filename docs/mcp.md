# Threadwork MCP

Threadwork starts a loopback-only MCP Streamable HTTP server with the desktop editor. The default endpoint is:

```text
http://127.0.0.1:8765/mcp
```

The server supports the current stateless MCP protocol (`2026-07-28`) through `server/discover` and retains the legacy `initialize` handshake for older clients. It does not require an account or internet connection. The server is reachable only from the local machine.

## Design guide first

MCP is an interface to the Threadwork design model, not a replacement for
design reasoning. Before creating or changing a workflow, agents should read
the [Threadwork Design Guide](design-guide.md). It is the project manifesto:

- libraries define reusable algorithms and capabilities;
- processing nodes employ those capabilities and route their result or typed
  error;
- Type entities define values crossing boundaries;
- links expose data flow, error flow, calls, dependencies, and recovery.

The guide is advisory. It establishes the normal architecture without
preventing a human or machine designer from making an explicit, justified
exception. The MCP resource `threadwork://agent-guide` contains the same core
principles together with protocol, mutation, C implementation, layout, and
persistence instructions for agents that cannot read this repository.

## Codex

With the app running, register the endpoint once:

```text
codex mcp add threadwork --url http://127.0.0.1:8765/mcp
codex mcp list
```

The equivalent user configuration is:

```toml
[mcp_servers.threadwork]
url = "http://127.0.0.1:8765/mcp"
```

On Linux and macOS this is normally `~/.codex/config.toml`. On Windows use `%USERPROFILE%\\.codex\\config.toml`. Codex Desktop and the Codex CLI share this configuration.

The app's `MCP` tab shows the endpoint and controls for starting, restarting, or stopping the server. The server starts automatically when the desktop app opens.

## Exposed operations

The tools are grouped around the open model:

- Read the complete design, an entity, a subtree fragment, metadata, users, technologies, command catalog, validation diagnostics, and network counts.
- Create, update, move, and delete entities.
- Create and update directed links, including self-links.
- Update entity text, layout, technology, ports, type definitions, link data, metadata, plugin data, revision, diagnostics, assignee, responsible user, and status.
- Register or refresh model users.
- Execute enabled desktop commands by command ID.

The `threadwork://instructions`, `threadwork://agent-guide`,
`threadwork://technologies`, `threadwork://commands`, and
`threadwork://design` resources provide agent guidance and JSON views of the
current app state. The server instructions tell an agent to read the design
guide before editing, use stable entity IDs, use repository-backed mutations,
keep algorithms in libraries, keep processing nodes thin, preserve result and
error contracts, and validate after structural changes.

## Security boundary

This is a local desktop integration, not a remotely hosted MCP service. It binds to `127.0.0.1`, accepts model-editing commands without OAuth, and should not be exposed through a reverse proxy or a non-loopback bind address without adding authentication and authorization first.
