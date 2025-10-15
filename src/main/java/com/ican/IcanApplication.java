package com.ican;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import top.continew.starter.web.annotation.EnableGlobalResponse;

/**
 * @author 席崇援
 * @since 2024-10-03
 */
@EnableGlobalResponse
@SpringBootApplication
@EnableAsync
@MapperScan("com.ican.mapper")
public class IcanApplication {

	public static void main(String[] args) {
		SpringApplication.run(IcanApplication.class, args);
		System.out.println("===========================================================\n"+
		 "帮助文档 UI (Knife4j): " + "http://localhost:8080/doc.html\n"
		 + "===========================================================");

	}

}
