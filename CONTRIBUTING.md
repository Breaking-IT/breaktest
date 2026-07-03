# How to contribute

Want to help BreakTest become a leaner continuation of Apache JMeter? Contributions are welcome.

BreakTest is focused on continuing the performance testing tool with lower memory usage, lower CPU usage, and a smaller operational footprint while preserving compatibility with existing JMeter test plans and integrations where practical.

## :beetle: Found a bug

Log it in GitHub issues:

* Use this repository's issue tracker for BreakTest-specific bugs.
* If the issue appears to be inherited from Apache JMeter, include the upstream JMeter version you compared against.

Be sure to include all relevant information, like the BreakTest revision, the Java version, and whether the behavior differs from upstream JMeter.
A test plan that caused the issue as well as any error messages are also very helpful.

## :question: Need help

Contact:

* Use this repository's discussions or issue tracker when available.
* For general JMeter-compatible test-plan questions, the broader JMeter community resources may still be useful.

## :bar_chart: What needs to be developed

See:

* Open issues in this repository.
* Performance, memory, CPU, startup, and distribution-size improvements are especially aligned with BreakTest's goals.

## Development setup

### Gradle

You might find useful Gradle commands in [gradle.md](gradle.md)

### <a name="intellij"></a>IntelliJ IDEA

You require IntelliJ 2018.3.1 or newer.

1. Open the build.gradle.kts file with IntelliJ IDEA and choose `Open as Project`
1. Make sure `Create separate module per source set` is selected
1. Make sure `Use default gradle wrapper` is selected
1. In the `File already exists` dialogue, choose `Yes` to overwrite
1. In the `Open Project` dialogue, choose `Delete Existing Project and Import`

### Eclipse

Eclipse can import Gradle projects automatically via `Import...->Gradle project` wizard.

Optionally you can generate an Eclipse project by running

    ./gradlew eclipse

The steps to import the sources (based on Eclipse 2019-06) into Eclipse are as follows:

1. Install `Eclipse IDE for Java Developers`
1. Install `Kotlin for Eclipse` plugin (BreakTest code uses Java and Kotlin)
1. Make sure you have a Java 21 compatible JDK configured in your workspace
1. Open `File->Import...`
1. Select `Existing Gradle Project` and click `Next`
1. Read `How to experience the best Gradle integration` and click `Next`
1. Then you might just click `Finish`

## :star2: Have a patch

The best way to make sure your issue or feature is addressed is to submit a patch.
We accept patches through:

* pull requests

However, before sending a patch, please make sure that the following applies:

* Your commit message is descriptive.
* Your patch doesn't have useless merge commits.
* Your coding style is similar to ours.
* Your patch is 100% tested. JUnit are welcome.
* All tests checks pass (run `./gradlew check`)
* You understand that we're very grateful for your patch!

## :heart: Adding something new

We want to enhance BreakTest while keeping the runtime lean and compatible with existing JMeter usage where practical.
The best way to work out your idea is to discuss it first in this repository.

Please, if you can, don't just throw us the code of a new feature; lets figure first together
what would be the best approach regarding the current architecture and future plans,
before any development.
This way we all get sure that your idea is aligned with the codebase, and you can enjoy
your happy coding even more :)

## :closed_book: Want to write docs

Documentation is very valuable to us.

It is located in **[xdocs](xdocs)** folder in XML format.

You can contribute as you would for code through patch or *PR* (pull request).
