# StormFox

[Mago](https://mago.carthage.software/) integration for PhpStorm: format PHP code and inspect stylistic issues, inconsistencies, and code smells using your project's Mago configuration.

![PHP formatted by Mago](docs/marketplace/screenshots/ide-3.gif)

Requires PhpStorm 2024.3 or newer.

### Setup

1. Install [Mago](https://mago.carthage.software/) and make sure it runs on your machine.
2. Open **Settings | PHP | Quality Tools | Mago** and set **Mago executable** to the binary.

### Usage

Turn on **Use Mago formatter** in **Settings | PHP | Quality Tools | Mago**,
so the **Reformat code** action uses Mago instead of the default formatter for PHP files.

Turn on **Reformat code** in **Settings | Tools | Actions on Save**, so Mago is used to format PHP files on save.

Enable **PHP | Quality tools | Mago validation** in **Settings | Editor | Inspections**
to show Mago Linter findings in the editor.

### Things to know

* While the formatter is on, Mago replaces the IDE code style for PHP files: the
  **Settings | Editor | Code Style | PHP** page no longer affects them.
* Only whole files are formatted. Mago cannot format a fragment, so selections and "changed lines
  only" ranges are handled by the IDE's own formatter.

### Upcoming Features

* [Analyzer](https://mago.carthage.software/1.59.0/en/tools/analyzer/overview/) support.
* [Guard](https://mago.carthage.software/1.59.0/en/tools/guard/overview/) support.
