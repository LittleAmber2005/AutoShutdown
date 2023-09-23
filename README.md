# AutoShutdown

A mod adding command `/sd` and implementing automatic server shutdown.

Made for servers! Clients don't have to install AutoShutdown to connect to the server.

## Usage

`/sd info` View automatic shutdown configuration _(Non Operator Command)_.

`/sd timer enable <enable-timer>` Enable or disable shutdown timer.

`/sd timer set <timer>` Set shutdown timer in format **_hh-mm-ss_**, e.g., **_07-30-45_**,
which is regarded as **_7:30:45_**.

`/sd delayer enable <enable-delayer>` Enable or disable shutdown delayer.

`/sd delayer set <delayer>` Set shutdown delayer in format **_hh-mm-ss_**, e.g., **_01-20-15_**,
which is regarded as **_1 hour, 20 minutes and 15 seconds_**.

`enable-timer` and `timer` can be set in **_auto-shutdown.properties_** as well.
