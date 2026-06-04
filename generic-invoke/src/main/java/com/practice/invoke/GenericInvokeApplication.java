package com.practice.invoke;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统一泛化调用示例应用入口。
 */
@SpringBootApplication
@MapperScan("com.practice.invoke.mapper")
public class GenericInvokeApplication {

    public static void main(String[] args) {
        SpringApplication.run(GenericInvokeApplication.class, args);
    }
}
