package dev.hippodid.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Boot application entry point for the HippoDid MCP server.
 *
 * <p>Supports two transport modes:
 * <ul>
 *   <li>{@code stdio} (default) — JSON-RPC over stdin/stdout, one process per session</li>
 *   <li>{@code sse} — HTTP SSE on embedded Tomcat, multi-session</li>
 * </ul>
 *
 * <p>Mode selection: {@code --mode sse} CLI arg, or {@code HIPPODID_MCP_MODE=sse} env var.
 */
@SpringBootApplication
@EnableConfigurationProperties(McpProperties.class)
public class HippoDidMcpApplication {

    public static void main(String[] args) {
        String mode = resolveMode(args);

        SpringApplication app = new SpringApplication(HippoDidMcpApplication.class);

        Map<String, Object> defaults = new HashMap<>();
        defaults.put("mcp.mode", mode);

        if ("sse".equalsIgnoreCase(mode)) {
            defaults.put("spring.main.web-application-type", "servlet");
            defaults.put("server.port",
                    System.getenv().getOrDefault("HIPPODID_MCP_SSE_PORT", "8090"));
        }

        app.setDefaultProperties(defaults);
        app.run(args);
    }

    private static String resolveMode(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--mode".equals(args[i])) {
                return args[i + 1];
            }
        }
        String envMode = System.getenv("HIPPODID_MCP_MODE");
        return envMode != null && !envMode.isBlank() ? envMode : "stdio";
    }
}
