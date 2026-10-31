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

import org.jobrunr.scheduling.cron.CronExpression;
import org.jobrunr.scheduling.cron.InvalidCronExpressionException;

import java.time.Duration;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates the Quartz cron expressions that notes carry in their {@code cron} config entry into
 * the expression JobRunr runs them with.
 *
 * <p>Zeppelin keeps accepting Quartz syntax (6 or 7 fields, {@code ?}, day-of-week numbers 1 to 7,
 * an optional year field) so that existing notes keep their schedule. The differences that have
 * no JobRunr equivalent are rejected up front: {@code W} in the day-of-month field and {@code #}
 * in the day-of-week field.
 */
public final class QuartzCron {

  /** The smallest distance between two runs the in-memory JobRunr storage accepts. */
  static final Duration SMALLEST_INTERVAL = Duration.ofSeconds(1);

  private static final int MIN_YEAR = 1970;
  private static final int MAX_YEAR = 2199;

  // a single digit 1-7 that is not a step size (after '/'), not an occurrence (after '#') and not
  // part of a longer number
  private static final Pattern DAY_OF_WEEK_NUMBER = Pattern.compile("(?<![/#\\d])([1-7])(?!\\d)");

  private QuartzCron() {
  }

  /** A translated expression plus the years the Quartz expression restricted it to. */
  public static final class Translation {
    private final String jobRunrExpression;
    private final SortedSet<Integer> years;

    Translation(String jobRunrExpression, SortedSet<Integer> years) {
      this.jobRunrExpression = jobRunrExpression;
      this.years = years == null ? null : Collections.unmodifiableSortedSet(years);
    }

    /** The 6-field expression (seconds first) understood by JobRunr. */
    public String getJobRunrExpression() {
      return jobRunrExpression;
    }

    /** The years the Quartz year field allowed, or null when every year is allowed. */
    public SortedSet<Integer> getYears() {
      return years;
    }
  }

  /**
   * @return true when the expression is accepted by {@link #translate(String)}
   */
  public static boolean isValid(String quartzExpression) {
    try {
      translate(quartzExpression);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * @throws IllegalArgumentException with a message that can be shown to the user when the
   *     expression is not a Quartz cron expression or uses a feature JobRunr does not support
   */
  public static Translation translate(String quartzExpression) {
    if (quartzExpression == null || quartzExpression.trim().isEmpty()) {
      throw new IllegalArgumentException("Empty cron expression");
    }
    String[] fields = quartzExpression.trim().split("\\s+");
    if (fields.length < 6 || fields.length > 7) {
      throw new IllegalArgumentException("A cron expression needs 6 or 7 fields "
          + "(seconds minutes hours day-of-month month day-of-week [year]): " + quartzExpression);
    }
    String dayOfMonth = "?".equals(fields[3]) ? "*" : fields[3];
    String dayOfWeek = "?".equals(fields[5]) ? "*" : shiftDayOfWeekNumbers(fields[5]);
    if (dayOfMonth.toUpperCase().contains("W")) {
      throw new IllegalArgumentException(
          "'W' (nearest weekday) is not supported in the day-of-month field: " + quartzExpression);
    }
    if (dayOfWeek.contains("#")) {
      throw new IllegalArgumentException(
          "'#' (n-th weekday of the month) is not supported in the day-of-week field: "
          + quartzExpression);
    }
    SortedSet<Integer> years = fields.length == 7 ? parseYears(fields[6], quartzExpression) : null;

    String jobRunrExpression =
        String.join(" ", fields[0], fields[1], fields[2], dayOfMonth, fields[4], dayOfWeek);
    CronExpression parsed;
    try {
      parsed = new CronExpression(jobRunrExpression);
    } catch (InvalidCronExpressionException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    if (parsed.durationBetweenSchedules().compareTo(SMALLEST_INTERVAL) < 0) {
      throw new IllegalArgumentException(
          "The schedule must leave at least " + SMALLEST_INTERVAL.getSeconds()
          + " second between two runs: " + quartzExpression);
    }
    return new Translation(jobRunrExpression, years);
  }

  /** Quartz numbers the days 1 (Sunday) to 7 (Saturday); JobRunr numbers them 0 to 6. */
  private static String shiftDayOfWeekNumbers(String field) {
    Matcher matcher = DAY_OF_WEEK_NUMBER.matcher(field);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(result, String.valueOf(Integer.parseInt(matcher.group(1)) - 1));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  /**
   * Parses the optional Quartz year field: {@code *}, single years, ranges, lists and steps.
   *
   * @return the allowed years, or null when every year is allowed
   */
  private static SortedSet<Integer> parseYears(String field, String expression) {
    if ("*".equals(field)) {
      return null;
    }
    SortedSet<Integer> years = new TreeSet<>();
    for (String item : field.split(",")) {
      String range = item;
      int step = 1;
      int slash = item.indexOf('/');
      if (slash >= 0) {
        range = item.substring(0, slash);
        step = parseYearNumber(item.substring(slash + 1), 1, MAX_YEAR - MIN_YEAR, expression);
      }
      int from;
      int to;
      if ("*".equals(range)) {
        from = MIN_YEAR;
        to = MAX_YEAR;
      } else if (range.contains("-")) {
        String[] bounds = range.split("-", 2);
        from = parseYearNumber(bounds[0], MIN_YEAR, MAX_YEAR, expression);
        to = parseYearNumber(bounds[1], MIN_YEAR, MAX_YEAR, expression);
      } else {
        from = parseYearNumber(range, MIN_YEAR, MAX_YEAR, expression);
        to = slash >= 0 ? MAX_YEAR : from;
      }
      if (to < from) {
        throw new IllegalArgumentException("Invalid year range in cron expression: " + expression);
      }
      for (int year = from; year <= to; year += step) {
        years.add(year);
      }
    }
    return years;
  }

  private static int parseYearNumber(String value, int min, int max, String expression) {
    int number;
    try {
      number = Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid year field in cron expression: " + expression, e);
    }
    if (number < min || number > max) {
      throw new IllegalArgumentException("Year out of range (" + min + "-" + max
          + ") in cron expression: " + expression);
    }
    return number;
  }
}
