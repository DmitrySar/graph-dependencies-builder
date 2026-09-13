package com.example.graphbuilder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class GraphbuilderApplication {

	public static void main(String[] args) throws Exception {
		ApplicationContext context = SpringApplication.run(GraphbuilderApplication.class, args);

		CodeGraphCli codeGraphCli = context.getBean(CodeGraphCli.class);
		codeGraphCli.generateGraphDependencies(args);
	}

}
