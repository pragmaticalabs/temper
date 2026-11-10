package org.pragmatica.temper.controller.review;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// GitHub as the review path sees it: reads as the controller App (a token minted with
/// `{"pull_requests":"read"}`, C.5), the one write as the reviewer, whose PAT file is read at every [#post].
public interface ReviewGitHub {
    /// The reviewer's login, to recognise its own reviews (case-insensitively).
    String reviewerLogin();
    /// `GET pulls/{n}` as the controller App.
    Promise<PullView> pull(String repo, long pr);
    /// `GET pulls/{n}/reviews` as the controller App.
    Promise<List<ReviewView>> reviews(String repo, long pr);
    /// `POST pulls/{n}/reviews` as the reviewer; fails with a [ReviewError].
    Promise<Posted> post(String repo, long pr, String sha, String event, String body);

    /// `state` is `open`, `closed`; `merged` is not asked.
    record PullView(String state, String headSha, String baseRef, String baseSha) {
        public boolean open() {
            return "open".equals(state);
        }
    }

    /// `state` is GitHub's: `APPROVED`, `CHANGES_REQUESTED`, `DISMISSED`, `COMMENTED`, `PENDING`.
    record ReviewView(long id, Option<String> login, String state, Option<String> commitId) {
        public boolean active() {
            return "APPROVED".equals(state) || "CHANGES_REQUESTED".equals(state);
        }
    }

    /// The review GitHub made, and `x-ratelimit-remaining` of the reply when it had one.
    record Posted(long id, Option<Integer> remaining) {}
}
