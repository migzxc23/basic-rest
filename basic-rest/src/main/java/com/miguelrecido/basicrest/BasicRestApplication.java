package com.miguelrecido.basicrest;

import com.miguelrecido.secrets.EnableSecretsManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableSecretsManager
public class BasicRestApplication {

    public static void main(String[] args) {
        SpringApplication.run(BasicRestApplication.class, args);
    }
}
