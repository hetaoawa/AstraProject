Astra Framework (testing; see version.txt)

Requires Java 21. Run start.ps1 (Windows) or sh start.sh (Linux/macOS).
On first start, config/application.yml is created. Edit it and set QQ_APP_ID
and QQ_BOT_SECRET before starting again. Install plugin JARs in plugins/.
The example plugin is in examples/ and is not enabled automatically.

SQLite is the default database. Redis is disabled in this distribution's
example configuration; enable it explicitly if needed. Keep config/, plugins/
and data/ when upgrading. Plugin changes require a restart.

Documentation: https://github.com/hetaoawa/AstraProject

Project-owned code is licensed under EPL-2.0. See LICENSE and NOTICE.
Corresponding source is available in the repository's version tags.
Third-party components retain their own licenses and notices.
