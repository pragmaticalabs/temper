package org.pragmatica.temper.acceptance.trust;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.report.ParsedTestcase;
import org.pragmatica.temper.domain.report.ReportFile;
import org.pragmatica.temper.protocol.api.ManifestView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.ManifestEntryDto;
import org.pragmatica.temper.protocol.report.ReportParser;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The captured bb-report outputs and the S1 report XML of this module's test resources (`bb-report/capture.sh`
/// says how they were made), and temper-side records built from the same XML.
final class Captures {
    /// The 26 S1 TC report files (real bigboy XML: 293 `<testcase>`, `tests=` sum 177 because of `@Nested`).
    static final List<String> S1_FILES = List.of("ArrayConversionsTest", "AuthenticationTest", "BinaryRowReadLivePgTest",
                                                 "ConnectionPoolingTest", "CrossTypeConversionTest", "CustomConverterTest",
                                                 "GenericBinaryConversionTest", "ListenNotifyTest", "net.SslConfigTest",
                                                 "ParametersBindingTest", "PipelineTest", "PlainConnectionTest",
                                                 "PreparedStatementsCacheTest", "QueryResultTest", "sasl.ChannelBindingDigestTest",
                                                 "sasl.SaslPrepCharClassTest", "sasl.SaslPrepRfc4013ComplianceTest",
                                                 "sasl.SaslStepsTest", "ScriptResultsTest", "SslModeIntegrationTest",
                                                 "TransactionModePoolingTest", "TransactionTest", "TransientClassificationTest",
                                                 "TypeConverterTest", "util.HexConverterTest", "ValidatedConnectionTest");

    private Captures() {}

    static String text(String resource) {
        try (InputStream in = Captures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// bb-report's stdout of a capture.
    static String out(String name) {
        return text("/bb-report/" + name + ".out");
    }

    /// bb-report's exit code of a capture.
    static int exit(String name) {
        return Integer.parseInt(text("/bb-report/" + name + ".exit").strip());
    }

    static BbReport report(String name) {
        return BbReportParser.parse(out(name), exit(name))
                             .fold(cause -> {
                                       throw new AssertionError(cause.message());
                                   },
                                   report -> report);
    }

    /// The S1 XML read with temper's own `ReportParser`; `red` swaps in the two doctored files of the red capture.
    static List<ReportFile> s1Reports(boolean red) {
        var reports = new ArrayList<ReportFile>();

        for (var file : S1_FILES) {
            var name = "TEST-org.pragmatica.postgres." + file + ".xml";
            var doctored = red && (file.equals("PipelineTest") || file.equals("CrossTypeConversionTest"));
            var resource = doctored
                           ? "/bb-report/red-" + name
                           : "/s1-reports/" + name;
            reports.add(ReportParser.parse(name, text(resource).getBytes(StandardCharsets.UTF_8))
                                    .fold(cause -> {
                                              throw new AssertionError(cause.message());
                                          },
                                          report -> report));
        }
        return reports;
    }

    /// Every `<testcase>` of [#s1Reports].
    static List<ParsedTestcase> s1Rows(boolean red) {
        return s1Reports(red).stream().flatMap(report -> report.testcases().stream()).toList();
    }

    /// temper's rows for the same cases, as `GET /jobs/{id}/testcases` answers them.
    static List<TestcaseDto> temperRows(List<ParsedTestcase> rows) {
        return rows.stream()
                   .map(row -> new TestcaseDto(row.className(), row.name(), row.outcome().wire(), row.timeSeconds(), "gate", false))
                   .toList();
    }

    static ResultRecordDto result(String verdict, int testcases, long testsAttr) {
        return new ResultRecordDto(1,
                                   verdict,
                                   none(),
                                   none(),
                                   new SourceView(none(), some("a".repeat(40))),
                                   new RecipeDto.Gate(),
                                   List.of(),
                                   new ResultRecordDto.CountsDto(testcases, testcases, 0, 0, 0, testsAttr, 26, some(26), some(0), some(0)),
                                   List.of(),
                                   0,
                                   List.of(),
                                   none(),
                                   ResultRecordDto.EvidenceDto.NOT_STORED,
                                   new ResultRecordDto.TimingsDto(none(), Option.some(Duration.ofMinutes(94))), none(), List.of());
    }

    static ManifestView manifest(Set<String> classes, List<String> missing, List<String> drift) {
        return new ManifestView(true,
                                false,
                                classes.stream().sorted().map(name -> new ManifestEntryDto("db/postgres-async", name, "surefire")).toList(),
                                List.of(),
                                missing,
                                drift,
                                List.of());
    }
}
