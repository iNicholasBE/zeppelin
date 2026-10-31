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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobDetails;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.jobs.states.EnqueuedState;
import org.jobrunr.utils.mapper.gson.GsonJsonMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.TreeSet;

/**
 * The request is persisted as JSON inside the job; this test guards the round trip through the
 * mapper the scheduler uses.
 */
class CronNoteJobRequestTest {

  private final JobMapper jobMapper = new JobMapper(new GsonJsonMapper());

  @Test
  void roundTripsThroughTheJobMapper() {
    CronNoteJobRequest request =
        new CronNoteJobRequest("2E1YA3X1U", "0 0 0 1 1 ? 2099", new TreeSet<>(Arrays.asList(2099)));
    Job job = enqueuedJob(request);

    Job back = jobMapper.deserializeJob(jobMapper.serializeJob(job));

    Object parameter = back.getJobDetails().getJobParameters().get(0).getObject();
    assertEquals(request, parameter);
    assertEquals(CronNoteJobRequestHandler.class.getName(), back.getJobDetails().getClassName());
    assertEquals("2E1YA3X1U", ((CronNoteJobRequest) parameter).getNoteId());
  }

  @Test
  void roundTripsWithoutYears() {
    CronNoteJobRequest request = new CronNoteJobRequest("2E1YA3X1U", "0 0/5 * * * ?", null);
    Job back = jobMapper.deserializeJob(jobMapper.serializeJob(enqueuedJob(request)));
    CronNoteJobRequest parameter =
        (CronNoteJobRequest) back.getJobDetails().getJobParameters().get(0).getObject();
    assertEquals(request, parameter);
    assertNull(parameter.getYears());
    assertTrue(parameter.runsInYear(2026));
  }

  @Test
  void yearRestrictionIsEvaluatedByTheHandler() {
    CronNoteJobRequest request =
        new CronNoteJobRequest("2E1YA3X1U", "0 0 0 1 1 ? 2099", new TreeSet<>(Arrays.asList(2099)));
    assertTrue(request.runsInYear(2099));
    assertFalse(request.runsInYear(2026));
  }

  private static Job enqueuedJob(CronNoteJobRequest request) {
    Job job = new Job(new JobDetails(request), new EnqueuedState());
    job.setJobName("Cron run of note " + request.getNoteId());
    return job;
  }
}
