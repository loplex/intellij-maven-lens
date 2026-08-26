# Maven Lens - IntelliJ Platform Plugin

![Build](https://github.com/loplex/intellij-maven-lens/workflows/Build/badge.svg)

> Not yet published to JetBrains Marketplace - see [Installation](#installation) for how to try it now.

**Maven Lens** brings transparency to your Maven build configuration by making hidden plugin dependencies visible inside IntelliJ IDEA.

By default, IntelliJ IDEA indexes standard project dependencies, but leaves the classes and dependencies of Maven plugins hidden from the Project View, code completion, and search. **Maven Lens** automates this bridging process.

### Key Features

* **Automatic Attachment:** Automatically triggers after every Maven reload/import.
* **Plugin Visibility:** Registers all declared Maven plugins as standard Project Libraries.
* **Deep Dependency Resolution:** Resolves and attaches internal plugin dependencies
  (the `<dependencies>` block inside a plugin declaration).
* **Instant Code Exploration:** Enables standard IDE features like "Go to Class",
  code completion, and decompilation for plugin internals.

### How it works

Every time you reimport your Maven project, Maven Lens scans your `pom.xml` files, resolves the physical JAR locations within your local `.m2/repository`, and maps them to the project structure seamlessly without altering your original build files.


## Installation

- Manually:

  Download the [latest release](https://github.com/loplex/intellij-maven-lens/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

Once published to JetBrains Marketplace, it will also be installable directly via
<kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd>.

## License

Licensed under the [Apache License, Version 2.0](./LICENSE).

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
