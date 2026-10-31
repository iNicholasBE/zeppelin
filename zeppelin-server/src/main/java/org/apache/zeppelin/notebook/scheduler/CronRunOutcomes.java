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

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the handler learned about a run, for the metrics filter: the note name and whether the run
 * was skipped. Job metadata cannot carry this because JobRunr clears user metadata when a job
 * succeeds, before the server filters run. Entries live from the start of a run until the filter
 * records it.
 */
class CronRunOutcomes {

  /** The outcome of one run. */
  static final class Outcome {
    private volatile String noteName = "unknown";
    private volatile boolean skipped;

    String getNoteName() {
      return noteName;
    }

    boolean isSkipped() {
      return skipped;
    }
  }

  private final Map<UUID, Outcome> outcomes = new ConcurrentHashMap<>();

  void noteName(UUID jobId, String noteName) {
    outcome(jobId).noteName = Objects.toString(noteName, "unknown");
  }

  void skipped(UUID jobId) {
    outcome(jobId).skipped = true;
  }

  /** Removes and returns the outcome of the run, empty when the handler never reported one. */
  Optional<Outcome> remove(UUID jobId) {
    return Optional.ofNullable(outcomes.remove(jobId));
  }

  int size() {
    return outcomes.size();
  }

  private Outcome outcome(UUID jobId) {
    return outcomes.computeIfAbsent(jobId, id -> new Outcome());
  }
}
