/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.notebook.scheduler;

import static org.jobrunr.dashboard.JobRunrDashboardWebServerConfiguration.usingStandardDashboardConfiguration;
import static org.jobrunr.scheduling.RecurringJobBuilder.aRecurringJob;
import static org.jobrunr.server.BackgroundJobServerConfiguration.usingStandardBackgroundJobServerConfiguration;

import org.apache.commons.lang3.StringUtils;
import org.jobrunr.dashboard.JobRunrDashboardWebServer;
import org.jobrunr.dashboard.JobRunrDashboardWebServerConfiguration;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.jobs.filters.JobFilter;
import org.jobrunr.jobs.filters.RetryFilter;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.server.JobActivator;
import org.jobrunr.server.configuration.BackgroundJobServerThreadType;
import org.jobrunr.server.configuration.DefaultBackgroundJobServerWorkerPolicy;
import org.jobrunr.storage.InMemoryStorageProvider;
import org.jobrunr.storage.Paging;
import org.jobrunr.utils.mapper.JsonMapper;
import org.jobrunr.utils.mapper.gson.GsonJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.inject.Inject;

import org.apache.zeppelin.conf.ZeppelinConfiguration;
import org.apache.zeppelin.notebook.Notebook;

/**
 * Schedules the cron runs of the notes with JobRunr.
 *
 * <p>Each note with a cron expression gets one recurring job on an in-memory store, which gives
 * the same behaviour as the Quartz {@code RAMJobStore} it replaces: schedules are rebuilt from the
 * notes at startup and runs that were in flight when the server stopped are not resumed. A run is
 * a {@link CronNoteJobRequest} handled by {@link CronNoteJobRequestHandler}.
 */
public class JobRunrSchedulerService implements SchedulerService {
  private static final Logger LOGGER = LoggerFactory.getLogger(JobRunrSchedulerService.class);

  /**
   * JobRunr enqueues the runs that fall in the next poll interval. One second keeps the runs within
   * a second of their cron moment; the in-memory store has no database to protect.
   */
  static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
  /** The same number of concurrent note runs the Quartz default thread pool allowed. */
  static final int WORKER_COUNT = 10;
  static final String RECURRING_JOB_ID_PREFIX = "note-";
  private static final String LABEL_PREFIX = "note:";
  private static final int PENDING_RUNS_PAGE_SIZE = 1000;

  private final ZeppelinConfiguration zConf;
  private final Notebook notebook;
  private final InMemoryStorageProvider storageProvider;
  private final BackgroundJobServer backgroundJobServer;
  private final JobRequestScheduler jobRequestScheduler;
  private final JobRunrDashboardWebServer dashboard;
  // recurring job ids carry a generation so that a changed schedule never reuses an id
  private final AtomicLong generation = new AtomicLong(System.currentTimeMillis());

  @Inject
  public JobRunrSchedulerService(ZeppelinConfiguration zConf, Notebook notebook) {
    this.zConf = zConf;
    this.notebook = notebook;

    JsonMapper jsonMapper = new GsonJsonMapper();
    this.storageProvider = new InMemoryStorageProvider();
    this.storageProvider.setJobMapper(new JobMapper(jsonMapper));

    CronRunOutcomes outcomes = new CronRunOutcomes();
    CronNoteJobRequestHandler handler = new CronNoteJobRequestHandler(notebook, outcomes);
    JobActivator jobActivator = new JobActivator() {
      @Override
      public <T> T activateJob(Class<T> type) {
        if (type == CronNoteJobRequestHandler.class) {
          return type.cast(handler);
        }
        throw new IllegalArgumentException("Unknown job type: " + type.getName());
      }
    };
    // a failed note run is not retried, as under Quartz: the next cron moment runs the note again
    List<JobFilter> jobFilters =
        Arrays.asList(new RetryFilter(0), new CronNoteMetricsFilter(outcomes));

    this.backgroundJobServer = new BackgroundJobServer(storageProvider, jsonMapper, jobActivator,
        usingStandardBackgroundJobServerConfiguration()
            .andName("zeppelin-notebook-cron")
            .andPollInterval(POLL_INTERVAL)
            .andBackgroundJobServerWorkerPolicy(new DefaultBackgroundJobServerWorkerPolicy(
                WORKER_COUNT, BackgroundJobServerThreadType.PlatformThreads)));
    this.backgroundJobServer.setJobFilters(jobFilters);
    this.backgroundJobServer.start();
    this.jobRequestScheduler = new JobRequestScheduler(storageProvider, jobFilters);
    this.dashboard = startDashboard(zConf, jsonMapper);
    // Start Init
    notebook.addInitConsumer(this::refreshCron);
  }

  private JobRunrDashboardWebServer startDashboard(ZeppelinConfiguration zConf,
      JsonMapper jsonMapper) {
    int port = zConf.getZeppelinNotebookCronDashboardPort();
    if (port <= 0) {
      return null;
    }
    JobRunrDashboardWebServerConfiguration configuration = usingStandardDashboardConfiguration()
        .andPort(port)
        .andAllowAnonymousDataUsage(false);
    String username = zConf.getZeppelinNotebookCronDashboardUsername();
    String password = zConf.getZeppelinNotebookCronDashboardPassword();
    if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
      configuration.andBasicAuthentication(username, password);
    } else {
      LOGGER.warn("The cron dashboard on port {} has no username and password configured: "
          + "keep the port internal", port);
    }
    JobRunrDashboardWebServer webServer =
        new JobRunrDashboardWebServer(storageProvider, jsonMapper, configuration);
    webServer.start();
    LOGGER.info("Cron dashboard started on port {}", port);
    return webServer;
  }

  @Override
  public boolean refreshCron(String noteId) {
    removeCron(noteId);
    try {
      return notebook.processNote(noteId,
        note -> {
          if (note == null) {
            LOGGER.warn("Skip refresh cron of note: {} because there's no such note", noteId);
            return false;
          }
          if (note.isTrash()) {
            LOGGER.warn("Skip refresh cron of note: {} because it is in trash", noteId);
            return false;
          }
          Map<String, Object> config = note.getConfig();
          if (config == null) {
            LOGGER.warn("Skip refresh cron of note: {} because its config is empty.", noteId);
            return false;
          }
          if (!note.isCronSupported(zConf)) {
            LOGGER.warn("Skip refresh cron of note {} because its cron is not enabled.", noteId);
            return false;
          }
          String cronExpr = (String) note.getConfig().get("cron");
          if (StringUtils.isBlank(cronExpr)) {
            LOGGER.warn("Skip refresh cron of note: {} because its cron expression is empty.",
                noteId);
            return false;
          }
          Map<String, Object> info = note.getInfo();
          info.put("cron", null);
          QuartzCron.Translation translation;
          try {
            translation = QuartzCron.translate(cronExpr);
          } catch (IllegalArgumentException e) {
            LOGGER.error("Fail to create cron trigger for note: {}", noteId, e);
            info.put("cron", e.getMessage());
            return false;
          }

          String recurringJobId = recurringJobIdPrefix(noteId) + generation.incrementAndGet();
          try {
            LOGGER.info("Trigger cron for note: {}, with cron expression: {}", noteId, cronExpr);
            jobRequestScheduler.createRecurrently(aRecurringJob()
                .withId(recurringJobId)
                .withName("Cron run of note " + noteId)
                .withLabels(LABEL_PREFIX + noteId)
                .withAmountOfRetries(0)
                .withCron(translation.getJobRunrExpression())
                .withZoneId(ZoneId.systemDefault())
                .withJobRequest(new CronNoteJobRequest(noteId, cronExpr, translation.getYears())));
            return true;
          } catch (RuntimeException e) {
            LOGGER.error("Fail to schedule cron job for note: {}", noteId, e);
            info.put("cron", "Scheduler Exception");
            return false;
          }
        });
    } catch (IOException e) {
      LOGGER.error("Fail to schedule cron job for note: {}", noteId, e);
      return false;
    }
  }

  @Override
  public int getJobsSize() {
    int size = 0;
    for (RecurringJob recurringJob : storageProvider.getRecurringJobs()) {
      if (recurringJob.getId().startsWith(RECURRING_JOB_ID_PREFIX)) {
        size++;
      }
    }
    return size;
  }

  @Override
  public void shutdown() {
    if (dashboard != null) {
      dashboard.stop();
    }
    backgroundJobServer.stop();
    storageProvider.close();
  }

  /**
   * Removes the recurring job of the note and the runs JobRunr already scheduled or enqueued for
   * it. A run that is being processed finishes; one that slips through is skipped by the handler
   * because the cron expression of the note no longer matches.
   */
  private void removeCron(String noteId) {
    String prefix = recurringJobIdPrefix(noteId);
    try {
      for (RecurringJob recurringJob : storageProvider.getRecurringJobs()) {
        if (recurringJob.getId().startsWith(prefix)) {
          jobRequestScheduler.deleteRecurringJob(recurringJob.getId());
        }
      }
      for (StateName state : new StateName[] {StateName.SCHEDULED, StateName.ENQUEUED}) {
        for (Job job : storageProvider.getJobList(state,
            Paging.AmountBasedList.ascOnUpdatedAt(PENDING_RUNS_PAGE_SIZE))) {
          if (job.getRecurringJobId().filter(id -> id.startsWith(prefix)).isPresent()) {
            jobRequestScheduler.delete(job.getId(), "The cron schedule of the note changed");
          }
        }
      }
    } catch (RuntimeException e) {
      LOGGER.error("Can't remove cron of note {}", noteId, e);
    }
  }

  private static String recurringJobIdPrefix(String noteId) {
    // recurring job ids may only contain [A-Za-z0-9-_(),.]
    return RECURRING_JOB_ID_PREFIX + noteId.replaceAll("[^A-Za-z0-9_(),.-]", "_") + "-g";
  }
}
