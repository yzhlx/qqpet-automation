# JVM unit-test runners

`gradle :app:testDebugUnitTest` cannot run in this project: the Gradle test
worker writes and reads its own classpath @argfile, and with the project at
`C:\Users\lu158\Desktop\dsh插件\养宠自动化\...` the worker fails to read it back,
so **every** test dies with `ClassNotFoundException`. These two scripts run the
same tests by handing the classpath to `java` in an argument file written with
the local (`mbcs`) code page, which the JVM does read correctly.

The build itself goes through the ASCII junction `C:\Users\lu158\dsh-qqpet`
(see `VirtualDisplay/gradle.properties` and `memory.md`); create it once if
missing:

    mklink /J C:\Users\lu158\dsh-qqpet "C:\Users\lu158\Desktop\dsh插件\养宠自动化"

## Current diagnostic workflow

These scripts preserve the local workaround used for this development snapshot.
They are machine-specific diagnostic helpers, not a complete fresh-checkout test
command.

    # Captures a Gradle worker argument file, for inspection only.
    python tools/jvm-tests/capture_cp.py

    # Requires an already prepared .build-tmp/cp.lst and compiled classes.
    python tools/jvm-tests/run_tests.py --log .build-tmp/junit-current.log

    # Run only the relevant compiled classes during a fix; empty matches fail.
    python tools/jvm-tests/run_tests.py '*Payroll*Test' --log .build-tmp/junit-payroll.log

`capture_cp.py` copies a worker file to `.build-tmp/captured-classpath.txt`. It
can select an older file and does not generate `cp.lst`. Before running the
second script, prepare `.build-tmp/cp.lst` as UTF-8, one current classpath entry
per line, using paths that exist on this machine. Include the freshly compiled
unit-test classes, the main-code compile jar described below, JUnit, and the
mockable Android jar and other dependencies. Rebuild the classes and compile jar
after source changes; running this helper alone does not compile anything.

`run_tests.py` writes `.build-tmp/junit.args` and starts JUnit in `app/`. These
scratch files and the classpath list are ignored and are not included in Git.
The helper prints JUnit's `EXIT` value and propagates that exit code to its own
process (updated 2026-10-21). Check the JUnit result as well as the process status;
a successful shell alone does not prove that the classpath used fresh classes.

The last recorded run returned `OK (273 tests)` and printed `EXIT 0`. This
validates the compiled classes used for that run; it is not device acceptance.

## Two constraints the scripts encode, both learned the hard way

- **Working directory is `VirtualDisplay/app`**, because `DialogDigitReaderTest`
  resolves `src/main/assets` relatively and otherwise reports a missing fixture.
- **Main code comes from `bundleDebugClassesToCompileJar`
  (`compile_app_classes_jar/classes.jar`), not `runtime_app_classes_jar`.** The
  runtime jar is not repackaged by `compileDebugUnitTestKotlin`, so pointing at
  it silently tests stale classes — which once produced 6 failures that looked
  like real regressions but were simply an old jar.

## Why the stub android.jar shapes the tests

The mockable `android.jar` used for JVM tests leaves `Rect`'s methods
(`height()`, `hashCode()`, `equals()`) unimplemented and its constructor ignores
its arguments, though the public fields do read and write. `org.json` is stubbed
the same way. So in tests:

- set `Rect` fields directly instead of using the constructor;
- avoid `Rect.height()` — use `bottom - top`;
- never put a `TextLine` in a `HashSet` (its equality runs `Rect.hashCode`);
  track consumed rows by index instead;
- JSON round-trips (`SessionPlan`, `CourseCatalog`) have no JVM coverage and can
  only be verified on a device.
