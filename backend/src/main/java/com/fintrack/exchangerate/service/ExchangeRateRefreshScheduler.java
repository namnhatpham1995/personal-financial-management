package com.fintrack.exchangerate.service;

import com.fintrack.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers a daily exchange rate refresh at 01:30 UTC.
 *
 * <p>{@link SchedulerLock} ensures only one Railway replica executes this job at a time.
 * The lock is held for at least 30 minutes so a crashed node cannot immediately re-run the job,
 * and at most 1 hour to prevent indefinite lock starvation if the process dies mid-run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeRateRefreshScheduler {

    private final ExchangeRateService exchangeRateService;
    private final AppProperties       appProperties;

    @Scheduled(cron = "0 30 1 * * *")   // 01:30 UTC daily
    @SchedulerLock(name = "exchangeRateRefresh", lockAtMostFor = "PT1H", lockAtLeastFor = "PT30M")
    public void refresh() {
        String base = appProperties.getExchangeRate().getBase();
        log.info("Scheduled exchange rate refresh starting for base={}", base);
        exchangeRateService.refresh(base);
        log.info("Scheduled exchange rate refresh complete for base={}", base);
    }
}
