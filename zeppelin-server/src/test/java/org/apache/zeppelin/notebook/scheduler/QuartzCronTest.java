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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jobrunr.scheduling.cron.CronExpression;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.TreeSet;

class QuartzCronTest {

  @ParameterizedTest
  @CsvSource({
      // the presets of the cron dialog in the web UI
      "0 0/1 * * * ?, 0 0/1 * * * *",
      "0 0/5 * * * ?, 0 0/5 * * * *",
      "0 0 0/1 * * ?, 0 0 0/1 * * *",
      "0 0 0/3 * * ?, 0 0 0/3 * * *",
      "0 0 0/6 * * ?, 0 0 0/6 * * *",
      "0 0 0/12 * * ?, 0 0 0/12 * * *",
      "0 0 0 * * ?, 0 0 0 * * *",
      // every second, used by the tests
      "* * * * * ?, * * * * * *",
      // Quartz numbers Sunday 1, JobRunr numbers Sunday 0
      "0 30 9 ? * 2-6, 0 30 9 * * 1-5",
      "0 0 12 ? * 1, 0 0 12 * * 0",
      "0 0 12 ? * 7, 0 0 12 * * 6",
      "0 0 8 ? * 2/2, 0 0 8 * * 1/2",
      "0 0 8 ? * MON-FRI, 0 0 8 * * MON-FRI",
      // the year field is dropped from the expression
      "0 0 0 1 1 ? 2099, 0 0 0 1 1 *",
      "0 0 0 1 1 ? *, 0 0 0 1 1 *"
  })
  void translatesQuartzExpressions(String quartz, String jobRunr) {
    QuartzCron.Translation translation = QuartzCron.translate(quartz);
    assertEquals(jobRunr, translation.getJobRunrExpression());
    // and JobRunr accepts the result
    new CronExpression(translation.getJobRunrExpression());
  }

  @Test
  void shiftedDayOfWeekKeepsTheQuartzMeaning() {
    // Quartz 2-6 is Monday to Friday
    QuartzCron.Translation translation = QuartzCron.translate("0 30 9 ? * 2-6");
    Instant saturday = Instant.parse("2026-10-31T00:00:00Z");
    Instant next = new CronExpression(translation.getJobRunrExpression())
        .next(saturday, saturday, ZoneOffset.UTC);
    assertEquals(DayOfWeek.MONDAY, next.atZone(ZoneOffset.UTC).getDayOfWeek());
  }

  @Test
  void yearsAreKeptForTheHandler() {
    assertNull(QuartzCron.translate("0 0 0 1 1 ?").getYears());
    assertNull(QuartzCron.translate("0 0 0 1 1 ? *").getYears());
    assertEquals(new TreeSet<>(Arrays.asList(2099)),
        QuartzCron.translate("0 0 0 1 1 ? 2099").getYears());
    assertEquals(new TreeSet<>(Arrays.asList(2026, 2027, 2028)),
        QuartzCron.translate("0 0 0 1 1 ? 2026-2028").getYears());
    assertEquals(new TreeSet<>(Arrays.asList(2026, 2028, 2030)),
        QuartzCron.translate("0 0 0 1 1 ? 2026-2030/2").getYears());
    assertEquals(new TreeSet<>(Arrays.asList(2026, 2030)),
        QuartzCron.translate("0 0 0 1 1 ? 2026,2030").getYears());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "a * * * * ?",          // not a number
      "* * * * *",            // 5 fields: Quartz needs 6 or 7
      "* * * * * * * *",      // 8 fields
      "0 0 12 15W * ?",       // nearest weekday
      "0 0 12 ? * 6#3",       // third Friday
      "0 0 12 ? * 8",         // day of week out of range
      "0 0 0 1 1 ? 1969",     // year out of range
      "0 0 0 1 1 ? 2030-2026",// inverted year range
      "",
      "   "
  })
  void rejectsWhatJobRunrCannotRun(String quartz) {
    assertFalse(QuartzCron.isValid(quartz));
    assertThrows(IllegalArgumentException.class, () -> QuartzCron.translate(quartz));
  }

  @Test
  void acceptsTheExpressionsTheRestApiTestsUse() {
    assertTrue(QuartzCron.isValid("0 0 0 1 1 ? 2099"));
    assertTrue(QuartzCron.isValid("* * * * * ?"));
    assertTrue(QuartzCron.isValid("0 0/5 * * * ?"));
  }

  @Test
  void rejectionMessagesNameTheProblem() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> QuartzCron.translate("0 0 12 15W * ?"));
    assertTrue(e.getMessage().contains("'W'"), e.getMessage());
    e = assertThrows(IllegalArgumentException.class, () -> QuartzCron.translate("0 0 12 ? * 6#3"));
    assertTrue(e.getMessage().contains("'#'"), e.getMessage());
  }
}
