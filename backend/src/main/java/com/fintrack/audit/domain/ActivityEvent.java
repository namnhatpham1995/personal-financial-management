package com.fintrack.audit.domain;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * MongoDB document capturing one activity event per authenticated mutation.
 * Schema intentionally open (Map meta) because different actions carry different fields.
 */
@Document(collection = "activity_log")
@CompoundIndex(name = "user_ts", def = "{'userId': 1, 'ts': -1}")
@Getter
@Builder
public class ActivityEvent {

    @Id
    private String id;

    private Long userId;

    /** Action identifier, e.g. "auth.login", "account.created", "budget.updated". */
    private String action;

    @Indexed
    private Instant ts;

    private String correlationId;

    /** Action-specific metadata — varies per action type. */
    private Map<String, Object> meta;
}
