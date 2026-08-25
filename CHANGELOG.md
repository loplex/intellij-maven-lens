<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-maven-lens Changelog

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Automatic attachment of Maven plugins and their internal `<dependencies>` as `MavenLens:` project libraries on every Maven re-import, so plugin internals are indexed and completable in the editor
