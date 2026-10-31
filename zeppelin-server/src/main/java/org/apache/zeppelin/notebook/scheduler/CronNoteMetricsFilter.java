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

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobDetails;
import org.jobrunr.jobs.filters.JobServerFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records the {@code cronjob} timer per note run, as the Quartz job listener did. A run that the
 * handler skipped (schedule changed, paragraph still running) is not recorded, like a vetoed
 * Quartz execution.
 */
public class CronNoteMetricsFilter implements JobServerFilter {
  private static final Logger LOGGER = LoggerFactory.getLogger(CronNoteMetricsFilter.class);

  private static final String RESULT_SUCCEEDED = "succeeded";
  private static final String RESULT_FAILED = "failed";

  private final Map<UUID, Timer.Sample> cronJobTimerSamples = new ConcurrentHashMap<>();
  private final CronRunOutcomes outcomes;

  public CronNoteMetricsFilter(CronRunOutcomes outcomes) {
    this.outcomes = outcomes;
  }

  @Override
  public void onProcessing(Job job) {
    noteId(job).ifPresent(noteId -> {
      LOGGER.info("Start cron job of note: {}", noteId);
      cronJobTimerSamples.put(job.getId(), Timer.start(Metrics.globalRegistry));
    });
  }

  @Override
  public void onProcessingSucceeded(Job job) {
    record(job, RESULT_SUCCEEDED);
  }

  @Override
  public void onProcessingFailed(Job job, Exception e) {
    record(job, RESULT_FAILED);
  }

  private void record(Job job, String result) {
    Optional<String> noteId = noteId(job);
    if (!noteId.isPresent()) {
      return;
    }
    Timer.Sample sample = cronJobTimerSamples.remove(job.getId());
    Optional<CronRunOutcomes.Outcome> outcome = outcomes.remove(job.getId());
    if (outcome.isPresent() && outcome.get().isSkipped()) {
      return;
    }
    String noteName = outcome.map(CronRunOutcomes.Outcome::getNoteName).orElse("unknown");
    LOGGER.info("cron job of noteId {} executed with result {}", noteId.get(), result);
    if (sample != null) {
      Tag noteIdTag = Tag.of("nodeid", noteId.get());
      Tag nameTag = Tag.of("name", noteName);
      Tag statusTag = Tag.of("result", result);
      sample.stop(Metrics.timer("cronjob", Tags.of(noteIdTag, nameTag, statusTag)));
    } else {
      LOGGER.warn("No Timer.Sample for NoteId {} found", noteId.get());
    }
  }

  /** @return the note id when the job is a cron run of a note, empty for any other job */
  static Optional<String> noteId(Job job) {
    JobDetails jobDetails = job.getJobDetails();
    if (!CronNoteJobRequestHandler.class.getName().equals(jobDetails.getClassName())
        || jobDetails.getJobParameters().isEmpty()) {
      return Optional.empty();
    }
    Object request = jobDetails.getJobParameters().get(0).getObject();
    if (request instanceof CronNoteJobRequest) {
      return Optional.of(((CronNoteJobRequest) request).getNoteId());
    }
    return Optional.empty();
  }
}
