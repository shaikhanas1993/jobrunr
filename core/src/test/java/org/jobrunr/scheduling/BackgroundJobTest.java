package org.jobrunr.scheduling;

import org.jobrunr.configuration.JobRunr;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobContext;
import org.jobrunr.jobs.states.ProcessingState;
import org.jobrunr.scheduling.cron.Cron;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.storage.PageRequest;
import org.jobrunr.storage.SimpleStorageProvider;
import org.jobrunr.stubs.TestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.time.Duration.ofSeconds;
import static java.time.Instant.now;
import static java.time.ZoneId.systemDefault;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.FIVE_SECONDS;
import static org.awaitility.Durations.ONE_MINUTE;
import static org.awaitility.Durations.TEN_SECONDS;
import static org.jobrunr.JobRunrAssertions.assertThat;
import static org.jobrunr.jobs.states.StateName.ENQUEUED;
import static org.jobrunr.jobs.states.StateName.FAILED;
import static org.jobrunr.jobs.states.StateName.PROCESSING;
import static org.jobrunr.jobs.states.StateName.SCHEDULED;
import static org.jobrunr.jobs.states.StateName.SUCCEEDED;
import static org.jobrunr.server.BackgroundJobServerConfiguration.usingStandardConfiguration;

public class BackgroundJobTest {

    private TestService testService;
    private SimpleStorageProvider storageProvider;
    private BackgroundJobServer backgroundJobServer;

    @BeforeEach
    void setUpTests() throws IOException {
        Files.deleteIfExists(Paths.get("/tmp/code.txt"));

        testService = new TestService();
        testService.reset();
        storageProvider = new SimpleStorageProvider();
        backgroundJobServer = new BackgroundJobServer(storageProvider, null, usingStandardConfiguration().andPollIntervalInSeconds(5));
        JobRunr.configure()
                .useStorageProvider(storageProvider)
                .useBackgroundJobServer(backgroundJobServer)
                .initialize();

        backgroundJobServer.start();
    }

    @AfterEach
    public void cleanUp() {
        backgroundJobServer.stop();
    }

    @Test
    void testEnqueueSystemOut() {
        JobId jobId = BackgroundJob.enqueue(() -> System.out.println("this is a test"));
        await().atMost(FIVE_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(storageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueue() {
        JobId jobId = BackgroundJob.enqueue(() -> testService.doWork());
        await().atMost(FIVE_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(storageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueueWithCustomObject() {
        final TestService.Work work = new TestService.Work(2, "some string", UUID.randomUUID());
        JobId jobId = BackgroundJob.enqueue(() -> testService.doWork(work));
        await().atMost(FIVE_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(storageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueueWithPath() {
        JobId jobId = BackgroundJob.enqueue(() -> testService.doWorkWithPath(Path.of("/tmp/jobrunr/example.log")));
        await().atMost(FIVE_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(storageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testEnqueueWithJobContextAndMetadata() {
        JobId jobId = BackgroundJob.enqueue(() -> testService.doWork(5, JobContext.Null));
        await().atMost(FIVE_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SUCCEEDED);
        Job jobById = storageProvider.getJobById(jobId);
        assertThat(jobById)
                .hasStates(ENQUEUED, PROCESSING, SUCCEEDED)
                .hasMetadata("test", "test");
    }

    @Test
    void testEnqueueStreamWithMultipleParameters() {
        Stream<UUID> workStream = getWorkStream();
        AtomicInteger atomicInteger = new AtomicInteger();
        BackgroundJob.enqueue(workStream, (uuid) -> testService.doWork(uuid.toString(), atomicInteger.incrementAndGet(), now()));

        await().atMost(FIVE_SECONDS).untilAsserted(() -> assertThat(storageProvider.countJobs(SUCCEEDED)).isEqualTo(5));
    }

    @Test
    void testEnqueueStreamWithWrappingObjectAsParameter() {
        AtomicInteger atomicInteger = new AtomicInteger();
        Stream<TestService.Work> workStream = getWorkStream()
                .map(uuid -> new TestService.Work(atomicInteger.incrementAndGet(), "some string " + uuid, uuid));

        BackgroundJob.enqueue(workStream, (work) -> testService.doWork(work));
        await().atMost(FIVE_SECONDS).untilAsserted(() -> assertThat(storageProvider.countJobs(SUCCEEDED)).isEqualTo(5));
    }

    @Test
    void testEnqueueStreamWithParameterFromWrappingObject() {
        AtomicInteger atomicInteger = new AtomicInteger();
        Stream<TestService.Work> workStream = getWorkStream()
                .map(uuid -> new TestService.Work(atomicInteger.incrementAndGet(), "some string " + uuid, uuid));

        BackgroundJob.enqueue(workStream, (work) -> testService.doWork(work.getUuid()));
        await().atMost(FIVE_SECONDS).untilAsserted(() -> assertThat(storageProvider.countJobs(SUCCEEDED)).isEqualTo(5));
    }

    @Test
    void testFailedJobAddsFailedStateAndScheduledThanksToDefaultRetryFilter() {
        JobId jobId = BackgroundJob.enqueue(() -> testService.doWorkThatFails());
        await().atMost(FIVE_SECONDS).untilAsserted(() -> assertThat(storageProvider.getJobById(jobId)).hasStates(ENQUEUED, PROCESSING, FAILED, SCHEDULED));
    }

    @Test
    void testScheduleWithZonedDateTime() {
        JobId jobId = BackgroundJob.schedule(() -> testService.doWork(), ZonedDateTime.now().plusSeconds(7));
        await().during(FIVE_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(TEN_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(storageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleWithOffsetDateTime() {
        JobId jobId = BackgroundJob.schedule(() -> testService.doWork(), OffsetDateTime.now().plusSeconds(7));
        await().during(FIVE_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(TEN_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(storageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleWithLocalDateTime() {
        JobId jobId = BackgroundJob.schedule(() -> testService.doWork(), LocalDateTime.now().plusSeconds(7));
        await().during(FIVE_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(TEN_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(storageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleWithInstant() {
        JobId jobId = BackgroundJob.schedule(() -> testService.doWork(), now().plusSeconds(7));
        await().during(FIVE_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(TEN_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SUCCEEDED);
        assertThat(storageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleUsingDateTimeInTheFutureIsNotEnqueued() {
        JobId jobId = BackgroundJob.schedule(() -> testService.doWork(), now().plus(100, ChronoUnit.DAYS));
        await().during(FIVE_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SCHEDULED);
        await().atMost(FIVE_SECONDS).until(() -> storageProvider.getJobById(jobId).getState() == SCHEDULED);
        assertThat(storageProvider.getJobById(jobId)).hasStates(SCHEDULED);
    }

    @Test
    void testScheduleThatSchedulesOtherJobs() {
        JobId jobId = BackgroundJob.schedule(() -> testService.scheduleNewWork(5), now().plusSeconds(1));
        await().atMost(ONE_MINUTE).until(() -> storageProvider.countJobs(SUCCEEDED) == (5 + 1));
        assertThat(storageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testScheduleThatSchedulesOtherJobsSlowlyDoesNotBlockOtherWorkers() {
        JobId jobId = BackgroundJob.schedule(() -> testService.scheduleNewWorkSlowly(5), now().plusSeconds(1));
        await().atMost(ofSeconds(12)).until(() -> (storageProvider.countJobs(PROCESSING) + storageProvider.countJobs(SUCCEEDED)) > 1);
        assertThat(storageProvider.getJobById(jobId)).hasStates(SCHEDULED, ENQUEUED, PROCESSING);
    }

    @Test
    void testRecurringJob() {
        BackgroundJob.scheduleRecurringly(() -> testService.doWork(5), Cron.minutely());
        await().atMost(65, SECONDS).until(() -> storageProvider.countJobs(SUCCEEDED) == 1);

        final Job job = storageProvider.getJobs(SUCCEEDED, PageRequest.asc(0, 1)).get(0);
        assertThat(storageProvider.getJobById(job.getId())).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testRecurringJobWithId() {
        BackgroundJob.scheduleRecurringly("theId", () -> testService.doWork(5), Cron.minutely());
        await().atMost(65, SECONDS).until(() -> storageProvider.countJobs(SUCCEEDED) == 1);

        final Job job = storageProvider.getJobs(SUCCEEDED, PageRequest.asc(0, 1)).get(0);
        assertThat(storageProvider.getJobById(job.getId())).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testRecurringJobWithIdAndTimezone() {
        BackgroundJob.scheduleRecurringly("theId", () -> testService.doWork(5), Cron.minutely(), systemDefault());
        await().atMost(65, SECONDS).until(() -> storageProvider.countJobs(SUCCEEDED) == 1);

        final Job job = storageProvider.getJobs(SUCCEEDED, PageRequest.asc(0, 1)).get(0);
        assertThat(storageProvider.getJobById(job.getId())).hasStates(SCHEDULED, ENQUEUED, PROCESSING, SUCCEEDED);
    }

    @Test
    void testDeleteOfRecurringJob() {
        String jobId = BackgroundJob.scheduleRecurringly(() -> testService.doWork(5), Cron.minutely());
        BackgroundJob.deleteRecurringly(jobId);
        await().atMost(61, SECONDS).until(() -> storageProvider.countJobs(ENQUEUED) == 0 && storageProvider.countJobs(SUCCEEDED) == 0);
        assertThat(storageProvider.getRecurringJobs()).isEmpty();
    }

    @Test
    void jobCanBeUpdatedInTheBackgroundAndThenGoToSucceededState() {
        JobId jobId = BackgroundJob.enqueue(() -> testService.doWorkThatTakesLong(10));
        await().atMost(3, SECONDS).until(() -> storageProvider.getJobById(jobId).hasState(PROCESSING));
        await().atMost(6, SECONDS).untilAsserted(() -> {
            final Job job = storageProvider.getJobById(jobId);
            ProcessingState processingState = job.getJobState();
            assertThat(processingState.getUpdatedAt()).isAfter(processingState.getCreatedAt());
            storageProvider.getJobById(jobId).hasState(PROCESSING);
        });
        await().atMost(6, SECONDS).untilAsserted(() -> assertThat(storageProvider.getJobById(jobId)).hasState(SUCCEEDED));
    }

    @Test
    void jobCanBeDeletedDuringProcessingState() {
        JobId jobId = BackgroundJob.enqueue(() -> testService.doWorkThatTakesLong(12));
        await().atMost(3, SECONDS).until(() -> storageProvider.getJobById(jobId).hasState(PROCESSING));

        final Job job = storageProvider.getJobById(jobId);
        job.delete();
        storageProvider.save(job);

        await().atMost(6, SECONDS).untilAsserted(() -> {
            assertThat(backgroundJobServer.getJobZooKeeper().getWorkQueueSize()).isZero();
        });
    }

    private Stream<UUID> getWorkStream() {
        return IntStream.range(0, 5)
                .mapToObj(i -> UUID.randomUUID());
    }
}
