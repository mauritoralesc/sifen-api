package com.ratones.sifenwrapper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SifenWrapperApplication {

    public static void main(String[] args) {
        SpringApplication.run(SifenWrapperApplication.class, args);
    }
}
