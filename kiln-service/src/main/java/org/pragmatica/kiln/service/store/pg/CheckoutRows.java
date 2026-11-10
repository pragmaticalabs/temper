package org.pragmatica.kiln.service.store.pg;

import java.util.List;

import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.Derived;
import org.pragmatica.kiln.core.checkout.LinkRule;
import org.pragmatica.kiln.core.checkout.PrLink;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.BranchTip;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.service.store.CheckoutRow;
import org.pragmatica.kiln.service.store.DerivedRow;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgRow;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// `checkout` (with its common dir's `stash`) and `checkout_derived` rows; shared by [PgCheckoutStore] and
/// [PgViewStore].
sealed interface CheckoutRows {
    String TABLE = "checkout";
    String DERIVED_TABLE = "checkout_derived";

    String COLUMNS = "c.host, c.path, c.common_dir, c.head_sha, c.branch, c.remote_url, c.branches::text AS branches, c.modified, "
                   + "c.untracked, c.op, c.activity_at, to_json(c.processes)::text AS processes, c.maven, c.salvage_refs, c.facts_pass, "
                   + "c.facts_at, c.listed_at, c.size_kb, c.size_at, s.count AS stash";

    String FROM = " FROM checkout c LEFT JOIN stash s ON s.host = c.host AND s.common_dir = c.common_dir";

    String DERIVED_COLUMNS = "d.status, d.preservation, to_json(d.reasons)::text AS reasons, d.at_base, "
                           + "to_json(d.linked_prs)::text AS linked_prs, d.link_rule, d.facts_at AS derived_facts_at, d.mirror_at, "
                           + "d.prs_at, d.derived_at";

    String DERIVED_JOIN = " LEFT JOIN checkout_derived d ON d.host = c.host AND d.path = c.path";
    /// The status a count or a page files a checkout under: its derived status, and `UNSCANNED` while it has no full
    /// facts (Q-5) or has not been derived yet [guess: the derivation follows every pass, so the second case is brief].
    String STATUS = "(CASE WHEN c.facts_at IS NULL OR d.status IS NULL THEN 'UNSCANNED' ELSE d.status END)";
    String PRESERVED = "PRESERVED";
    String HOST_BARE_ONLY = "HOST_BARE_ONLY";
    String UNPRESERVED = "UNPRESERVED";

    static Result<CheckoutRow> row(PgRow row) {
        return Result.all(Rows.host(row.getString("host")),
                          CheckoutPath.checkoutPath(row.getString("path")),
                          facts(row),
                          Rows.optional(Rows.text(row, "facts_pass"),
                                        CheckoutRows::passKind))
                     .map((host, path, facts, pass) -> new CheckoutRow(host,
                                                                       path,
                                                                       row.getString("common_dir"),
                                                                       row.getInstant("activity_at"),
                                                                       facts,
                                                                       pass,
                                                                       Rows.instant(row, "facts_at"),
                                                                       row.getInstant("listed_at"),
                                                                       option(row.getLong("size_kb")),
                                                                       Rows.instant(row, "size_at")))
                     .mapError(Rows.corrupt(TABLE));
    }

    private static Result<PassKind> passKind(String raw) {
        return Rows.constant(PassKind.class, raw);
    }

    /// Full facts exist once a pass gave them (`facts_at` set); a stub-only row has none (`UNSCANNED`).
    private static Result<Option<CheckoutFacts>> facts(PgRow row) {
        return Rows.flip(Rows.instant(row, "facts_at").map(_ -> fullFacts(row)));
    }

    /// The facts of the last full-facts pass, with the row's `activity_at`: a later stub line refreshes it, so a status
    /// never rests on an older activity than kiln has seen (and the row joins the next hot set, §3.2).
    private static Result<CheckoutFacts> fullFacts(PgRow row) {
        return Result.all(CheckoutPath.checkoutPath(row.getString("path")),
                          Rows.optional(Rows.text(row, "head_sha"),
                                        Sha::sha),
                          Rows.optional(Rows.text(row, "branch"),
                                        BranchName::branchName),
                          Rows.required(row, "branches").flatMap(CheckoutRows::tips),
                          Rows.required(row, "processes").flatMap(json -> Json.read(json, Json.LONGS)),
                          option(row.getInt("stash")).toResult(Rows.NULL_COLUMN.apply("stash")))
                     .map((path, head, branch, branches, processes, stash) -> new CheckoutFacts(path,
                                                                                                head,
                                                                                                branch,
                                                                                                Rows.text(row,
                                                                                                          "remote_url"),
                                                                                                branches,
                                                                                                row.getInt("modified"),
                                                                                                row.getInt("untracked"),
                                                                                                Rows.text(row, "op"),
                                                                                                row.getInstant("activity_at"),
                                                                                                processes,
                                                                                                row.getBoolean("maven"),
                                                                                                row.getString("common_dir"),
                                                                                                stash,
                                                                                                option(row.getInt("salvage_refs"))));
    }

    private static Result<List<BranchTip>> tips(String json) {
        return Json.read(json, Json.BRANCHES).flatMap(CheckoutRows::tips);
    }

    private static Result<List<BranchTip>> tips(List<Json.BranchJson> stored) {
        return Result.allOf(stored.stream().map(CheckoutRows::tip).toList());
    }

    private static Result<BranchTip> tip(Json.BranchJson stored) {
        return Result.all(BranchName.branchName(stored.name()),
                          Sha.sha(stored.tip()))
                     .map(BranchTip::new);
    }

    static List<Json.BranchJson> json(List<BranchTip> tips) {
        return tips.stream()
                   .map(tip -> new Json.BranchJson(tip.name().value(),
                                                   tip.tip().value()))
                   .toList();
    }

    /// The derivation stored for the row, absent until derived (columns [#DERIVED_COLUMNS]).
    static Result<Option<DerivedRow>> derived(PgRow row) {
        return Rows.flip(Rows.instant(row, "derived_at").map(_ -> derivedRow(row))).mapError(Rows.corrupt(DERIVED_TABLE));
    }

    private static Result<DerivedRow> derivedRow(PgRow row) {
        return Result.all(CheckoutPath.checkoutPath(row.getString("path")),
                          Rows.constant(CheckoutStatus.class,
                                        row.getString("status")),
                          preservation(row),
                          PrRows.numbers(row.getString("linked_prs")),
                          Rows.constant(LinkRule.class,
                                        row.getString("link_rule")))
                     .map((path, status, preservation, prs, rule) -> new DerivedRow(path,
                                                                                    new Derived(status,
                                                                                                preservation,
                                                                                                new PrLink(prs, rule),
                                                                                                option(row.getBoolean("at_base"))),
                                                                                    Rows.instant(row, "derived_facts_at"),
                                                                                    Rows.instant(row, "mirror_at"),
                                                                                    Rows.instant(row, "prs_at"),
                                                                                    row.getInstant("derived_at")));
    }

    private static Result<Preservation> preservation(PgRow row) {
        return switch (row.getString("preservation")) {
            case PRESERVED -> success(new Preservation.Preserved());
            case HOST_BARE_ONLY -> success(new Preservation.HostBareOnly());
            case UNPRESERVED -> Json.read(row.getString("reasons"), Json.STRINGS).map(Preservation.Unpreserved::new);
            case null, default -> Rows.UNKNOWN_VALUE.apply(row.getString("preservation")).result();
        };
    }

    static String preservation(Preservation preservation) {
        return switch (preservation) {
            case Preservation.Preserved() -> PRESERVED;
            case Preservation.HostBareOnly() -> HOST_BARE_ONLY;
            case Preservation.Unpreserved(var _) -> UNPRESERVED;
        };
    }

    static List<String> reasons(Preservation preservation) {
        return switch (preservation) {
            case Preservation.Unpreserved(var reasons) -> reasons;
            case Preservation.Preserved(), Preservation.HostBareOnly() -> List.of();
        };
    }

    record unused() implements CheckoutRows {}
}
