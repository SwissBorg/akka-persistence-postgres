Release Akka Persistence Postgres $VERSION$

<!--
# Release Train Issue Template for Akka Persistence Postgres

(Liberally copied and adopted from Scala itself https://github.com/scala/scala-dev/blob/b11cd2e4a4431de7867db6b39362bea8fa6650e7/notes/releases/template.md)

For every release, make a copy of this file named after the release, and expand the variables.
Ideally replacing variables could become a script you can run on your local machine.

Variables to be expanded in this template:
- $VERSION$=???

Key links:
  - SwissBorg/akka-persistence-postgres milestone: https://github.com/SwissBorg/akka-persistence-postgres/milestone/?
-->
### ~ 1 week before the release

- [ ] Check that open PRs and issues assigned to the milestone are reasonable
- [ ] Create a new milestone for the [next version](https://github.com/SwissBorg/akka-persistence-postgres/milestones)
- [ ] Check [closed issues without a milestone](https://github.com/SwissBorg/akka-persistence-postgres/issues?utf8=%E2%9C%93&q=is%3Aissue%20is%3Aclosed%20no%3Amilestone) and either assign them the 'upcoming' release milestone or `invalid/not release-bound`
- [ ] Close the [$VERSION$ milestone](https://github.com/SwissBorg/akka-persistence-postgres/milestones?direction=asc&sort=due_date)

### 1 day before the release

- [ ] Make sure all important / big PRs have been merged by now

### Preparing release notes in the documentation / announcement

- [ ] Review the [draft release notes](https://github.com/SwissBorg/akka-persistence-postgres)

### Cutting the release

- [ ] Wait until [master build finished](https://github.com/SwissBorg/akka-persistence-postgres/actions/) after merging the latest PR
- [ ] Check that all new features are documented in reference - docs folder.
- [ ] Update the [draft release](https://github.com/akka/akka-persistence-jdbc/releases) with the next tag version `v$VERSION$`, title and release description linking to announcement and milestone
- [ ] Check that GitHub Actions release build has executed successfully (GitHub Actions will start a [CI build](https://travis-ci.com/akka/akka-persistence-jdbc/builds) for the new tag and publish artifacts to Bintray)
- [ ] Go to [Bintray](https://bintray.com/akka/maven/akka-persistence-jdbc) and select the just released version // TODO Should we have this step?
- [ ] Go to the Maven Central tab, check the *Close and release repository when done* checkbox and sync with Sonatype (using your Sonatype TOKEN key and password) // TODO should we sync our Sonatype with maven central or is it happen automatically?

### Check availability

- [ ] Check [reference](https://doc.akka.io/docs/akka-persistence-jdbc/) documentation. Check that the reference docs are available.
- [ ] Check the release on [Maven central](https://repo1.maven.org/maven2/com/lightbend/akka/akka-persistence-jdbc_2.12/$VERSION$/)

### Afterwards

- Close this issue
