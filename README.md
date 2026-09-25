This repository contains the code for measurement research of mainstream SCF services using a case study approach.

The rapid development of Serverless computing function (SCF) has enabled an event-driven application development paradigm, providing users with on-demand and cost-efficient computing resources. The evolution of cloud services from Platform as a Service (PaaS) to Function as a Service (FaaS) offers a finer-grained model for resource provisioning and usage. Although SCF services offered by major CSP are functionally similar, they differ in interface design and usage patterns, and the lack of a unified management layer across CSP significantly increases the cost of comparison. To effectively understand these differences, users require a measurement framework that enables rapid evaluation of application-relevant characteristics across different CSP.

We focus on practical performance characteristics, including stability, cold-start behavior, parallel performance, and network performance, and further evaluates the effectiveness of combining SCF with another fundamental cloud service.

---

## Quick Start

### 1. Clone

```bash
git clone https://github.com/szu-security-group/cross-cloud-serverless-measurement-study.git
cd cross-cloud-serverless-measurement-study
```

### 2. Prerequisites

| # | Requirement |
|---|-------------|
| 1 | JDK 1.8+ (`java -version`) |
| 2 | Maven; record its full path if it is not on `PATH` |
| 3 | Test file `D:/testdata/testfile.txt` (~7 MB) |
| 4 | Alibaba Cloud only: a **Linux x86_64 JRE 11** tarball at `D:/tmp/jre11.tar.gz` (~43.5 MB). See below |
| 5 | One object-storage bucket per platform, **created in advance** — the code uploads into an existing bucket and never creates one. The cloud function itself does **not** need to exist: `deploy` calls `CreateFunction` and falls back to updating the code if the function is already there |

Create the test file:

```powershell
# Windows PowerShell
$size = 7475448; $bytes = new-object byte[] $size; (new-object Random).NextBytes($bytes); [IO.File]::WriteAllBytes("D:\testdata\testfile.txt", $bytes)
```

```bash
# Linux / macOS
dd if=/dev/urandom of=/root/testdata/testfile.txt bs=1024 count=7301
```

Get the Alibaba JRE (Alibaba only). Ali FC does not ship a JRE, so the deploy zip
carries one and its `bootstrap` unpacks it to `/tmp/jre` on the first cold start:

```powershell
Invoke-WebRequest -Uri "https://api.adoptium.net/v3/binary/latest/11/ga/linux/x64/jre/hotspot/normal/eclipse" -OutFile "D:\tmp\jre11.tar.gz"
```

Requirements for the tarball: **Linux x86_64**, contains `bin/java`, and has
exactly one top-level directory (so `--strip-components=1` lands `java` at
`/tmp/jre/bin/java`). Use a JRE rather than a JDK — a full JDK is ~4x larger and
the deploy zip goes over the cross-border link to Tokyo OSS, which is the
flakiest part of the whole flow.

### 3. Configure

Fill in `Properties-tencent`, `Properties-ali`, `Properties-aws` and
`Properties-azure`. Each file holds the credentials, region, bucket and function
name for one platform. See [Configuration](#configuration) for the field list.

Benchmark copies the matching platform file to `Properties` before each run, so
only the four per-platform files need editing.

### 4. Build

```bash
mvn package -Pdevelopment-Azure -DskipTests
```

Build artifact: `target/TPDSInSCF-1.0-SNAPSHOT_Benchmark-jar-with-dependencies.jar`.
The repository ships a pre-built JAR, so this step can be skipped unless you
changed the source.

### 5. Deploy

```bash
# upload handler code to the cloud (creates the function if it does not exist yet)
java -DskipBuild=true -cp target/TPDSInSCF-1.0-SNAPSHOT_Benchmark-jar-with-dependencies.jar com.fchen_group.TPDSInScf.Run.Benchmark deploy

# or one platform at a time
java -DskipBuild=true -cp target/TPDSInSCF-1.0-SNAPSHOT_Benchmark-jar-with-dependencies.jar com.fchen_group.TPDSInScf.Run.Benchmark deploy Tencent
```

Always pass `-DskipBuild=true`. Without it the program runs `mvn package` first,
which rewrites the very JAR the JVM is executing — on Windows the file is locked
and the build fails or produces a truncated JAR.

Azure is deployed differently: `deploy Azure` ignores the local JAR and goes
through the Maven plugin, which authenticates with the cached `az` CLI login.
Log in and pre-compile first, otherwise the plugin reports
`0 Azure Functions entry point(s) found`:

```bash
az login
mvn package -Pdevelopment-Azure -DskipTests -DbenchmarkJarName=azureprebuild
java -DskipBuild=true -cp target/TPDSInSCF-1.0-SNAPSHOT_Benchmark-jar-with-dependencies.jar com.fchen_group.TPDSInScf.Run.Benchmark deploy Azure
```

Alibaba is the flakiest: the deploy zip is ~111 MB and goes cross-border to a
Tokyo OSS bucket, split into 13 concurrent 8 MB parts (`Benchmark.java:659`).
If it keeps dying with `Connection reset by peer`, do the upload by hand —
`createAliZip` has already written the package to `target/function.zip`
(bootstrap + JAR + `jre11.tar.gz`):

1. Upload it to the bucket named by `bucketName` in `Properties-ali`, using the
   **object name `function.zip`** — `deployAli` hard-codes that key, so any other
   name is not found.
2. Point the FC function's code source at that OSS object (console: function →
   code → change source to the OSS location), or upload the ZIP directly in the
   FC console instead.

This needs no redeploy if you are only collecting data: the handler classes in
the cloud are unaffected by client-side changes.

### 6. Verify (fast)

```bash
java -DtestMode=true -cp target/TPDSInSCF-1.0-SNAPSHOT_Benchmark-jar-with-dependencies.jar com.fchen_group.TPDSInScf.Run.Benchmark audit Tencent
```

Runs in about 30 s at reduced parameters (see Test Mode below).
Note it exercises whatever is already deployed in the cloud, not the local JAR —
deploy first (step 5). A run that finishes without `VERIFY FAILED` means the protocol
and credentials are working.

### 7. Collect data

```bash
java -DskipBuild=true -DskipDeploy=true -cp target/TPDSInSCF-1.0-SNAPSHOT_Benchmark-jar-with-dependencies.jar com.fchen_group.TPDSInScf.Run.Benchmark audit Tencent
```

Run each test type per platform rather than using `all`, and run every command from
the project root — CSV output is written to the current working directory, and the
files are append-only (`Benchmark.java:53`).

---

## Measurement Settings

Everything needed to rerun the reported numbers. The [Parameter Matrix](#parameter-matrix)
below says *what* is swept; this section says *where, when and on which runtimes*.

### Measurement rounds

| Round | Dates | Notes |
|-------|-------|-------|
| Paper (§5) | 2025-01 / 2025-05 / 2025-10 | one block at 12:00 and one at 18:00 on each measurement day, 5 runs per block → 30 repetitions per configuration |
| Re-measurement | 2026-06-20 | first end-to-end rerun on all four platforms |
| Re-measurement | 2026-09-22 – 2026-09-25 | second round; adds `Total_Time_ms`, the idle cold-start trigger and the Azure memory probes |

The two 2026 rounds agree with each other within a few percent on the
configurations both cover. Where they disagree with the paper's numbers, the
CSVs keep every raw per-invocation row, so the difference can be re-derived
rather than taken on trust.

### Platform, region, runtime

| Platform | Region | Runtime | Handler | Memory tiers measured (audit) |
|----------|--------|---------|---------|-------------------------------|
| Tencent Cloud SCF | `ap-tokyo` | `Java8` | `TenHandle::mainHandler` | 128, 256, 512, 1408, 2176, 2944 |
| Alibaba Cloud FC | `ap-northeast-1` | `custom.debian10` + bundled JRE 11 | `AliHandle::handleRequest` | 128, 256, 512, 1024, 2048 |
| AWS Lambda | `ap-northeast-1` | `java11` | `AwsHandle::handleRequest` | 256, 512, 1024, 2048 |
| Azure Functions | `japanwest` | `Java11` (Windows, extension `~4`) | `Audit` (HTTP trigger) | fixed — thread sweep instead |

Region and runtime come from the `Properties-*` files; the Azure values also
appear in `pom.xml:482`. **The memory tiers differ per platform** and must match
the tiers present in the data being reproduced — see
[XML Configuration](#xml-configuration).

Azure is the one deviation from the paper's unified Tokyo choice: it is deployed
to `japanwest` (Osaka) because that is the region this subscription can deploy
Functions to, and its host is **Windows**, not Linux. See
[Azure is measured differently](#azure-is-measured-differently).

### Client-side stack

| Component | Version |
|-----------|---------|
| JDK | 1.8 (`pom.xml:14`) |
| Tencent COS / SCF | `cos_api 5.6.35`, `tencentcloud-sdk-java 3.1.1013`, `scf-java-events 0.0.1` |
| Alibaba OSS / FC | `aliyun-sdk-oss 3.17.4`, `fc20230330 4.1.2` |
| AWS S3 / Lambda | `aws-java-sdk-s3 1.12.770`, `aws-java-sdk-lambda 1.12.770`, `aws-lambda-java-core 1.2.3` |
| Azure Blob | `azure-storage-blob 12.25.0` |
| JSON | `fastjson 1.2.73` (`pom.xml:31-35`) |
| JNA | `5.14.0` — Windows Job Object and sandbox memory counters (`pom.xml:467-471`) |

These are the versions of the `development-Azure` profile, which is the profile
the fat JAR is built with (`mvn package -Pdevelopment-Azure`). The
`development-Ten` profile is `activeByDefault` and carries only a subset of the
SDKs; a JAR built with it loads no AWS/Ali/Azure client classes.

### Workload geometry

The audit workload is one ~7 MB file erasure-coded with Reed–Solomon over
GF(2⁸):

| Quantity | Value | Where |
|----------|-------|-------|
| Input file | 7,475,448 B (`D:/testdata/testfile.txt`) | `filePath` in `Properties-*` |
| Codec | RS(255, 223) — 223 data + 32 parity shards | `Benchmark.java:43-44` |
| Data shard | 223 B | `IntegrityAuditing.java:66` |
| Parity shard (authentication tag) | 32 B | — |
| Shard count | ⌈(7475448 + 4) / 223⌉ = **33,523** | `IntegrityAuditing.java:57-59` |
| `sourceFile.txt` | 33,523 × 223 = **7,475,629 B** | uploaded to object storage |
| `parities.txt` | 33,523 × 32 = **1,072,736 B** | uploaded to object storage |
| Challenge length | **460** | `Benchmark.java:45` |
| Range reads per invocation | 460 × 223 B + 460 × 32 B = 920 GETs ≈ **115 KiB** | handler's `Prove` step |
| FIV payload | `fib(800000)` → 167,190 decimal digits | `test-config.xml` |

A single audit invocation is therefore 920 *serial* small range reads: at one
thread it takes double-digit seconds to move ~115 KiB, which is HTTP latency 920
times over, not bandwidth.

`blockNum=4` in the Properties files is the **upload part count**: `sourceFile.txt`
is split into 4 parallel parts and `parities.txt` is uploaded as a single part
(`Benchmark.java:1097,1128-1129`). `partNum` is **read by no Java code** — it is
a leftover and has no effect. Neither key is related to the shard count, which
follows from the file size alone.

The cost figures are defined in [Cost Model](#cost-model): `GB·s = (Memory_MB / 1024) × Execution_Time_s`,
summed over the `Memory_MB` / `Execution_Time_ms` columns present in every CSV.

## Project Structure

```
src/main/java/com/fchen_group/TPDSInScf/
├── Core/                          # auditing core algorithms
│   ├── IntegrityAuditing.java     # 5-step protocol: KeyGen→OutSource→Audit→Prove→Verify
│   ├── ChallengeData.java         # challenge data structure
│   ├── ProofData.java             # proof data structure
│   └── PseudoRandom.java          # pseudorandom number
│
├── Utils/                         # cloud platform abstraction layer
│   ├── CloudAPI.java              # Tencent Cloud COS API
│   ├── AliCloudAPI.java           # Alibaba Cloud OSS API
│   ├── TenYunControl.java         # Tencent Cloud SCF control (deployment/invocation/configuration)
│   ├── AliYunControl.java         # Alibaba Cloud FC control
│   ├── AwsControl.java            # AWS Lambda control
│   ├── AzureControl.java          # Azure Functions control
│   ├── TenRequestClass.java       # request/response data model
│   ├── ResponseClass.java         # response encapsulation
│   ├── XmlConfigParser.java       # XML test configuration parser [new]
│   ├── FibIter.java               # naive O(n) fibonacci, the -DfibAlgo=iter payload [new]
│   └── ReedSolomon/               # RS erasure coding GF(2⁸)
│
├── Run/                           # entry points and cloud function handlers
│   ├── Benchmark.java             # ★ automated test main framework [new]
│   ├── TenHandle.java             # Tencent Cloud SCF handler
│   ├── AliHandle.java             # Alibaba Cloud FC handler
│   ├── AwsHandle.java             # AWS Lambda handler
│   └── AzureHandle.java           # Azure Functions handler
│
├── Properties                     # active config, copied from Properties-{platform} at runtime
├── Properties-tencent             # Tencent Cloud configuration
├── Properties-ali                 # Alibaba Cloud configuration
├── Properties-aws                 # AWS configuration
├── Properties-azure               # Azure configuration
└── test-config.xml                # XML test parameter configuration [new]
```

The four committed `Properties-*` files are **placeholders** (`secretId=<your-secret-id>`,
the Azure connection string likewise) — fill in your own credentials and bucket
names before running anything. `Properties` is not in the repository; it is
generated at runtime by copying the matching `Properties-<platform>`
(`Benchmark.java:635-641`).

## Case Study: Cloud Storage Auditing System

Cloud storage greatly simplifies application system development as modern infrastructure, but it also introduces data security challenges. Cloud storage auditing is a mechanism that allows users to verify the integrity and availability of their data stored in cloud environments. In a typical cloud storage auditing system, users can periodically or on-demand request proof from the cloud service provider to confirm that their data remain intact and unaltered. In this case study, SCF acts as the interface for object storage and involves three key components: user, storage service, and compute service. The auditing workflow is as follows:

<div align="center">
    <img src="mdPics/System2.png" alt="System2" style="zoom:50%;" />
</div>


## Auditing Protocol (5 Steps)

```
KeyGen → OutSource → [upload to cloud] → Audit(SCF invocation) → Prove → Verify
```

| Step | Execution Location | Description |
|------|--------------------|-------------|
| KeyGen | local | generate encryption key and authentication key |
| OutSource | local | encode file into k=223 data blocks + m=32 parity blocks, generate authentication tags |
| Upload | local | upload sourceFile.txt and parities.txt to cloud storage |
| Audit | local | generate random challenge of length l=460 |
| Prove | inside SCF | download challenge blocks from cloud, compute data and parity proofs |
| Verify | local | verify proof correctness, determine data integrity |

Parameters: n=255, k=223, GF(2⁸), challenge length l=460, test file ~7MB.

## Automated Testing (Benchmark)

### Command Line

```bash
java -cp <JAR> com.fchen_group.TPDSInScf.Run.Benchmark [test type] [cloud platforms...]
```

### Test Types

| Value | Description | Output CSV |
|-----|-------------|------------|
| `audit` | native cloud storage + integrity audit | `云审计各自.csv` |
| `fib` | Fibonacci CPU benchmark | `FIV.csv` (or `FIV_iter.csv` with `-DfibAlgo=iter`) |
| `s3` | unified S3 storage + audit (cross-cloud comparison) | `s3.csv` |
| `azure` | Azure-specific format | `azure_benchmark_data.csv` |
| `coldstart` | cold-start measurement (memory switching and instance recycling) | `cold_start.csv`, `azure_cold_start.csv` |
| `coldstartidle` | cold-start measurement (idle reclaim; the trigger the platform cannot anticipate) | `cold_start_idle.csv` |
| `all` | run all 5 types of tests | all CSVs |
| `deploy` | deploy function code only, do not run tests | none |

### Flags

| Flag | Effect |
|------|--------|
| `-DtestMode=true` | skip the Maven build **and** deployment; hard-override every test to 512 MB, 1 thread, 3 repeats (coldstart: 512/1024 MB, 3 repeats, 2-minute wait). Smoke test only — never use it to collect data |
| `-DskipBuild=true` | skip the `mvn package` that `main()` runs otherwise. **Always pass this when launching from the fat JAR** (see [Deploy](#5-deploy)) |
| `-DskipDeploy=true` | use the code already deployed in the cloud instead of re-uploading it before the run |
| `-DslimDeploy=true` | build a code-only zip that excludes the other platforms' SDKs, for a faster upload |
| `-Dmaven.executable=<path>` | Maven binary used by the internal build (`Benchmark.java:58`). Note `deployAzure` reads the separate `mavenExecutable` key from the Properties file and does **not** see this flag |
| `-DbenchmarkJarName=<name>` | redirect the assembly output name, so the build does not overwrite the JAR the JVM has open |
| `-DfibAlgo=iter` | switch the FIV payload from fast doubling to the naive O(n) iteration, and route the output to `FIV_iter.csv` (`Benchmark.java:50-51,368`). Absent or `fast` leaves the standard `FIV.csv` run unchanged |
| `-DazurePhaseSeconds=<n>` | seconds to spread one Azure thread phase over, so Azure Monitor's PT1M metrics resolve every thread level (default 300; `0` = back-to-back; `Benchmark.java:525`) |
| `-DidleTiers=128,512,...` | memory tiers for `coldstartidle` (default: all of `DEFAULT_MEMORY_SIZES`) |
| `-DidleMinutes=<n>` | idle wait per tier for `coldstartidle`, during which the platform reclaims the instance (default 20; `testMode` shortens it to 2) |

`main()` reads `testMode`, `skipBuild`, `skipDeploy`, `slimDeploy`, `idleMinutes` and `idleTiers` (`Benchmark.java:73-88`). Without `-DskipBuild=true` the first thing it does is run `mvn package -Pdevelopment-Azure` (`Benchmark.java:107-109`).

### Cloud Platform Parameters

`Tencent`, `Ali`, `AWS`, `Azure` (unspecified = all related platforms)

### Azure is measured differently

Azure Functions is the odd one out and its numbers are **not** collected the same
way as the other three:

- **No memory-switch API.** The other platforms let the client change the
  allocation between blocks; Azure Consumption does not expose that, so the
  `azure` test sweeps **thread counts** (1, 2, 4, 8, 16, 32) at whatever
  allocation the plan gives, instead of sweeping memory. That is why the paper's
  Azure results do not sit in the same table as the other platforms, and why
  `azure_benchmark_data.csv` has a different schema.
- **Windows host.** `audit-test-azure` runs on a Windows Consumption plan
  (`SKU Y1` / `Dynamic`), so there is no cgroup: memory has to be read from the
  Windows **Job Object** the App Service sandbox uses, and from the platform's
  own per-app counter. The JVM cannot see either through an in-process API —
  `K32GetProcessMemoryInfo` and `psapi!GetProcessMemoryInfo` both fail inside
  the sandbox, so `proc_mem_api` stays `-`.
- **`japanwest`**, see above. The paper's "unified Tokyo" describes the other
  three platforms; Azure is deployed to Osaka here.

#### `azure_benchmark_data.csv`

`threads,test_id,exec_time,mem_usage` — `exec_time` is in **seconds** (unlike
the other CSVs, which are milliseconds), and `mem_usage` is the instance's peak
**private commit** over the invocation, as reported by the platform's own
counter `WEBSITE_COUNTERS_APP.privateBytes` (`Benchmark.java:581-588`).

That counter is the billing caliber: the Consumption plan charges on
`PrivateMemorySize64` (private memory of the process *and its children*),
rounded up to the next 128 MB and capped at 1536 MB. It is deliberately **not**
`peakCommittedMB` (that is only this JVM's own commit, ~130 MB, and stays flat)
and **not** `peakPhysUsedMB` (inside the sandbox the process sees the shared
host, total 3070 MB, so it includes other tenants).

Reading the counter needs JNA through `kernel32!GetEnvironmentVariableW`: the
sandbox serves these values as write-only "environment variables" that do not
appear in an enumeration and are recomputed on every lookup, so
`System.getenv` — which reads the JVM's start-up snapshot — never sees them.

#### `azure_phase_diag.csv`

A wider per-invocation diagnostic file written alongside it, in 27 columns,
grouped by what each one measures:

| Group | Columns |
|-------|---------|
| Invocation | `thread,test_id,invoke_start_utc,invoke_start_epoch_ms,exec_time_s` |
| Host / sandbox | `total_phys_MB,peak_phys_used_MB` |
| JVM | `peak_heap_used_MB,peak_nonheap_used_MB,peak_direct_used_MB,peak_committed_MB,peak_threads,samples` |
| Sandbox limit (Job Object on Windows, cgroup on Linux) | `mem_source,mem_source_detail,limit_MB,used_start_MB,used_peak_MB,used_last_MB` |
| Process memory (unavailable in the sandbox) | `proc_peak_working_set_MB,proc_peak_private_MB,proc_mem_api` |
| Billing counter (`WEBSITE_COUNTERS_APP.privateBytes`) | `priv_source,priv_start_MB,priv_peak_MB,priv_last_MB,env_mem_limit_MB` |

`mem_source` names which mechanism answered (`job` / `cgroup-v2` / `cgroup-v1` /
`-`), so a row is self-describing. `limit_MB` is `-1` when unknown and `-2` when
unlimited. On this plan the Job Object reports `limit_MB` = 1536 and
`priv_peak_MB` tracks `used_peak_MB` closely.

The `invoke_start_utc` column exists to **join the run to Azure Monitor's
platform metrics**: export `AverageMemoryWorkingSet` (`Microsoft.Web/sites`,
unit bytes, PT1M) for the same window and match it minute by minute. The join
needs roughly one invocation per minute per thread level, which is what
`-DazurePhaseSeconds=300` (the default) is for.

### Examples

```bash
# test mode (quick verification: 512MB/single-thread/3 repeats)
java -DtestMode=true -cp ...jar Benchmark audit Tencent

# deploy code to all platforms
java -cp ...jar Benchmark deploy

# run Tencent Cloud audit test (data collection)
java -DskipBuild=true -DskipDeploy=true -cp ...jar Benchmark audit Tencent

# run Fibonacci test
java -DskipBuild=true -DskipDeploy=true -cp ...jar Benchmark fib

# same, but with the naive O(n) iteration payload (writes FIV_iter.csv)
java -DskipBuild=true -DskipDeploy=true -DfibAlgo=iter -cp ...jar Benchmark fib

# run S3 unified storage test
java -DskipBuild=true -DskipDeploy=true -cp ...jar Benchmark s3

# run cold-start measurement
java -DskipBuild=true -DskipDeploy=true -cp ...jar Benchmark coldstart Tencent

# run cold-start measurement via idle reclaim (Tencent / Ali only; see below)
java -DskipBuild=true -DskipDeploy=true -DidleTiers=256,512 -DidleMinutes=20 -cp ...jar Benchmark coldstartidle Tencent

# run all tests
java -DskipBuild=true -cp ...jar Benchmark all
```

### Test Mode (`-DtestMode=true`)

- skips the Maven build **and** the deployment step
- forces the parameters down to 512 MB, single thread, 3 repeats (coldstart: 512/1024 MB, 3 repeats, a 2-minute recycle wait instead of 30; coldstartidle: 512/1024 MB and a 2-minute idle wait instead of 20 — below any reclaim threshold, so a testMode idle run yields no cold rows on purpose)
- meant for debugging and for verifying the workflow end to end; do not collect data with it

### Execution Flow

```
audit mode:
  deploy code → KeyGen+OutSource+upload (one-time) → generate challenge
  → for memory in <the configured tier list; set per platform in test-config.xml>:
       → update memory → warm-up (absorb cold start)
       → for thread in [1,2,4,8,16]:
            → for repeat in 1..30:
                 → invoke SCF → verify → write CSV

fib mode (-DfibAlgo=fast, the default):
  deploy code → for memory → warm-up → for 1..30: invoke (fib(800000), fast doubling) → write FIV.csv
fib mode (-DfibAlgo=iter):
  identical flow, but each invocation runs the naive O(n) iteration → write FIV_iter.csv

s3 mode:
  upload data to AWS S3 → for platform → for memory → warm-up
  → for 1..30: invoke (storageType="s3") → write CSV (including Total_Time_ms)

coldstart mode (Tencent / Ali / AWS):
  pin the function to one instance (see Max-Instance below), remembering the old
  setting so it can be put back in a finally block
  for each adjacent pair of memory tiers (128/256, 256/512, ...):
       phase 1: switch to B | phase 2: switch to A
       each phase:
            → update memory, wait until the platform reports the new tier (poll
              up to 60 s — a flat 5 s sleep is not enough)
            → run 0: the first call after the switch, recorded as the baseline
            → for r in 1..3:
                 → start client wall clock → invoke → stop clock
                 → execution time = download_time + proofTime from the response
                 → init probe = the handler's JVM uptime at entry (in-instance)
                 → cold when the instance ID is one this run has never seen
                 → write CSV
  restore the previous max-instance setting

coldstart mode (Azure):
  fixed memory (no switch API) → invoke → sleep 30 min for the instance to be
  reclaimed → invoke again → compare instanceId → write CSV

coldstartidle mode (Tencent / Ali):
  pin the function to one instance, as above
  for each tier (all, or -DidleTiers):
       → switch to the tier, then run 0: one call to warm an instance at it
       → stop calling for -DidleMinutes (default 20) so the platform reclaims it
       → run 1: the cold sample — the platform had no demand signal during the
         wait, so it had no reason to pre-build a replacement
       → for r in 2..4: same-container warm rows, the estimator's baseline
  restore the previous max-instance setting
```

Changing the memory tier is what forces a new container on Tencent / Ali / AWS, so
the *first* invocation after a switch tends to be cold — but not always: the platform
may keep serving from the pre-switch container, so the cold call can land on run 1 or
later. `Run_ID 0` is recorded for exactly that reason, and the cold/warm label is
derived from instance identity, never from position in the block.

### Where the cold-start number comes from

`Total_Time_ms` alone cannot answer this. It is the client's wall clock around the
invoke, so it stacks client SDK setup and network RTT on top of init and execution —
and on a long-haul link that first term moves by tens of seconds between calls
(Ali 256 MB: 21.8 s cold vs 44.4 s warm *on the same container*), which is how the
naive `Total_cold − Total_warm` produced negative cold starts.

Two ways to get init out of that, both recorded per row:

- **`Init_Probe_ms`** — the handler reads `ManagementFactory` uptime as the invocation
  enters. In-instance, so client and network time are structurally absent. It is the
  container's *age* at handler entry, and equals the cold start only when the container
  was built for that call — not on every row, because a platform can build the new
  tier's container in the background after a memory switch. On AWS, which does replace
  the container at the switch, it is a lower bound on the platform's `Init Duration`
  (JVM start happens after the platform's own bootstrap). On Tencent the first call to
  reach the new tier can report an age reaching back *before* the request: the
  2026-09-24 run had a row at `Init_Probe_ms` 29325 against `Total_Time_ms` 20736,
  which a container created by that call cannot do. So use it as a **validity flag**,
  not a value: `probe > Total − Execution` means the row is a **false cold** and has to
  be dropped, and the probe is not a Tencent cold-start figure at all. It also catches
  a container warmed by the pre-measurement audit call, which is new to the seen-set
  but reports minutes rather than seconds. Needs a rebuild + redeploy; older packages
  yield `NA`.
- **`(Total − Execution)`** on the cold row minus the median of the same on that same
  container's warm rows. Subtracting the in-handler time leaves only
  client + init + response, and taking the baseline from the *same* container holds
  the client/network term constant. This needs no redeploy and works on data already
  collected.

Both cross-check each other, and the two independent containers each tier gets from
the adjacent-pair sweep cross-check the tier. What to reject: a non-positive value, a
tier that breaks monotonicity in memory (init is CPU-bound, so more memory must not
be slower), or a tier whose two estimates disagree by more than a few percent — that
last one is Ali's signature, and it means the client was too far from the region.

### Why a second cold-start trigger exists (`coldstartidle`)

The trigger above is a **memory switch** — and a memory switch is something the platform
can see. Tencent uses that warning to build the replacement container in the background
while the block's baseline call is still running, so the call we label cold lands on a
container that already exists and the number is only the part of the rebuild the platform
left for the caller. On 2026-09-24 that residual ran 1.5–2.7× below the idle-reclaim
trigger on the same tiers (256 MB: 3433 ms vs 5165 ms; 512 MB: 1877 ms vs 4226 ms).

`coldstartidle` makes the trigger the platform's own **idle reclaim**: set the tier once,
warm one instance, then stop calling. Nothing during the wait is a demand signal, so
there is no reason to pre-build, and the first call afterwards is the one that has to
build the container. That call is the sample (`Run_ID 1`); runs 2..4 of the same container
are the warm baseline. Columns, gates and estimator are unchanged — same 7-column format
as `cold_start.csv`, different file.

Which platform uses which:

| platform | method | why |
|---|---|---|
| Tencent | `coldstartidle` | pre-builds the replacement, so the switch method measures a residual |
| AWS | `coldstart` | replaces the container at the switch; probe and client wall clock agree (256 MB: 100667 vs 102706), so the switch method is already correct |
| Ali | `coldstartidle` | same pre-build exposure as Tencent; needs the console instance cap first |
| Azure | `coldstart Azure` | no memory API; its own path already waits for reclaim |

`runIdleColdStartBenchmark` removes AWS and Azure from the platform set and says why in a
comment, so passing them on the command line is a no-op rather than a silent wrong run.

Cost is dominated by the wait, not the calls: one cold sample per block, and one
`idleMinutes` wait per block, so a full five-tier ladder is ~100 minutes per platform. If
a tier's instance was not reclaimed in time, the sample call comes back warm by the
seen-set rule — the correct label — and the analysis drops the block rather than averaging
it in. Two caveats worth knowing before reading the numbers:

- **The in-instance probe cannot see the platform's bootstrap.** Image pull and JVM
  launch happen before `ManagementFactory` starts counting, so `Init_Probe_ms` is a gate
  here, never a value. What the mode produces is the *caller-visible* cold latency
  (container build + package pull + JVM start + network), which is comparable across
  providers but is not the platform's own `Init Duration`.
- **Nothing in the CSV records the memory tier the instance actually ran at.** A memory
  change may not have propagated when the warm-up call goes out (observed: Tencent's
  512 MB warm-up landed back on the 256 MB container), so tier validity can only be
  inferred from execution time afterwards.

### Max-Instance (paper §4.5)

Without a one-instance cap a platform may keep two containers alive and route calls
to either one, which produces blocks like `A B A` where nothing has a warm baseline.
The cap is applied automatically before the sweep and reverted afterwards:

| platform | mechanism | note |
|---|---|---|
| Tencent | `PutReservedConcurrencyConfig` | the quota is **memory-sized**, so max instances = quota ÷ function memory; the code moves the quota with every memory switch to keep the count at 1. A switch **down** is rejected with `concurrency exceeded reserved quota` until the previous tier's instance is reclaimed, so calls are retried (10 s apart, up to 6 min) until one gets through |
| AWS | `PutFunctionConcurrency` / `DeleteFunctionConcurrency` | reserved concurrency is a plain instance count, so it stays at 1 |
| Ali | none | the `fc20230330` SDK in `pom.xml` has no scaling-config API; set **弹性实例配额 = 1** by hand (函数计算 → 函数 → 弹性配置 → 函数配额) or the run violates §4.5. The code prints a reminder and still verifies the memory tier through `getFunction` |

Two independent signals are recorded per row and they are **not** interchangeable:

- `Is_Cold` — did the call land on an instance ID this platform has not returned
  before in this run? (A seen-set, not a comparison with the previous call: with two
  alternating containers, "different from last time" mislabels the second sighting of
  a container as cold.) Failed calls write `NA`, never `false`, so a group-by on this
  column cannot silently absorb them.
- `Total_Time_ms` — client-side wall clock. This is the one that captures container
  boot, because `Execution_Time_ms` is measured *inside* the handler body and starts
  after the runtime is already up. Subtract the warm baseline from it to get the real
  cold-start cost.

Note that the timed window includes the client-side setup inside each platform's
`invoke()` (it re-reads the Properties file and builds a fresh SDK client per call),
which adds a roughly constant offset to both cold and warm rows; it largely cancels
in the subtraction but it is not zero-variance.

### FIV: two Fibonacci algorithms

`fib` can run two different implementations of the same function, selected by
`-DfibAlgo`:

| `-DfibAlgo` | Implementation | Output |
|-------------|----------------|--------|
| `fast` (default) | `fib()` — fast doubling, O(log n) big-integer multiplies | `FIV.csv` |
| `iter` | `FibIter.fib()` — naive loop, O(n) big-integer additions | `FIV_iter.csv` |

Both compute **F(800000) exactly** and return a byte-identical 167,190-digit
decimal string, so the result cannot tell them apart — only the timing can. The
payload carries `fibAlgo` and each handler picks the implementation from it
(`TenRequestClass.java`), so no redeploy is needed to switch — except that the
deployed package must contain `FibIter`; packages built before it do not.

The two runs are written to **separate files on purpose**. `FIV_iter.csv` is an
order of magnitude slower per call and would silently change what `FIV.csv`
means if it were appended there.

The variant exists because the paper never states which algorithm it used. Its
only description is "calculate the first 800,000 terms of the Fibonacci
sequence" (§5.6, and the Figure 6 caption); there is no code, pseudocode or
algorithm name. The plain reading of that phrase suggests the O(n) iteration, and
the *magnitude* of the paper's own numbers supports it — AWS at 2048 MB (~1 vCPU)
is 11.4 s, the order of an O(n) pass, while fast doubling lands 10–100× below
that. That is an inference from magnitudes, not a statement in the paper, so the
switch exists to measure both rather than argue about it.

### Parameter Matrix

| Parameter | audit | fib | s3 | azure | coldstart |
|------|-------|-----|-----|-------|-----------|
| platforms | Tencent/Ali/AWS | Tencent/Ali/AWS | Tencent/Ali/AWS | Azure | Tencent/Ali/AWS + Azure |
| memory (MB) | per platform: Tencent 128,256,512,1408,2176,2944 · Ali 128,256,512,1024,2048 · AWS 256,512,1024,2048 | 128,256,512,1024,2048 | 256,512,1024,2048 | fixed | adjacent pairs of the default tiers |
| threads | 1,2,4,8,16 | — | — | 1,2,4,8,16,32 | — |
| algorithm | — | fast doubling, or O(n) iteration with `-DfibAlgo=iter` | — | — | — |
| repetitions | 30 | 30 | 30 | 10 | 3 |

The `audit` tiers are the ones each platform's reference data actually contains,
and they are **not** the same list: Tencent has its own ladder with 1408/2176/2944,
AWS has no 128 MB tier (it fails at 128 MB), and Ali uses the plain powers of two.
They are set in `test-config.xml` and apply to all platforms in a run, so the file
has to be edited between platforms — see [XML Configuration](#xml-configuration).

`coldstart` does **not** read `test-config.xml`: it always uses the built-in
`DEFAULT_MEMORY_SIZES` (128,256,512,1024,2048) and pairs adjacent tiers.

`coldstartidle` takes its tiers from `-DidleTiers` instead (all of `DEFAULT_MEMORY_SIZES`
when absent), and its own output root duration from `-DidleMinutes`. It is not part of
`all`: the per-block idle wait is a different order of magnitude from the other tests, so
folding it in would make `all` impossible to estimate.

**Approximately 3200 calls for the audit / fib / s3 / azure tests, plus the
`coldstart` runs; expected to run 12 hours or more.** `coldstartidle` is separate and
priced in idle waits, not calls.

### CSV Output Format

For `audit`, `fib`, `s3` and `azure` the filename is configurable: each test writes
to the name given by its `csvFile` entry in `test-config.xml`, falling back to the
default below when the entry is absent. The three `coldstart` filenames are hard-coded
in `Benchmark.java` (`cold_start.csv`, `azure_cold_start.csv`, `cold_start_idle.csv`).

**云审计各自.csv**: `CSP,Memory_MB,Thread,Run_ID,Execution_Time_ms`
- Thread format: "1 Thread", "2 Threads" ...
- Execution_Time_ms = time inside the cloud function (download + proof computation)

**FIV.csv**: `CSP,Memory_MB,Run_ID,Execution_Time_ms`
- fib(800000) computation time in milliseconds, fast doubling (O(log n))
- No `Total_Time_ms` column: FIV is a pure-CPU test, so the client-side wall
  clock would only add network time

**FIV_iter.csv**: the same four columns, from a run with `-DfibAlgo=iter`
- Same `fib(800000)`, computed by the naive O(n) iteration instead. Separate file
  because the two algorithms are not comparable on one axis

**s3.csv**: `CSP,Memory_MB,Run_ID,Execution_Time_ms,Total_Time_ms`
- Total_Time_ms = total time from client request initiation to response receipt

**azure_benchmark_data.csv**: `threads,test_id,exec_time,mem_usage`
- exec_time unit is seconds
- mem_usage = peak private commit of the instance, from the platform's own
  counter; see [Azure is measured differently](#azure-is-measured-differently)

**azure_phase_diag.csv**: 27 columns of per-invocation Azure diagnostics, written
alongside the file above. Column groups and the `AverageMemoryWorkingSet` join are
described in [Azure is measured differently](#azure-is-measured-differently)

**cold_start.csv** (Tencent / Ali / AWS): `CSP,Memory_MB,Is_Cold,Run_ID,Init_Probe_ms,Execution_Time_ms,Instance_ID,Total_Time_ms`
- Is_Cold = true if the instance ID had not been seen before in this run; `NA` on failure
- Init_Probe_ms = the handler's own `ManagementFactory` uptime as the invocation entered,
  in ms — sampled **inside the instance**, so it carries no client or network time. It is
  the container's age at handler entry, which equals the cold start only when the container
  was built for that call. Not a value on every row: a platform may build the new tier's
  container in the background after a memory switch, so the first call to land on it can
  report an age reaching back before the request (2026-09-24: Tencent 29325 ms against a
  Total of 20736 ms). Read it as a validity flag — `probe > Total − Execution` is a false
  cold. On AWS it is a lower bound on `Init Duration` (JVM start comes after that bootstrap);
  on Tencent it is not a cold-start figure at all. `NA` if the deployed package predates the
  probe (rebuild + redeploy to get it)
- Run_ID 0 = the first call after a memory switch, kept as the block's baseline
- Total_Time_ms = client-side wall clock, the only column that includes client SDK and
  network time on top of boot + execution

**cold_start_idle.csv** (Tencent / Ali): the same seven columns as `cold_start.csv`, and
the same label rule and gates. Only `Run_ID` means something different — `0` warms an
instance at the tier (it builds a container too, and gets `Is_Cold = true`, but it is
**not** a sample), `1` is the cold sample measured after the idle wait, `2..4` are
same-container warm rows. No column records the memory tier the instance actually ran at,
so a tier's validity has to be inferred from execution time.

**azure_cold_start.csv**: `CSP,Run_ID,Execution_Time_ms,Instance_ID,Allocated_Memory_MB,Total_Time_ms`
- Allocated_Memory_MB = memory the platform actually assigned (Azure Consumption Plan)

All CSVs are opened in **append** mode: an interrupted run keeps the rows already
written. The header is only written when the file is missing or ≤10 bytes
(`writeCsvHeader`), rows always append, and nothing in the code ever truncates a
file — so **renaming or deleting the CSV before a rerun is mandatory**. Otherwise
the old and new rows land in the same file under one header.

### XML Configuration

Edit `test-config.xml` to customize test parameters:

```xml
<test type="audit" enabled="true">
    <csvFile>云审计_腾讯.csv</csvFile>
    <platforms>Tencent</platforms>
    <memorySizes>128,256,512,1408,2176,2944</memorySizes>
    <threadCounts>1,2,4,8,16</threadCounts>
    <repetitions>30</repetitions>
</test>
```

Set `enabled="false"` to skip a test. `csvFile` sets the output name for `audit`,
`fib`, `s3` and `azure` (the three cold-start names are hard-coded).

`memorySizes` is the one setting that **must be changed between platforms**: there
is no per-platform override, so a run with several platforms and one list either
adds a tier a platform does not have (AWS at 128 MB fails on every call) or drops
the tiers the reference data uses (Tencent's 1408/2176/2944).


## Cost Model

Serverless platforms bill on two axes: **allocated memory** and **execution
time**. The billed quantity for one invocation is therefore

```
GB·s = (Memory_MB / 1024) × Execution_Time_s
```

This is the **cost efficiency** metric reported in the paper. The two axes are
not independent: on most platforms a larger memory tier also receives
proportionally more vCPU, so raising memory shortens execution time. For this
workload execution time falls faster than the per-second rate rises, which means
the cheapest configuration is usually *not* the smallest one.

The total cost of a benchmark run follows directly from the recorded data:

```
Total_GB·s = Σ (Memory_MB / 1024) × (Execution_Time_ms / 1000)
```

Every CSV records both `Memory_MB` and `Execution_Time_ms` for each invocation,
so this sum can be computed from the output files without re-running anything.

Two caveats when comparing against an actual bill:

- Platforms round billed duration up (typically to 1 ms or 100 ms granularity),
  so real charges run slightly higher than this estimate.
- Cold-start invocations are billed for their full duration, including
  initialization overhead — the `coldstart` test exists to measure that cost.

Unit prices differ per platform, region and memory tier, and change over time, so
no price table is hard-coded here. Multiply `Total_GB·s` by the current rate for
the region under test.

## Configuration

### Properties File Format

```properties
mavenExecutable=mvn
filePath=D:/testdata/testfile.txt
tmpFilePath=D:/tmp
memorySize=512
timeout=900
blockNum=4
partNum=4

# platform-specific configuration
secretId=<AccessKey>
secretKey=<SecretKey>
regionName=<Region>
bucketName=<Bucket>
functionName=<FunctionName>
handler=<HandlerClass>
runtime=<Runtime>
```

There is one Properties file per platform (`Properties-tencent/ali/aws/azure`), and Benchmark will automatically copy the corresponding file to `Properties` at runtime.

### Prerequisites

1. JDK 1.8+
2. Maven (path configured in Properties `mavenExecutable`)
3. test file `D:/testdata/testfile.txt` (~7MB)
4. Alibaba Cloud requires JRE package: `D:/tmp/jre11.tar.gz`
5. object storage and cloud function services enabled on each cloud platform

## Key Design Decisions

- **One-time data preparation**: KeyGen + OutSource execute only once; all invocations reuse the same cloud-side data
- **Memory hot switching**: modify memory with the SDK UpdateFunctionConfiguration API (1-2 seconds), no redeployment needed
- **Cold start absorption**: perform one warm-up invocation after each memory configuration change
- **Properties switching**: copy `Properties-{platform}` → `Properties`, existing Control classes do not need modification
- **Retry mechanism**: automatically retry when the function is in "Updating" status (up to 10 times, 10-second intervals)
- **Alibaba Cloud JRE bundling**: package JRE into zip, extract it to /tmp/jre on first cold start, and reuse it on subsequent warm starts
