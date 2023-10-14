# AutoShutdown

A mod implements automatic server shutdown, adding `/shutdown`.

Made for servers! Clients don't have to install AutoShutdown to connect to the server.

## Usage

`/shutdown info` View automatic shutdown configuration _(Non Operator Command)_.

`/shutdown timer enable <enable-timer>` Enable or disable shutdown timer.

`/shutdown timer set <timer>` Set shutdown timer in format **_hh-mm-ss_**, e.g., **_07-30-45_**,
which is regarded as **_7:30:45_**.

`/shutdown delayer enable <enable-delayer>` Enable or disable shutdown delayer.

`/shutdown delayer set <delayer>` Set shutdown delayer in format **_hh-mm-ss_**, e.g., **_01-20-15_**,
which is regarded as **_1 hour, 20 minutes and 15 seconds_**.

`enable-timer` and `timer` can be set in **_auto-shutdown.properties_** as well.
