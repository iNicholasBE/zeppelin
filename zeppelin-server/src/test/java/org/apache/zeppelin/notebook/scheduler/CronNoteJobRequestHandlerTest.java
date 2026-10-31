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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobDetails;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.jobs.states.EnqueuedState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Year;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeSet;

import org.apache.zeppelin.notebook.Note;
import org.apache.zeppelin.notebook.Notebook;
import org.apache.zeppelin.user.AuthenticationInfo;

class CronNoteJobRequestHandlerTest {
  private static final String NOTE_ID = "2E1YA3X1U";
  private static final String CRON = "0 0/5 * * * ?";

  private Notebook notebook;
  private Note note;
  private Map<String, Object> config;
  private JobContext jobContext;
  private CronRunOutcomes outcomes;
  private CronNoteJobRequestHandler handler;

  @BeforeEach
  void setUp() throws Exception {
    notebook = mock(Notebook.class);
    note = mock(Note.class);
    config = new HashMap<>();
    config.put("cron", CRON);
    config.put("cronExecutingUser", "alice");
    config.put("cronExecutingRoles", "[\"analysts\"]");
    when(note.getConfig()).thenReturn(config);
    when(note.getName()).thenReturn("sales report");
    doAnswer(invocation -> {
      Notebook.NoteProcessor<?> processor = invocation.getArgument(1);
      return processor.process(note);
    }).when(notebook).processNote(eq(NOTE_ID), any());
    Job job = new Job(new JobDetails(new CronNoteJobRequest(NOTE_ID, CRON, null)),
        new EnqueuedState());
    jobContext = new JobContext(job) { };
    outcomes = new CronRunOutcomes();
    handler = new CronNoteJobRequestHandler(notebook, outcomes) {
      @Override
      protected JobContext currentJobContext() {
        return jobContext;
      }
    };
  }

  @Test
  void runsEveryParagraphAsTheCronExecutingUser() throws Exception {
    handler.run(new CronNoteJobRequest(NOTE_ID, CRON, null));

    ArgumentCaptor<AuthenticationInfo> auth = ArgumentCaptor.forClass(AuthenticationInfo.class);
    verify(note).runAll(auth.capture(), eq(true), eq(true), anyMap());
    assertEquals("alice", auth.getValue().getUser());
    assertEquals(new HashSet<>(Arrays.asList("analysts")), auth.getValue().getRoles());
    CronRunOutcomes.Outcome outcome = outcomes.remove(jobContext.getJobId()).get();
    assertEquals("sales report", outcome.getNoteName());
    assertFalse(outcome.isSkipped());
    assertEquals(0, outcomes.size());
  }

  @Test
  void defaultsToAnonymousWithoutCronExecutingUser() throws Exception {
    config.remove("cronExecutingUser");
    config.remove("cronExecutingRoles");
    handler.run(new CronNoteJobRequest(NOTE_ID, CRON, null));

    ArgumentCaptor<AuthenticationInfo> auth = ArgumentCaptor.forClass(AuthenticationInfo.class);
    verify(note).runAll(auth.capture(), eq(true), eq(true), anyMap());
    assertEquals("anonymous", auth.getValue().getUser());
  }

  @Test
  void skipsARunWhoseScheduleChanged() throws Exception {
    handler.run(new CronNoteJobRequest(NOTE_ID, "0 0 0/1 * * ?", null));

    verify(note, never()).runAll(any(), eq(true), eq(true), anyMap());
    assertSkipped();
  }

  @Test
  void skipsARunWhenTheCronWasRemoved() throws Exception {
    config.remove("cron");
    handler.run(new CronNoteJobRequest(NOTE_ID, CRON, null));

    verify(note, never()).runAll(any(), eq(true), eq(true), anyMap());
    assertSkipped();
  }

  @Test
  void skipsWhileAParagraphIsRunningOrPending() throws Exception {
    when(note.haveRunningOrPendingParagraphs()).thenReturn(true);
    handler.run(new CronNoteJobRequest(NOTE_ID, CRON, null));

    verify(note, never()).runAll(any(), eq(true), eq(true), anyMap());
    assertSkipped();
  }

  @Test
  void skipsOutsideTheYearsOfTheExpression() throws Exception {
    String cron = "0 0 0 1 1 ? 2099";
    config.put("cron", cron);
    handler.run(new CronNoteJobRequest(NOTE_ID, cron, new TreeSet<>(Arrays.asList(2099))));

    verify(note, never()).runAll(any(), eq(true), eq(true), anyMap());
    assertSkipped();
  }

  @Test
  void runsInsideTheYearsOfTheExpression() throws Exception {
    int thisYear = Year.now().getValue();
    String cron = "0 0 0 1 1 ? " + thisYear;
    config.put("cron", cron);
    handler.run(new CronNoteJobRequest(NOTE_ID, cron, new TreeSet<>(Arrays.asList(thisYear))));

    verify(note).runAll(any(), eq(true), eq(true), anyMap());
  }

  @Test
  void failsWhenTheNoteIsGone() throws Exception {
    doAnswer(invocation -> {
      Notebook.NoteProcessor<?> processor = invocation.getArgument(1);
      return processor.process(null);
    }).when(notebook).processNote(eq(NOTE_ID), any());
    assertThrows(IllegalStateException.class,
        () -> handler.run(new CronNoteJobRequest(NOTE_ID, CRON, null)));
  }

  @Test
  void aFailingNoteFailsTheRun() throws Exception {
    Exception failure = new Exception("paragraph p1 failed");
    doAnswer(invocation -> {
      throw failure;
    }).when(note).runAll(any(), eq(true), eq(true), anyMap());
    Exception thrown = assertThrows(Exception.class,
        () -> handler.run(new CronNoteJobRequest(NOTE_ID, CRON, null)));
    assertSame(failure, thrown);
  }

  private void assertSkipped() {
    assertTrue(outcomes.remove(jobContext.getJobId()).get().isSkipped());
  }
}
