package org.pragmatica.temper.protocol.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;


/// The `200` body of `GET /api/v1/diff` (m2-design §3.3): `{"diff": DiffView}` when both sides were compared, or
/// `{"refused": DiffRefusal}` naming why not, typed so the CLI maps it to exit 8 or 9 without parsing text.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes({@JsonSubTypes.Type(value = DiffView.class, name = "diff"), @JsonSubTypes.Type(value = DiffRefusal.class, name = "refused")})
public sealed interface DiffAnswer permits DiffView, DiffRefusal {}
