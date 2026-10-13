package com.citygo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.citygo.mapper")
@SpringBootApplication
@EnableScheduling
public class CityGoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityGoApplication.class, args);
    }

}