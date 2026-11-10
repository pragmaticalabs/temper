package org.pragmatica.temper.controller.t3;

import java.util.List;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.BbTicket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The completeness proof of a `bb-tickets` reply.
class TicketParseTest {
    private static final JsonMapper MAPPER = TemperJson.mapper();

    private static final String RUNNING = "{\"v\":1,\"ticket\":\"r1\",\"dir\":\"running\",\"waiter\":\"alive\",\"fields\":{\"owner\":\"x\",\"agent\":\"a\"},\"dropped\":[\"co\"]}";

    private static final String DONE = "{\"v\":1,\"ticket\":\"d1\",\"dir\":\"done\",\"waiter\":null,\"fields\":{\"rc\":\"0\"},\"dropped\":[]}";

    private static String trailer(int count) {
        return "{\"v\":1,\"end\":true,\"count\":" + count + ",\"queue\":\"present\",\"at\":1793700000}";
    }

    private static String failure(String stdout) {
        var result = TicketParse.parse(MAPPER, stdout);

        assertThat(result.isFailure()).as(stdout).isTrue();

        return result.fold(cause -> cause.message(), _ -> "");
    }

    @Test
    void aCompleteReply_isTheTicketLinesInOrder_withWaiterFieldsAndDropped() {
        var tickets = TicketParse.parse(MAPPER, RUNNING + "\n" + DONE + "\n" + trailer(2) + "\n").unwrap().tickets();

        assertThat(tickets).extracting(BbTicket::ticket).containsExactly("r1", "d1");
        assertThat(tickets.get(0).waiter()).isEqualTo("alive");
        assertThat(tickets.get(0).fields()).containsEntry("owner", "x").containsEntry("agent", "a");
        assertThat(tickets.get(0).dropped()).containsExactly("co");
        assertThat(tickets.get(1).waiterState().isPresent()).as("a done ticket has waiter null").isFalse();
    }

    @Test
    void aReplyWithoutItsTrailer_isTruncated() {
        assertThat(failure(RUNNING + "\n" + DONE + "\n")).isEqualTo("truncated");
        assertThat(failure("")).isEqualTo("truncated");
    }

    @Test
    void aCountThatDiffersFromTheLines_isTruncated() {
        assertThat(failure(RUNNING + "\n" + trailer(2) + "\n")).isEqualTo("truncated");
        assertThat(failure(RUNNING + "\n" + DONE + "\n" + trailer(1) + "\n")).isEqualTo("truncated");
    }

    @Test
    void theFirstLineThatDoesNotParse_isMalformedWithItsNumber() {
        assertThat(failure(RUNNING + "\nnot json\n" + trailer(1) + "\n")).isEqualTo("malformed line 2");
        assertThat(failure("[1]\n" + trailer(0) + "\n")).isEqualTo("malformed line 1");
        assertThat(failure(RUNNING.replace("\"v\":1", "\"v\":2") + "\n" + trailer(1) + "\n")).isEqualTo("malformed line 1");
        assertThat(failure(RUNNING.replace("\"r1\"", "\"a b\"") + "\n" + trailer(1) + "\n")).isEqualTo("malformed line 1");
        assertThat(failure(RUNNING.replace("running", "lost") + "\n" + trailer(1) + "\n")).isEqualTo("malformed line 1");
        assertThat(failure(RUNNING.replace("alive", "dead") + "\n" + trailer(1) + "\n")).isEqualTo("malformed line 1");
        assertThat(failure(RUNNING.replace("\"x\"", "7") + "\n" + trailer(1) + "\n")).isEqualTo("malformed line 1");
        assertThat(failure(RUNNING + "\n\n" + trailer(1) + "\n")).as("a blank line").isEqualTo("malformed line 2");
        assertThat(failure(RUNNING + "\n" + trailer(1).replace("\"present\"", "\"maybe\"") + "\n")).isEqualTo("malformed line 2");
    }

    @Test
    void withTwoBadLines_theFirstIsReported() {
        assertThat(failure(RUNNING + "\nnot json\nalso bad\n" + trailer(1) + "\n")).isEqualTo("malformed line 2");
    }

    @Test
    void aSecondTrailer_orALineAfterIt_isMalformed() {
        assertThat(failure(RUNNING + "\n" + trailer(1) + "\n" + trailer(1) + "\n")).isEqualTo("malformed line 3");
        assertThat(failure(trailer(0) + "\n" + RUNNING + "\n")).isEqualTo("truncated");
    }

    /// An absent queue root has no ticket lines, so a trailer saying `absent` with `count` above 0 contradicts itself:
    /// malformed at the trailer's line, like every other trailer that does not parse. Mutation: drop the check → red.
    @Test
    void anAbsentQueue_withACount_isMalformedAtTheTrailer() {
        var absent = "{\"v\":1,\"end\":true,\"count\":1,\"queue\":\"absent\",\"at\":5}";

        assertThat(failure(RUNNING + "\n" + absent + "\n")).isEqualTo("malformed line 2");
        assertThat(failure(absent + "\n")).isEqualTo("malformed line 1");
    }

    @Test
    void anAbsentQueue_withNoLines_isAnEmptySuccess() {
        var absent = "{\"v\":1,\"end\":true,\"count\":0,\"queue\":\"absent\",\"at\":5}\n";
        var reply = TicketParse.parse(MAPPER, absent).unwrap();

        assertThat(reply.tickets()).isEqualTo(List.of());
        assertThat(reply.queue()).isEqualTo("absent");
    }

    /// The trailer `at` is kept (epoch seconds, the host's clock), no longer validated and
    /// dropped; an `at` that is not an integer, or beyond what an instant holds, is a malformed trailer. Mutation: drop
    /// `at` again → the reply has no time, red.
    @Test
    void theTrailerAt_isKept_andAnAtThatIsNoTime_isMalformed() {
        var reply = TicketParse.parse(MAPPER, RUNNING + "\n" + trailer(1) + "\n").unwrap();

        assertThat(reply.at()).isEqualTo(java.time.Instant.ofEpochSecond(1793700000L));
        assertThat(failure(RUNNING + "\n" + trailer(1).replace("1793700000", "\"1793700000\"") + "\n")).isEqualTo("malformed line 2");
        assertThat(failure(RUNNING + "\n" + trailer(1).replace("1793700000", "1.5") + "\n")).isEqualTo("malformed line 2");
        assertThat(failure(RUNNING + "\n" + trailer(1).replace("1793700000", "9223372036854775807") + "\n")).isEqualTo("malformed line 2");
    }
}
