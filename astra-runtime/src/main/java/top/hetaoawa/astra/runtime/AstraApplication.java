package top.hetaoawa.astra.runtime;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AstraConfig.class)
public class AstraApplication {
  public static void main(String[] args) {
    SpringApplication.run(AstraApplication.class, args);
  }
}
