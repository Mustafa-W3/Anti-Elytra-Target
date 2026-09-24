package com.antielytratarget.check;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CheckData {

    String name();

String configName() default "";

double decay() default 0.05;

    String description() default "";
}
