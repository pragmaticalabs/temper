package fx.display;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.DisplayName;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@DisplayName("Meta shown")
@interface MetaShown {}
