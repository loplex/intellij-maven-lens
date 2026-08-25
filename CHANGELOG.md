<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-maven-lens Changelog

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Automatic attachment of Maven plugins and their full transitive dependency graph as `MavenLens:` project libraries on every Maven re-import, so plugin internals are indexed and completable in the editor
- On-demand download of plugin artifacts that are missing from the local repository, the same way an actual Maven build would fetch them
- Idempotent re-sync: unchanged `MavenLens:` libraries are left in place, and libraries no longer corresponding to any resolved plugin are removed along with their module entries, so repeated re-imports don't accumulate stale entries
