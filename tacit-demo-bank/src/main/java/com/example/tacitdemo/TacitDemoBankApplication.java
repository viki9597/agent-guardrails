package com.example.tacitdemo;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TacitDemoBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(TacitDemoBankApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider bankTools(BankMockService bankMockService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(bankMockService)
                .build();
    }
}
