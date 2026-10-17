package top.hetaoawa.astra.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Scheduled {
  long delayMillis() default -1;

  long intervalMillis() default -1;

  String cron() default "";

  String zone() default "Asia/Shanghai";
}
