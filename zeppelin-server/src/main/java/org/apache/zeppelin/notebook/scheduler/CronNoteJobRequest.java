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

import org.jobrunr.jobs.lambdas.JobRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;

/**
 * The job request of one cron run of a note. Only the note id travels with the job; the note
 * itself is loaded when the run starts. The Quartz expression and the years it was restricted to
 * let the handler recognise runs that belong to a schedule that has been changed or removed.
 */
public class CronNoteJobRequest implements JobRequest {

  private String noteId;
  private String cron;
  private List<Integer> years;

  // used by the JSON mapper
  private CronNoteJobRequest() {
  }

  public CronNoteJobRequest(String noteId, String cron, SortedSet<Integer> years) {
    this.noteId = Objects.requireNonNull(noteId, "noteId");
    this.cron = Objects.requireNonNull(cron, "cron");
    this.years = years == null ? null : new ArrayList<>(years);
  }

  public String getNoteId() {
    return noteId;
  }

  /** The Quartz expression the note had when the schedule was created. */
  public String getCron() {
    return cron;
  }

  /** The years the Quartz year field allowed, or null when every year is allowed. */
  public List<Integer> getYears() {
    return years;
  }

  public boolean runsInYear(int year) {
    return years == null || years.contains(year);
  }

  @Override
  public Class<CronNoteJobRequestHandler> getJobRequestHandler() {
    return CronNoteJobRequestHandler.class;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CronNoteJobRequest)) {
      return false;
    }
    CronNoteJobRequest that = (CronNoteJobRequest) o;
    return noteId.equals(that.noteId) && cron.equals(that.cron)
        && Objects.equals(years, that.years);
  }

  @Override
  public int hashCode() {
    return Objects.hash(noteId, cron, years);
  }

  @Override
  public String toString() {
    return "CronNoteJobRequest{noteId='" + noteId + "', cron='" + cron + "'}";
  }
}
