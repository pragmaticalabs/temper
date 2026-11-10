package org.pragmatica.temper.protocol.report;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.namespace.QName;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartDocument;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.ParsedTestcase;
import org.pragmatica.temper.domain.report.ReportFile;

import static org.pragmatica.lang.utils.Causes.cause;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Thread-confined scan state for ONE report document, fed event by event. Numbers are kept as text and
/// parsed once at the end ([#toReportFile]), so a bad number is a typed failure, not an exception mid-scan.
final class ReportScan {
    private final Deque<String> suites = new ArrayDeque<>();
    private final List<String> testsValues = new ArrayList<>();
    private final List<PendingTestcase> rows = new ArrayList<>();
    private Option<OpenTestcase> current = none();
    private Option<String> declaredEncoding = none();

    ReportScan() {}

    /// One StAX event (the iterator yields `Object`); anything that is not an element is ignored.
    ReportScan accept(Object event) {
        return event instanceof XMLEvent xml
               ? onEvent(xml)
               : this;
    }

    /// The report file, or a failure when the body declares an encoding other than UTF-8 (m0-design §3.3) or
    /// carries an unparseable number.
    Result<ReportFile> toReportFile(String fileName) {
        return utf8().flatMap(_ -> parsed(fileName));
    }

    private Result<ReportFile> parsed(String fileName) {
        return Result.all(testsAttr(),
                          Result.allOf(rows.stream().map(PendingTestcase::parsed).toList()))
                     .map((tests, testcases) -> ReportFile.reportFile(fileName, tests, testcases));
    }

    private Result<Unit> utf8() {
        return declaredEncoding.filter(encoding -> !encoding.equalsIgnoreCase("UTF-8"))
                               .map(ReportScan::notUtf8)
                               .or(Result::unitResult);
    }

    private static Result<Unit> notUtf8(String encoding) {
        return cause("declared encoding " + encoding + " is not UTF-8").result();
    }

    private ReportScan onEvent(XMLEvent event) {
        if (event.isStartDocument()) {
            return startDocument((StartDocument) event);
        }

        if (event.isStartElement()) {
            return start(event.asStartElement());
        }

        if (event.isEndElement()) {
            return end(event.asEndElement().getName().getLocalPart());
        }

        return this;
    }

    private ReportScan startDocument(StartDocument document) {
        if (document.encodingSet()) {
            declaredEncoding = some(document.getCharacterEncodingScheme());
        }

        return this;
    }

    private ReportScan start(StartElement element) {
        var name = element.getName().getLocalPart();

        current.onPresent(testcase -> testcase.child(name)).onEmpty(() -> startOutside(element, name));

        return this;
    }

    private ReportScan startOutside(StartElement element, String name) {
        if (name.equals("testsuite")) {
            suites.push(attribute(element, "name").or(""));
            testsValues.add(attribute(element, "tests").or("0"));
        }

        if (name.equals("testcase")) {
            current = some(OpenTestcase.open(attribute(element, "classname").or(this::enclosingSuite),
                                             attribute(element, "name").or(""),
                                             attribute(element, "time")));
        }

        return this;
    }

    private String enclosingSuite() {
        return option(suites.peek()).or("");
    }

    private ReportScan end(String name) {
        current.onPresent(testcase -> endInside(testcase, name)).onEmpty(() -> endOutside(name));

        return this;
    }

    private ReportScan endInside(OpenTestcase testcase, String name) {
        if (testcase.closes(name)) {
            rows.add(testcase.pending());
            current = none();
        }

        return this;
    }

    private ReportScan endOutside(String name) {
        if (name.equals("testsuite")) {
            suites.poll();
        }

        return this;
    }

    private Result<Long> testsAttr() {
        return Result.allOf(testsValues.stream().map(ReportScan::count).toList()).map(ReportScan::sum);
    }

    private static Result<Long> count(String value) {
        return Number.parseLong(value.trim());
    }

    private static long sum(List<Long> values) {
        return values.stream()
                     .mapToLong(Long::longValue)
                     .sum();
    }

    private static Option<String> attribute(StartElement element, String name) {
        return option(element.getAttributeByName(new QName(name))).map(ReportScan::valueOf);
    }

    private static String valueOf(Attribute attribute) {
        return attribute.getValue();
    }

    /// A finished `<testcase>` whose `time` is still text.
    private record PendingTestcase(String className, String name, Outcome outcome, Option<String> rawTime) {
        private static final Pattern GROUPED = Pattern.compile("^[1-9]\\d{0,2}(,\\d{3})+(\\.\\d+)?$");

        public static PendingTestcase pendingTestcase(String className,
                                                      String name,
                                                      Outcome outcome,
                                                      Option<String> rawTime) {
            return new PendingTestcase(className, name, outcome, rawTime);
        }

        Result<ParsedTestcase> parsed() {
            return rawTime.map(PendingTestcase::seconds)
                          .or(success(0.0))
                          .map(time -> ParsedTestcase.parsedTestcase(className, name, outcome, time));
        }

        /// Commas are dropped only as thousands separators (`1,234.5`); a decimal comma (`0,123`) is malformed.
        private static Result<Double> seconds(String raw) {
            return Number.parseDouble(withoutGrouping(raw.trim()));
        }

        private static String withoutGrouping(String time) {
            return GROUPED.matcher(time).matches()
                   ? time.replace(",", "")
                   : time;
        }
    }

    /// A `<testcase>` being read; `depth` counts open elements nested inside it.
    private static final class OpenTestcase {
        private final String className;
        private final String name;
        private final Option<String> rawTime;
        private Option<Outcome> primary = none();
        private boolean rerunFailure;
        private boolean rerunError;
        private int depth;

        private OpenTestcase(String className, String name, Option<String> rawTime) {
            this.className = className;
            this.name = name;
            this.rawTime = rawTime;
        }

        static OpenTestcase open(String className, String name, Option<String> rawTime) {
            return new OpenTestcase(className, name, rawTime);
        }

        OpenTestcase child(String element) {
            depth++;

            return depth == 1
                   ? directChild(element)
                   : this;
        }

        boolean closes(String element) {
            if (depth == 0) {
                return element.equals("testcase");
            }

            depth--;

            return false;
        }

        PendingTestcase pending() {
            return new PendingTestcase(className, name, primary.or(this::fallbackOutcome), rawTime);
        }

        private OpenTestcase directChild(String element) {
            switch (element) {
                case "failure" -> decide(Outcome.FAILED);
                case "error" -> decide(Outcome.ERROR);
                case "skipped" -> decide(Outcome.SKIPPED);
                case "rerunFailure" -> rerunFailure = true;
                case "rerunError" -> rerunError = true;
                default -> {}
            }

            return this;
        }

        private OpenTestcase decide(Outcome outcome) {
            primary = primary.orElse(some(outcome));

            return this;
        }

        private Outcome fallbackOutcome() {
            if (rerunFailure) {
                return Outcome.FAILED;
            }

            return rerunError
                   ? Outcome.ERROR
                   : Outcome.PASSED;
        }
    }
}
