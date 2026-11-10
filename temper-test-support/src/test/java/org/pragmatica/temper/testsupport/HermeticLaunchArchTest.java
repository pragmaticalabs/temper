package org.pragmatica.temper.testsupport;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;


@AnalyzeClasses(locations = OwnTestOutput.class)
class HermeticLaunchArchTest {
    @ArchTest
    static final ArchTests launches = ArchTests.in(HermeticLaunchRules.class);
}
