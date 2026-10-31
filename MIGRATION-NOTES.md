# Quartz to JobRunr: notebook cron scheduler

Branch `migrate-to-jobrunr`, base `apache/zeppelin` master at 5a74f53 (30 Oct 2026). Written for the
reviewers of this change; drop this file before an upstream pull request and move the content to the
PR description.

## Why

`zeppelin-server` pinned `org.quartz-scheduler:quartz:2.3.2` (released October 2019). That artifact
declares `com.mchange:c3p0:0.9.5.4` and `com.mchange:mchange-commons-java:0.2.15` as compile
dependencies, so both jars ship in every Zeppelin distribution although the cron scheduler used the
in-memory `RAMJobStore` and never opened a connection pool. In 2026 both libraries received HIGH
advisories (JNDI injection and unsafe deserialization: GHSA-m2cm-222f-qw44, GHSA-h84g-69h7-mw6v,
GHSA-5476-xc4j-rqcv). Upstream Quartz fixed it by making c3p0 `provided` in 2.5.x; there is no
patched 2.3.x, Quartz publishes no security advisories, has no `SECURITY.md`, and 27 of its 60
commits in the last twelve months came from one maintainer. The cron feature itself needs eight
classes of Quartz: a scheduler, one job type, one trigger per note, two listeners.

JobRunr 9.0.3 (`org.jobrunr:jobrunr`, two dependencies: `slf4j-api` and `asm`) replaces it.

## Edition

JobRunr OSS. The inventory has three rows where JobRunr Pro maps closer to Quartz; none of them is
used by the Zeppelin code or its presets, they only concern expressions users may have typed:

| Quartz behaviour notes could rely on | JobRunr OSS (this branch) | JobRunr Pro |
|---|---|---|
| `W` and `#` in cron expressions (`15W`, `6#3`) | rejected with a message when the schedule is saved (`400 wrong cron expressions.` on the REST API); existing notes with such an expression log an error at startup and show it in `note.info.cron` | advanced cron supports both |
| `L` (last day, last weekday) | accepted by the 9.0.3 parser; covered by `QuartzCronTest` | documented feature |
| More than 100 scheduled notes | the docs give 100 recurring jobs as the OSS guideline for SQL and NoSQL stores; the in-memory store has no hard limit in the code | no limit |

The migration was done on OSS; nothing in the code needs Pro. Reviewers who want `W`/`#` back can
switch the artifact to `org.jobrunr:jobrunr-pro` later without touching the scheduler service.

## Licence (read this first if you plan an upstream PR)

JobRunr OSS is licensed under the **LGPL v3**. The Apache Software Foundation lists the LGPL under
Category X: it may not be a compile or runtime dependency of an Apache release. Quartz was Apache 2.0.
`zeppelin-distribution/src/bin_license/LICENSE` has been updated to list JobRunr under its real
licence so the problem is visible, not hidden. Options for upstream: an optional module that is not
part of the binary release, a different Apache-licensed scheduler, or asking the JobRunr maintainers
about a licence exception for Zeppelin. The fork is fine; the PR to `apache/zeppelin` is not until
this is settled.

## Inventory (what Quartz did in Zeppelin)

| What | Where | Mapped to |
|---|---|---|
| `QuartzSchedulerService` creating a `StdSchedulerFactory` scheduler (RAMJobStore, 10 threads) | `notebook/scheduler` | `JobRunrSchedulerService`: `InMemoryStorageProvider`, `BackgroundJobServer` with 10 platform-thread workers, 1 s poll interval |
| One `JobDetail` + `CronTrigger` per note, identity `noteId`/`note`, `JobDataMap` with `noteId` **and the `Notebook` singleton** | `refreshCron` | one recurring job per note, id `note-<noteId>-g<generation>`, label `note:<noteId>`, `CronNoteJobRequest(noteId, cron, years)`; the `Notebook` is a constructor argument of the handler |
| `CronJob.execute`: load note, build `AuthenticationInfo` from `cronExecutingUser`/`cronExecutingRoles`, `note.runAll(auth, blocking, isolated)`, swallow exceptions into a result string | `CronJob` | `CronNoteJobRequestHandler.run`, same steps; a failing `runAll` is rethrown so the run shows as FAILED |
| `ZeppelinCronJobTriggerListerner.vetoJobExecution`: skip when a paragraph is running or pending | listener | the check is the first thing the handler does; the run ends as a quick SUCCEEDED whose dashboard log says `Skipped: a paragraph of note ... is running or pending`, and it is not counted in the metric (`CronRunOutcomes` carries the outcome from the handler to the filter) |
| `MetricCronJobListener`: Micrometer timer `cronjob{nodeid,name,result}` around each run | listener | `CronNoteMetricsFilter` (`JobServerFilter`), same timer, same tags, same log lines |
| `removeCron`: `scheduler.deleteJob(noteId)` on every refresh | `refreshCron` | delete every recurring job with the note's id prefix **plus** its SCHEDULED and ENQUEUED runs |
| `getJobsSize`: `getJobKeys(anyGroup)` | REST/tests | count of recurring jobs with the `note-` prefix |
| `CronExpression.isValidExpression` on `POST /api/notebook/cron/{noteId}` | `NotebookRestApi` | `QuartzCron.isValid`: translate, parse with JobRunr's parser, refuse schedules under 1 s |
| Cron expressions typed by users, UI presets `0 0/1 * * * ?` to `0 0 0 * * ?`, tests with `* * * * * ?` and `0 0 0 1 1 ? 2099` | notes' `config.cron` | `QuartzCron.translate`: `?` becomes `*`, day-of-week numbers shift from 1-7 to 0-6, the year field is kept as a set of years checked by the handler |
| `scheduler.start()` in the constructor, no `shutdown()` anywhere | `QuartzSchedulerService`, `ZeppelinServer` | `SchedulerService.shutdown()` added; `ZeppelinServer.shutdown` and the tests call it |
| `addInitConsumer(this::refreshCron)`: every note is (re)scheduled at startup | constructor | unchanged |
| No persistence, single node | `RAMJobStore` | `InMemoryStorageProvider`, single node (documented in the class javadoc) |

Not present in the Zeppelin code: `@DisallowConcurrentExecution`, misfire instructions, trigger
priorities, calendars, JDBC store, clustering, scheduling inside transactions, one-off jobs.

## Behaviour changes

1. **Run timing.** Quartz fired on the second. JobRunr enqueues the runs that fall within the next
   poll interval (1 s) and a worker picks them up: runs start between 0 and about 1.2 s after their
   cron moment (measured: 5 runs of an every-second schedule in 6 s, each about 1.2 s after the
   second).
2. **Failed runs are visible.** `note.runAll` throwing used to be a WARN log and a `failed` metric.
   It is still both, and the run is now FAILED in JobRunr with the stack trace (visible in the
   optional dashboard). Retries are set to 0: the next cron moment runs the note again, as before.
3. **Changing or removing a schedule.** Quartz deleted the job and its trigger atomically. JobRunr
   creates a run up to one second ahead; `removeCron` deletes those SCHEDULED and ENQUEUED runs, and
   the handler skips any run whose stored cron expression no longer matches the note's current one.
   A run that is already PROCESSING finishes, as under Quartz.
4. **Recurring job ids change on every refresh** (`note-<id>-g<n>`) because JobRunr remembers the
   last run per id inside the server and would catch up missed runs when an id is recreated.
5. **`W` and `#` are rejected** (see Edition). A note that already carries such an expression is
   not scheduled after the upgrade; the error is logged and stored in `note.info.cron` like an
   invalid expression was before.
6. **Year field.** Quartz stopped firing outside the year range. JobRunr runs the schedule every
   year; the handler returns at once outside the allowed years (a short SUCCEEDED run with
   `cronResult=skipped`, no `cronjob` metric). `0 0 0 1 1 ? 2099` therefore produces one no-op run
   per year on 1 January until 2099.
7. **Skipped runs log a start line.** The filter logs `Start cron job of note` before the handler
   decides to skip; Quartz skipped `jobToBeExecuted` for vetoed runs. The timer is not recorded for
   skipped runs, so the metric is unchanged.
8. **Day-of-week numbers.** Quartz `1-7` (Sunday to Saturday) is translated to JobRunr `0-6`. The
   translation is covered by `QuartzCronTest.shiftedDayOfWeekKeepsTheQuartzMeaning`.
9. **Smallest schedule.** Every second (`* * * * * ?`) still works (the in-memory store allows 1 s);
   anything faster is refused.
10. **Shutdown.** The scheduler is now stopped when Zeppelin shuts down (Quartz was never stopped,
    its threads died with the JVM). Runs in progress get 10 s to finish, then their worker thread is
    interrupted.
11. **Worker threads.** 10 platform threads, as the Quartz default pool. On JDK 21+ JobRunr would
    default to virtual threads; the parity choice is explicit in the code.
12. **New optional dashboard** on `zeppelin.notebook.cron.dashboard.port` (off by default), with
    optional HTTP basic auth. It shows note ids and names; keep the port internal without
    credentials. Anonymous usage statistics of the dashboard are switched off.
13. **Outcome registry.** The handler reports the note name and a skip to the filter through an
    in-process `CronRunOutcomes` map keyed by job id, so the filter does not take the note lock. Job
    metadata cannot be used for this: JobRunr clears user metadata when a run succeeds, before the
    filters run (verified live).
14. **No overlapping runs of one note, decided one second early.** JobRunr creates the next run of a
    recurring job one poll interval (1 s) before its moment and skips it when the previous run is
    still processing at that point. Quartz fired on the moment itself and the veto listener skipped
    the run only when a paragraph was still running. The difference shows when a run lasts almost as
    long as the interval: a note scheduled every 5 s whose run takes 4.1 s (interpreter JVM start)
    ran at :40 and :50 in the live check, where Quartz would have run it at :40, :45 and :50
    (the :45 run started 0.5 s after the previous one finished). Schedules in the UI presets start at
    one minute, where this does not occur.

## What was removed

- `org.quartz-scheduler:quartz` from `zeppelin-server/pom.xml` and the `quartz.scheduler.version`
  property; `maven-enforcer-plugin` now bans `org.quartz-scheduler`.
- `QuartzSchedulerService`, `CronJob`, `ZeppelinCronJobTriggerListerner`, `MetricCronJobListener`.
- `org.quartz` imports in `Notebook`, `NotebookRestApi`, `NotebookTest`, `NotebookRepoSyncTest`.
- `log4j.logger.org.quartz=WARN` in the test logging (now `org.jobrunr`).

## Tests

- `QuartzCronTest` (new): every UI preset, the test expressions, day-of-week shift, year parsing,
  rejections, messages.
- `CronNoteJobRequestTest` (new): JSON round trip of the job request through the Gson mapper the
  scheduler uses.
- `NotebookTest`, `NotebookServiceTest`: construct `JobRunrSchedulerService` instead of
  `QuartzSchedulerService` and stop it in `tearDown`; the cron scenarios (`testSchedule`,
  `testScheduleAgainstRunningAndPendingParagraph`, `testSchedulePoolUsage`, `testScheduleDisabled*`,
  `testCronNoteInTrash`, `testNoteUpdate`, `testCronRefreshedOnlyWhenTheExpressionChanges`) are
  unchanged.
- `AuthenticatedCronRestApiTest`, `ZeppelinRestApiTest`: unchanged; they exercise the REST
  validation with `0 0 0 1 1 ? 2099` and `a * * * * ?`.

## Verification

All on the pinned JDK 11 (Zulu 11.0.33), Maven wrapper 3.9.9, JobRunr 9.0.3. Baseline numbers come from a
separate worktree of the base commit with the same interpreters built (`zeppelin-interpreter-shaded`, `md`, `sh`).
Evidence files: `~/quartz-migration/round4/harness/` on the machine the migration was done on (not in the repo).

| Check | Baseline (Quartz) | This branch (JobRunr) |
|---|---|---|
| `./mvnw -pl zeppelin-server clean test` (full module) | 1711 tests, 2 failures, 7 errors, 20 skipped, 13:47 min | 1752 tests, 2 failures, 7 errors, 20 skipped, 14:26 min |
| Failing classes (identical list, all environment: Spark/YARN launcher, `DirAccessTest`, `RecoveryTest`, `NotebookServerTest`, `NotebookRestApiTest` and `ZeppelinRestApiTest` need `interpreter/spark`) | 6 classes | the same 6 classes |
| `NotebookTest` (cron scenarios with real interpreter JVMs) | 42 pass | 42 pass |
| `NotebookServiceTest`, `NotebookRepoSyncTest`, `AuthenticatedCronRestApiTest` | 13, 10, 3 pass | 13, 10, 3 pass |
| New: `QuartzCronTest`, `CronNoteJobRequestTest`, `CronNoteJobRequestHandlerTest` | n/a | 29, 3, 9 pass |
| `dependency:tree` | `quartz:2.3.2`, `c3p0:0.9.5.4`, `mchange-commons-java:0.2.15` | `jobrunr:9.0.3` only (ASM excluded) |
| `rg "org\.quartz"` over Java, properties, YAML and POMs | 8 Java files, 6 `log4j.properties`, 2 POMs | nothing except the enforcer ban |
| Enforcer ban | n/a | adding `org.quartz-scheduler:quartz` to `zeppelin-server/pom.xml` fails `validate`: "banned via the exclude/include list" |
| Checkstyle (`checkstyle:check`, project rules) | 1823 pre-existing violations in the module | 1824; none on a changed or new line except one fixed whitespace |
| Apache RAT (`-Prat`) | passes | passes (0 unapproved, 316 approved) |
| Catch-up bursts (`resulted in [2-9] scheduled jobs` in the logs) | n/a | 0 occurrences in all runs |

Live scenario (in-process `MiniZeppelinServer` with Shiro, the `md` interpreter and the dashboard on 8013, driven
through the REST API; three runs, the third with the real interpreter):

- `POST /api/notebook/cron/{noteId}` with `0/5 * * * * ?`: one recurring job `note-<id>-g<n>`, expression
  `0/5 * * * * *`, zone `Europe/Brussels` (system default).
- With a stub paragraph the runs started 0.35 s before to 0.62 s after their second (scheduled :55, :00, :05, :10;
  processing started :55.62, :59.65, :04.68, :09.73), four runs in 21 s. With the real `md` interpreter a run takes
  4.1 s, so every second cron moment is skipped (see behaviour change 14): two runs in 21 s.
- A paragraph set to PENDING: the next run ended SUCCEEDED in 1.4 ms with the dashboard log line
  `Skipped: a paragraph of note <id> is running or pending`; no `cronjob` metric was recorded.
- Edit to `0/10 * * * * ?`: the old recurring job is gone, exactly one new one with `0/10 * * * * *`, no burst.
- `DELETE /api/notebook/cron/{noteId}`: zero recurring jobs, zero SCHEDULED and zero ENQUEUED runs, the same number
  of succeeded runs 12 s later (the dashboard shows the pending run as DELETED with the reason). When the DELETE
  landed while a run was processing (real interpreter, 4.1 s run), that run finished and no further run was created,
  which is what Quartz's `deleteJob` did too.
- Dashboard: Recurring Jobs lists the note with its expression and next run; Succeeded shows the runs with latency
  and process duration; the server page shows `zeppelin-notebook-cron` with 10 workers; screenshots in the
  evidence folder.

Not verified: a failing paragraph through the REST API (the handler's failure path is covered by
`CronNoteJobRequestHandlerTest.aFailingNoteFailsTheRun` and the `Fail to run note` log line in that run); a restart
of a long-running server (the in-memory store rebuilds the schedules from the notes through the unchanged
`addInitConsumer` path, which `NotebookTest.testCronNoteInTrash` and the `NotebookServiceTest` cron tests exercise);
`ZeppelinRestApiTest.testCronDisabledInAnonymousMode` (the class needs the Spark interpreter and fails at the
baseline too); a build of the web UI (`zeppelin-web-angular` is untouched).

## Rollout

- No database, no migration: schedules are rebuilt from the notes at startup as before.
- Notes whose cron expression uses `W` or `#` stop running; grep the notebook storage for them
  before the upgrade (`"cron": "` entries containing `W` or `#`).
- `zeppelin-distribution/src/bin_license/LICENSE` lists JobRunr under LGPLv3 (see Licence).
