package top.hetaoawa.astra.api.annotation;

import java.lang.annotation.*;
import top.hetaoawa.astra.api.Scene;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnNotice {
  String[] types() default {};

  Scene[] scenes() default {};

  int priority() default 100;

  boolean block() default false;
}
