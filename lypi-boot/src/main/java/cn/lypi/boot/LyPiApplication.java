package cn.lypi.boot;

import java.util.List;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class LyPiApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = application(args).run(args);
        System.exit(SpringApplication.exit(context));
    }

    /**
     * 创建应用入口。
     */
    public static SpringApplication application() {
        return application(new String[0]);
    }

    /**
     * 创建应用入口。
     */
    public static SpringApplication application(String[] args) {
        SpringApplication application = new SpringApplication(LyPiApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        if (isHeadlessSubagent(args)) {
            application.setDefaultProperties(Map.of("logging.level.root", "off"));
        }
        return application;
    }

    private static boolean isHeadlessSubagent(String[] args) {
        if (args == null) {
            return false;
        }
        return List.of(args).stream().anyMatch(argument ->
            argument.equals("headless-subagent")
                || argument.equals("--lypi.headless.subagent")
                || argument.equals("--lypi-headless-subagent")
                || argument.startsWith("--lypi.headless.subagent=")
                || argument.startsWith("--lypi-headless-subagent=")
        );
    }
}
