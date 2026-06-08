package cn.lypi.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class LyPiApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(LyPiApplication.class, args);
        System.exit(SpringApplication.exit(context));
    }
}
