package io.agentjava;

import io.agentjava.config.ProjectConfig;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class AgentJavaApplication {
  public static void main(String[] args) {
    var config = new ProjectConfig();
    var app = new SpringApplication(AgentJavaApplication.class);
    app.setDefaultProperties(
        Map.of(
            "server.address",
            config.text("backend", "host"),
            "server.port",
            config.number("backend", "port", 8081),
            "spring.servlet.multipart.max-file-size",
            "10MB",
            "spring.servlet.multipart.max-request-size",
            "11MB",
            "spring.mvc.async.request-timeout",
            "600000",
            "spring.main.banner-mode",
            "off"));
    app.run(args);
  }
}
