package top.hetaoawa.astra.api.annotation;

import java.lang.annotation.*;
import top.hetaoawa.astra.api.Scene;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnCommand {
  String value();

  String[] aliases() default {};

  String[] prefixes() default {"/"};

  Scene[] scenes() default {};

  int priority() default 100;

  boolean block() default false;
}
