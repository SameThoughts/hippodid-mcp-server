package dev.hippodid.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot application entry point for the HippoDid MCP server.
 *
 * <p>Runs as a CLI process (no web server). The MCP JSON-RPC protocol uses
 * stdout, so all logging goes to stderr.
 */
@SpringBootApplication
@EnableConfigurationProperties(McpProperties.class)
public class HippoDidMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(HippoDidMcpApplication.class, args);
    }
}
