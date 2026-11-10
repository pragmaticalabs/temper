package org.pragmatica.temper.domain.model;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;


/// Submitter's owner tag, same alphabet as `BB_OWNER`; the 64-character cap is a guess (m0-design §3.1).
public record OwnerTag(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    public static Result<OwnerTag> ownerTag(String raw) {
        return ownerTag(raw, "submittedBy");
    }

    /// The same tag where the input names it otherwise (`createdBy` of a known-failure entry); `field` names it in the
    /// failure.
    public static Result<OwnerTag> ownerTag(String raw, String field) {
        return Values.matching(raw, PATTERN, field).map(OwnerTag::new);
    }

    @Override
    public String toString() {
        return value;
    }
}
