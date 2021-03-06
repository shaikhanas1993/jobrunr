package org.jobrunr.server.strategy;

import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.server.JobZooKeeper;
import org.jobrunr.storage.BackgroundJobServerStatus;
import org.jobrunr.storage.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicWorkDistributionStrategy implements WorkDistributionStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(BasicWorkDistributionStrategy.class);

    private final BackgroundJobServerStatus backgroundJobServerStatus;
    private final JobZooKeeper jobZooKeeper;

    public BasicWorkDistributionStrategy(BackgroundJobServer backgroundJobServer, JobZooKeeper jobZooKeeper) {
        this.backgroundJobServerStatus = backgroundJobServer.getServerStatus();
        this.jobZooKeeper = jobZooKeeper;
    }

    @Override
    public boolean canOnboardNewWork() {
        final double workQueueSize = jobZooKeeper.getWorkQueueSize();
        final double workerPoolSize = backgroundJobServerStatus.getWorkerPoolSize();
        final boolean canOnboardWork = (workQueueSize / workerPoolSize) < 0.7;
        return canOnboardWork;
    }

    @Override
    public PageRequest getWorkPageRequest() {
        final int workQueueSize = jobZooKeeper.getWorkQueueSize();
        final int workerPoolSize = backgroundJobServerStatus.getWorkerPoolSize();

        final int limit = workerPoolSize - workQueueSize;
        LOGGER.debug("Can onboard {} new work (workQueueSize = {}; workerPoolSize = {}).", limit, workQueueSize, workerPoolSize);
        return PageRequest.ascOnCreatedAt(limit);
    }
}
