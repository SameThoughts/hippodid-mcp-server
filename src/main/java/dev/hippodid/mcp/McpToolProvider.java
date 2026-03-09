package dev.hippodid.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;

import java.util.List;

/**
 * Marker interface for MCP tool provider components.
 *
 * <p>Each implementation registers a logical group of tools (character, memory, file sync).
 * All tool specifications are collected by {@link McpServerRunner} at startup.
 */
public interface McpToolProvider {

    /**
     * Returns the list of synchronous tool specifications provided by this component.
     *
     * @return non-null, non-empty list of tool specifications
     */
    List<McpServerFeatures.SyncToolSpecification> tools();
}