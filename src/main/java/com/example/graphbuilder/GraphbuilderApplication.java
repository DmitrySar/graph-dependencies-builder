package com.example.graphbuilder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class GraphbuilderApplication {

	public static void main(String[] args) throws Exception {
		boolean mcpMode = java.util.Arrays.asList(args).contains("--mcp");

		SpringApplication app = new SpringApplication(GraphbuilderApplication.class);
		if (mcpMode) {
			// В MCP-режиме stdout занят протоколом — логи только в файл.
			args = new String[]{
					"--spring.main.banner-mode=off",
					"--spring.main.web-application-type=none",
					"--logging.console.enabled=false",
					"--logging.file.name=code-graph-mcp.log"
			};
		}

		ApplicationContext context = app.run(args);

		CodeGraphCli codeGraphCli = context.getBean(CodeGraphCli.class);
		if (mcpMode) {
			context.getBean(CodeGraphMcpServer.class).run();
		} else {
			codeGraphCli.generateGraphDependencies(args);
		}
	}

}
