package com.fintrack.apitoken.service;

import com.fintrack.apitoken.domain.ApiToken;
import com.fintrack.apitoken.repository.ApiTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the actual PAT insert in its own {@code REQUIRES_NEW} transaction/connection.
 *
 * <p>PostgreSQL aborts the entire enclosing transaction after any statement error, including a
 * unique-constraint violation — so if the insert ran in the same transaction as
 * {@link ApiTokenService#create}, catching {@code DataIntegrityViolationException} there and then
 * re-querying for the concurrent winner would itself fail ("current transaction is aborted").
 * Running the insert on its own suspended sub-transaction means only that connection rolls back
 * on conflict, leaving the caller's transaction free to look up the winner normally.
 */
@Component
@RequiredArgsConstructor
class ApiTokenWriter {

    private final ApiTokenRepository apiTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApiToken save(ApiToken token) {
        return apiTokenRepository.save(token);
    }
}
