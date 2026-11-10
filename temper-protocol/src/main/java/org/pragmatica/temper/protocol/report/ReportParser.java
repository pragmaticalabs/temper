package org.pragmatica.temper.protocol.report;

import java.io.ByteArrayInputStream;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.report.ReportError;
import org.pragmatica.temper.domain.report.ReportFile;


/// Surefire/failsafe XML report parser (spec §5.1, m0-design §3.3). It parses an in-memory `byte[]`; XML
/// parsing through the JDK's StAX is an adapter boundary (it can only fail by exception), which is why it
/// lives here rather than in temper-domain, next to the report types it produces.
///
/// - Namespace-unaware StAX reader, with DTDs and external entities disabled (XXE closed).
/// - `testsAttr` is the sum of `tests=` over every `<testsuite>`, including those under `<testsuites>`.
/// - Each `<testcase>` gives one row. Its outcome is the FIRST direct child that is `<failure>` (FAILED),
///   `<error>` (ERROR) or `<skipped>` (SKIPPED); otherwise PASSED. `<flakyFailure>`/`<flakyError>` alone
///   mean PASSED; `<rerunFailure>` alone means FAILED and `<rerunError>` alone means ERROR.
/// - A missing `classname` falls back to the enclosing `testsuite@name`; a missing `time` is 0. Thousands
///   separators in `time` (`1,234.5`, written by some surefire versions) are dropped.
/// - Any XML error, non-UTF-8/XML body, or unparseable number gives `ReportError.Malformed`.
public sealed interface ReportParser {
    static Result<ReportFile> parse(String fileName, byte[] xml) {
        return Result.lift(malformed(fileName),
                           () -> scan(factory().createXMLEventReader(new ByteArrayInputStream(xml))))
                     .flatMap(scan -> scan.toReportFile(fileName)
                                          .mapError(malformedCause(fileName)));
    }

    /// Feeds every event to a fresh scan. `XMLEventReader` iteration reports XML errors as unchecked
    /// exceptions, which the caller's `lift` turns into `ReportError.Malformed`. The reader reads an in-memory
    /// array, so it holds no resource that needs closing.
    private static ReportScan scan(XMLEventReader events) {
        var scan = new ReportScan();

        events.forEachRemaining(scan::accept);

        return scan;
    }

    private static XMLInputFactory factory() {
        var factory = XMLInputFactory.newDefaultFactory();

        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        return factory;
    }

    private static Fn1<ReportError.Malformed, Throwable> malformed(String fileName) {
        return thrown -> ReportError.Malformed.FACTORY.apply(fileName, Causes.fromThrowable(thrown));
    }

    private static Fn1<ReportError.Malformed, Cause> malformedCause(String fileName) {
        return origin -> ReportError.Malformed.FACTORY.apply(fileName, origin);
    }

    record unused() implements ReportParser {}
}
