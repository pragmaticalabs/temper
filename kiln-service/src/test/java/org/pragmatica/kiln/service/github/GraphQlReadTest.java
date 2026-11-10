package org.pragmatica.kiln.service.github;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// [GraphQlRead]: only a read query to `/graphql` passes; an operation keyword is read at the operation level only, so
/// a field, a string or a comment saying "mutation" passes and a mutation anywhere at the top level does not.
class GraphQlReadTest {
    static String body(String query) {
        return "{\"query\":\"" + query.replace("\\", "\\\\")
                                      .replace("\"", "\\\"")
                                      .replace("\n", "\\n") + "\"}";
    }

    static boolean passes(String path, String json) {
        return GraphQlRead.checked(path, json).isSuccess();
    }

    @Test
    void readQueries_pass_includingKilnsOwn() {
        assertThat(passes("/graphql", body("query($q: String!) { search(query: $q) { nodes { __typename } } }"))).isTrue();
        assertThat(passes("/graphql", body("{ viewer { login } }"))).isTrue();
        assertThat(passes("/graphql", "{\"query\":\"query { a }\",\"variables\":{\"q\":\"mutation\"}}")).isTrue();
    }

    @Test
    void theWordMutation_inAFieldStringOrComment_isNotAnOperation() {
        assertThat(passes("/graphql", body("query { mutation { id } }"))).isTrue();
        assertThat(passes("/graphql", body("query { search(query: \"mutation { x }\") { id } }"))).isTrue();
        assertThat(passes("/graphql", body("# mutation { x }\nquery { a }"))).isTrue();
        assertThat(passes("/graphql", body("query { a(b: \"\"\"mutation\"\"\") }"))).isTrue();
    }

    @Test
    void mutationsAndSubscriptions_areRefused_whereverTheyStandAtTopLevel() {
        assertThat(passes("/graphql", body("mutation { addComment { id } }"))).isFalse();
        assertThat(passes("/graphql", body("query { a }\nmutation M($x: ID!) { b(x: $x) }"))).isFalse();
        assertThat(passes("/graphql", body("fragment F on X { a } mutation { b }"))).isFalse();
        assertThat(passes("/graphql", body("subscription { s }"))).isFalse();
        assertThat(passes("/graphql", body("query { a } # a comment\nmutation { b }"))).isFalse();
    }

    @Test
    void otherPaths_andBodiesThatAreNotAQuery_areRefused() {
        assertThat(passes("/repos/o/r/issues/1/comments", body("query { a }"))).isFalse();
        assertThat(passes("/graphql?x=1", body("query { a }"))).isFalse();
        assertThat(passes("/graphql", "not json")).isFalse();
        assertThat(passes("/graphql", "[]")).isFalse();
        assertThat(passes("/graphql", "{\"query\":42}")).isFalse();
        assertThat(passes("/graphql", "{\"body\":\"hi\"}")).isFalse();
    }

    @Test
    void operationWords_skipSelectionsVariablesStringsAndComments() {
        assertThat(GraphQlRead.operationWords("query Q($a: Int) { x(y: \"z\") } # c\nfragment F on T { w }")).containsExactly("query",
                                                                                                                              "Q",
                                                                                                                              "fragment",
                                                                                                                              "F",
                                                                                                                              "on",
                                                                                                                              "T");
    }
}
