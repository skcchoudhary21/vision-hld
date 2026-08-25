package com.visionbank.approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApprovalEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApprovalEngineApplication.class, args);
    }
}
