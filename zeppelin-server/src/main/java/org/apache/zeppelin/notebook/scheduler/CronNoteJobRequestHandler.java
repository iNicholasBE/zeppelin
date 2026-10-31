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

import org.apache.commons.lang3.StringUtils;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.jobrunr.server.runner.ThreadLocalJobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Year;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.zeppelin.notebook.Notebook;
import org.apache.zeppelin.user.AuthenticationInfo;

/**
 * Runs one cron execution of a note: every enabled paragraph, in order, as the cron executing
 * user. Replaces the Quartz {@code CronJob} and the trigger listener that vetoed runs while a
 * paragraph was still running or pending. Skipped runs end as a short SUCCEEDED run whose dashboard
 * log says why.
 */
public class CronNoteJobRequestHandler implements JobRequestHandler<CronNoteJobRequest> {
  private static final Logger LOGGER = LoggerFactory.getLogger(CronNoteJobRequestHandler.class);

  private final Notebook notebook;
  private final CronRunOutcomes outcomes;

  public CronNoteJobRequestHandler(Notebook notebook, CronRunOutcomes outcomes) {
    this.notebook = notebook;
    this.outcomes = outcomes;
  }

  @Override
  public void run(CronNoteJobRequest request) throws Exception {
    String noteId = request.getNoteId();
    JobContext jobContext = currentJobContext();
    UUID jobId = jobContext.getJobId();
    AtomicReference<Exception> failure = new AtomicReference<>();
    boolean noteExists = notebook.processNote(noteId, note -> {
      if (note == null) {
        LOGGER.warn("Failed to run CronJob of note: {} because there's no such note", noteId);
        return false;
      }
      String currentCron = (String) note.getConfig().get("cron");
      if (StringUtils.isBlank(currentCron) || !currentCron.equals(request.getCron())) {
        // this run was created before the schedule of the note changed or was removed
        skip(jobContext, "Skipped: the cron expression of note " + noteId + " is no longer "
            + request.getCron());
        return true;
      }
      if (!request.runsInYear(Year.now().getValue())) {
        skip(jobContext, "Skipped: " + request.getCron() + " does not include this year");
        return true;
      }
      if (note.haveRunningOrPendingParagraphs()) {
        LOGGER.warn("execution of the cron job is skipped because there is a running or pending "
            + "paragraph (noteId: {})", noteId);
        skip(jobContext, "Skipped: a paragraph of note " + noteId + " is running or pending");
        return true;
      }
      outcomes.noteName(jobId, note.getName());
      String cronExecutingUser = (String) note.getConfig().get("cronExecutingUser");
      String cronExecutingRoles = (String) note.getConfig().get("cronExecutingRoles");
      if (null == cronExecutingUser) {
        cronExecutingUser = "anonymous";
      }
      AuthenticationInfo authenticationInfo =
          new AuthenticationInfo(
              cronExecutingUser,
              StringUtils.isEmpty(cronExecutingRoles) ? null : cronExecutingRoles,
              null);
      try {
        note.runAll(authenticationInfo, true, true, new HashMap<>());
      } catch (Exception e) {
        LOGGER.warn("Fail to run note: {}", note.getName(), e);
        failure.set(e);
      }
      return true;
    });
    if (!noteExists) {
      throw new IllegalStateException("No such note: " + noteId);
    }
    if (failure.get() != null) {
      throw failure.get();
    }
  }

  private void skip(JobContext jobContext, String reason) {
    LOGGER.info("{} (run {})", reason, jobContext.getJobId());
    jobContext.logger().info(reason);
    outcomes.skipped(jobContext.getJobId());
  }

  /** The context of the run being processed; tests override it. */
  protected JobContext currentJobContext() {
    return ThreadLocalJobContext.getJobContext();
  }
}
