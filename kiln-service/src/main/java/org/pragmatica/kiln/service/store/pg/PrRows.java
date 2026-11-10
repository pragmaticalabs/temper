package org.pragmatica.kiln.service.store.pg;

import java.util.List;

import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgRow;

import static org.pragmatica.lang.Option.option;


/// The `pr` table's columns and their reading into a [PullRequest]; shared by [PgPrStore] and [PgViewStore].
sealed interface PrRows {
    String TABLE = "pr";

    String COLUMNS = "p.number, p.title, p.state, p.draft, p.base_ref, p.head_ref, p.head_repo, p.head_sha, p.merged_at, p.closed_at, "
                   + "p.updated_at, to_json(p.labels)::text AS labels, to_json(p.closes)::text AS closes, "
                   + "to_json(p.part_of)::text AS part_of, p.observed_at";

    static Result<PullRequest> pullRequest(PgRow row) {
        return Result.all(PrNumber.prNumber(row.getLong("number")),
                          Rows.constant(PrState.class,
                                        row.getString("state")),
                          BranchName.branchName(row.getString("base_ref")),
                          BranchName.branchName(row.getString("head_ref")),
                          Sha.sha(row.getString("head_sha")),
                          Json.read(row.getString("labels"),
                                    Json.STRINGS),
                          numbers(row.getString("closes")),
                          numbers(row.getString("part_of")))
                     .map((number, state, base, headRef, head, labels, closes, partOf) -> new PullRequest(number,
                                                                                                          row.getString("title"),
                                                                                                          state,
                                                                                                          row.getBoolean("draft"),
                                                                                                          base,
                                                                                                          headRef,
                                                                                                          option(row.getString("head_repo")),
                                                                                                          head,
                                                                                                          Rows.instant(row,
                                                                                                                       "merged_at"),
                                                                                                          Rows.instant(row,
                                                                                                                       "closed_at"),
                                                                                                          row.getInstant("updated_at"),
                                                                                                          labels,
                                                                                                          closes,
                                                                                                          partOf))
                     .mapError(Rows.corrupt(TABLE));
    }

    static Result<List<PrNumber>> numbers(String json) {
        return Json.read(json, Json.LONGS).flatMap(PrRows::prNumbers);
    }

    private static Result<List<PrNumber>> prNumbers(List<Long> raw) {
        return Result.allOf(raw.stream().map(PrNumber::prNumber).toList());
    }

    static String[] texts(List<PrNumber> numbers) {
        return Rows.longs(numbers.stream().map(PrNumber::value).toList());
    }

    record unused() implements PrRows {}
}
