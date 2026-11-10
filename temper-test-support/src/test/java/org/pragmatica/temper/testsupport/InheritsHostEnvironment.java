package org.pragmatica.temper.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Marks a test class that builds a raw `ProcessBuilder` on purpose, to poison its inherited environment and show that the
/// code under test strips it (the sandbox environment tests). Documentation only: the exemption is the fixed list
/// `HermeticLaunchRules.MAY_INHERIT`, and the rules fail on any other class that carries this annotation.
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface InheritsHostEnvironment {}
