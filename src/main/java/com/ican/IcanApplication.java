package com.ican;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import top.continew.starter.web.annotation.EnableGlobalResponse;

/**
 * ICan 应用启动类
 *
 * @author ICan
 * @since 2024-10-03
 */
@EnableGlobalResponse
@SpringBootApplication
@EnableAsync
@MapperScan("com.ican.mapper")
public class IcanApplication {

	public static void main(String[] args) {
		SpringApplication.run(IcanApplication.class, args);
	}

}
