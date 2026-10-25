package com.example.tacitmcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TacitMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(TacitMcpApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider tacitTools(ScalaEvalService scalaEvalService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(scalaEvalService)
                .build();
    }
}
