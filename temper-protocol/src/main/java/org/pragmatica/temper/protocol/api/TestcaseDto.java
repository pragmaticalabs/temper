package org.pragmatica.temper.protocol.api;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;

import com.fasterxml.jackson.annotation.JsonProperty;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// One testcase row: `GET /jobs/{id}/testcases` and the `UnitReport` frame chunks. `outcome` is lowercase.
public record TestcaseDto(@JsonProperty("class") String className,
                          String name,
                          String outcome,
                          double timeSeconds,
                          String unit,
                          boolean rerun) {
    public static TestcaseDto testcaseDto(TestcaseRow row) {
        return new TestcaseDto(row.className(),
                               row.name(),
                               wire(row.outcome()),
                               row.timeSeconds(),
                               name(row.unit()),
                               row.rerun());
    }

    private static String wire(Outcome value) {
        return value.wire();
    }

    private static String name(UnitName value) {
        return value.value();
    }

    public Result<TestcaseRow> toDomain() {
        return Result.all(UnitName.unitName(unit),
                          Verify.ensure(className,
                                        Verify.Is::notNull,
                                        invalid("class")),
                          Verify.ensure(name,
                                        Verify.Is::notNull,
                                        invalid("name")),
                          Outcome.outcome(outcome))
                     .map((unitName, cls, testName, parsed) -> TestcaseRow.testcaseRow(unitName,
                                                                                       cls,
                                                                                       testName,
                                                                                       parsed,
                                                                                       timeSeconds,
                                                                                       rerun));
    }
}
