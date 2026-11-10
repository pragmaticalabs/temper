package org.pragmatica.kiln.service.store.pg;

import java.util.List;

import org.pragmatica.kiln.service.store.BaseStore;
import org.pragmatica.kiln.service.store.BaseView;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.net.Transaction;


/// [BaseStore] over `base_run` and `gh_issue`: each write replaces its set in one transaction, so a reader never sees a
/// scope half replaced.
record PgBaseStore(Db db) implements BaseStore {
    private static final String RUN = "INSERT INTO base_run (scope, id, name, status, conclusion, head_sha, html_url, created_at, observed_at) "
                                    + "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)";

    private static final String ISSUE = "INSERT INTO gh_issue (number, title, html_url, created_at, observed_at) VALUES ($1, $2, $3, $4, $5)";

    @Override
    public Promise<Unit> runs(String scope, List<BaseView.Run> runs) {
        return db.inTransaction(tx -> replace(tx, scope, runs));
    }

    @Override
    public Promise<Unit> issues(List<BaseView.Issue> issues) {
        return db.inTransaction(tx -> replace(tx, issues));
    }

    private static Promise<Unit> replace(Transaction tx, String scope, List<BaseView.Run> runs) {
        return Sql.execute(tx, "DELETE FROM base_run WHERE scope = $1", scope)
                  .flatMap(_ -> Sql.sequence(runs,
                                             run -> insert(tx, scope, run)))
                  .mapToUnit();
    }

    private static Promise<Unit> replace(Transaction tx, List<BaseView.Issue> issues) {
        return Sql.execute(tx, "DELETE FROM gh_issue")
                  .flatMap(_ -> Sql.sequence(issues,
                                             issue -> insert(tx, issue)))
                  .mapToUnit();
    }

    private static Promise<Unit> insert(Transaction tx, String scope, BaseView.Run run) {
        return Sql.execute(tx,
                           RUN,
                           scope,
                           run.id(),
                           run.name(),
                           run.status(),
                           Sql.nullable(run.conclusion()),
                           run.headSha(),
                           run.htmlUrl(),
                           Sql.utc(run.createdAt()),
                           Sql.utc(run.observedAt()));
    }

    private static Promise<Unit> insert(Transaction tx, BaseView.Issue issue) {
        return Sql.execute(tx,
                           ISSUE,
                           issue.number(),
                           issue.title(),
                           issue.htmlUrl(),
                           Sql.utc(issue.createdAt()),
                           Sql.utc(issue.observedAt()));
    }
}
