# Maven Lens - IntelliJ Platform Plugin

[![Build](https://github.com/loplex/intellij-maven-lens/actions/workflows/build.yml/badge.svg)](https://github.com/loplex/intellij-maven-lens/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/34153.svg)](https://plugins.jetbrains.com/plugin/34153-maven-lens)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/34153.svg)](https://plugins.jetbrains.com/plugin/34153-maven-lens)

**Maven Lens** makes the classes and dependencies of your Maven plugins visible inside IntelliJ IDEA.

IntelliJ IDEA indexes standard project dependencies, but leaves Maven plugins out of the Project
View, code completion and search. Maven Lens bridges that.

- **Automatic attachment** - runs after every Maven reload/import.
- **Plugin visibility** - registers every declared Maven plugin as a standard project library.
- **Deep dependency resolution** - resolves each plugin's full transitive graph the way Maven does
  for a real build, including any `<dependencies>` overrides declared on the plugin, and downloads
  what the local repository is missing.
- **Instant code exploration** - "Go to Class", code completion and decompilation reach plugin
  internals.
- **On/off switch** - a toggle in the Maven tool window toolbar detaches the libraries it attached;
  switching it back on re-resolves them right away, without waiting for the next Maven reload.

## Installation

- From inside the IDE:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > search for **Maven Lens** >
  <kbd>Install</kbd>

- From JetBrains Marketplace:

  Open the [plugin page](https://plugins.jetbrains.com/plugin/34153-maven-lens) and press <kbd>Install to ...</kbd>
  while your IDE is running. Every published build is also downloadable from its
  [versions page](https://plugins.jetbrains.com/plugin/34153-maven-lens/versions).

- From GitHub:

  Each release carries the plugin ZIP as an asset - see the
  [latest release](https://github.com/loplex/intellij-maven-lens/releases/latest).

A ZIP obtained either of the last two ways is installed with
<kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

### Compatibility

The plugin is built against IntelliJ IDEA 2025.2 and declares no upper bound, so it is offered to
**IntelliJ IDEA 2025.2 and newer, Community and Ultimate alike**.

JetBrains Marketplace derives the same range for Android Studio and lists Otter (2025.2.1) and newer
as compatible. That follows from the build number alone; this project does not test there.

## Building

- `./gradlew buildPlugin` - builds the installable plugin ZIP into `build/distributions/`
- `./gradlew runIde` - launches a sandboxed IDE with the plugin installed, for manual testing
- `./gradlew test` - runs the test suite
- `python3 -m unittest discover -s tools` - runs the release-tooling tests, which need the Python named
  in [`.python-version`](./.python-version) or newer; CI reads that same file rather than taking whatever
  the runner carries

## License

Licensed under the [Apache License, Version 2.0](./LICENSE).

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
