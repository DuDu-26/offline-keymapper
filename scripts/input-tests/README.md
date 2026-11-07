# Native scanner regression

`ScannerSelfTest.cpp` compiles the actual production `input_reader.cpp` into an Android command-line test with fake directory, file, ioctl, readiness polling and read functions. It never opens real input nodes, requests real EVIOCGRAB, records key contents or injects touches. It does not require Root.

Supply one explicit ADB device:

```sh
./scripts/input-tests/run-scanner-test.sh emulator-5560
```

The script uses the project's existing NDK configuration, builds a test-only executable under ignored `research/input-tests/`, runs it, then removes the device executable. Tests cover directory/node/query permission errors, USB composite and Bluetooth inclusion, internal/touch/power exclusion, acquisition busy errors, pause without rescanning, cached diagnostics and inventory length limits. These fake-I/O tests do not establish physical keyboard/hub compatibility.

The high-rate regression queues 5,000 ordered synthetic mouse events and verifies that bounded polling yields without releasing exclusive ownership, while a second keyboard remains responsive. A twenty-device fixture verifies the 1,024-event total budget and rotating fairness. Continuous input and interrupted reads cannot monopolize a call. Real SYN_DROPPED, HUP, EOF and hard I/O failures remain distinct safe-pause conditions; diagnostics retain the node path, cause and aggregate counters, never actual key or motion contents. Activation discards at most 4,096 queued stale events per device so continuous input cannot hang startup. This bounded flush is not a kernel queue reset.

The production kernel capture now runs on a dedicated native worker, independent of Java injection, diagnostics serialization and socket writes. It reads at most 64 events per ready node per pass, polls outside the state lock and validates node identity before each read. The JNI consumer only waits for and exchanges a bounded 4,096-record user queue. Continuous same-device X/Y deltas coalesce across SYN_REPORT packets; keys, wheels and device changes are ordering boundaries. Real kernel loss and user queue exhaustion still release all exclusive input and report an explicit fault.

Capability scanning uses directory inode/mtime to skip unchanged inventories. Enumeration and capability queries run outside the capture state lock; diagnostics snapshot under the lock and serialize afterwards. The capture thread requests nice -20 only for its own TID; success, actual priority and failure errno are recorded. Failure to obtain priority does not claim success or prevent a fallback worker. No realtime or global scheduling setting is used.

The deterministic suite also pumps 48,000 producer events while the consumer never drains, checks motion sums and key boundaries, queue exhaustion, scan caching, and real thread startup/stop/join using fake kernel I/O and fake priority calls. These tests establish concurrency/queue behavior, not physical-device compatibility or game behavior.
