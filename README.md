This repository contains the code for measurement research of mainstream SCF services using a case study approach.

The rapid development of Serverless computing function (SCF) has enabled an event-driven application development paradigm, providing users with on-demand and cost-efficient computing resources. The evolution of cloud services from Platform as a Service (PaaS) to Function as a Service (FaaS) offers a finer-grained model for resource provisioning and usage. Although SCF services offered by major CSP are functionally similar, they differ in interface design and usage patterns, and the lack of a unified management layer across CSP significantly increases the cost of comparison. To effectively understand these differences, users require a measurement framework that enables rapid evaluation of application-relevant characteristics across different CSP.

We focus on practical performance characteristics, including stability, cold-start behavior, parallel performance, and network performance, and further evaluates the effectiveness of combining SCF with another fundamental cloud service.

---

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
│   └── ReedSolomon/               # RS erasure coding GF(2⁸)
│
├── Run/                           # entry points and cloud function handlers
│   ├── Benchmark.java             # ★ automated test main framework [new]
│   ├── TenHandle.java             # Tencent Cloud SCF handler
│   ├── AliHandle.java             # Alibaba Cloud FC handler
│   ├── AwsHandle.java             # AWS Lambda handler
│   ├── AzureHandle.java           # Azure Functions handler
│   ├── Client.java                # manual test entry
│   ├── AliClient.java
│   ├── AwsClient.java
│   └── AzureClient.java
│
├── Properties                     # currently active platform configuration
├── Properties-tencent             # Tencent Cloud configuration
├── Properties-ali                 # Alibaba Cloud configuration
├── Properties-aws                 # AWS configuration
├── Properties-azure               # Azure configuration
└── test-config.xml                # XML test parameter configuration [new]
```

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

## Build

This project uses **Java 1.8** + **Maven**, and supports four cloud platforms (Tencent Cloud / Alibaba Cloud / AWS / Azure).

Configure the **Properties** file to access object storage and SCF services, and specify the location for intermediate auditing files.

```bash
# Build a JAR containing all platform SDKs using development-Azure profile
mvn package -Pdevelopment-Azure -DskipTests
```

Build artifact: `target/TPDSInSCF-1.0-SNAPSHOT_Benchmark-jar-with-dependencies.jar`

## Manual Run (Single Test)

After packaging, place the **Properties** file in the same directory as the JAR and run:

```bash
java -cp TPDSInSCF-1.0-SNAPSHOT_Benchmark-jar-with-dependencies.jar com.fchen_group.TPDSInScf.Run.Client
```

## Automated Testing (Benchmark)

### Command Line

```bash
java -cp <JAR> com.fchen_group.TPDSInScf.Run.Benchmark [test type] [cloud platforms...]
```

### Test Types

| Value | Description | Output CSV |
|-----|-------------|------------|
| `audit` | native cloud storage + integrity audit | `cloud_audit_each.csv` |
| `fib` | Fibonacci CPU benchmark | `FIV.csv` |
| `s3` | unified S3 storage + audit (cross-cloud comparison) | `s3.csv` |
| `azure` | Azure-specific format | `azure_benchmark_data.csv` |
| `all` | run all 4 types of tests | all CSVs |
| `deploy` | deploy function code only, do not run tests | none |

### Cloud Platform Parameters

`Tencent`, `Ali`, `AWS`, `Azure` (unspecified = all related platforms)

### Examples

```bash
# test mode (quick verification: 512MB/single-thread/3 repeats)
java -DtestMode=true -cp ...jar Benchmark audit Tencent

# deploy code to all platforms
java -cp ...jar Benchmark deploy

# run Tencent Cloud audit test
java -cp ...jar Benchmark audit Tencent

# run Fibonacci test
java -cp ...jar Benchmark fib

# run S3 unified storage test
java -cp ...jar Benchmark s3

# run all tests
java -cp ...jar Benchmark all
```

### Test Mode (`-DtestMode=true`)

- skip Maven build and function deployment
- only test 512MB memory, single thread, 3 repeats
- used for debugging and quickly verifying the workflow

### Execution Flow

``
audit mode:
  deploy code → KeyGen+OutSource+upload (one-time) → generate challenge
  → for memory in [128,256,512,1024,2048]:
       → update memory → warm-up (absorb cold start)
       → for thread in [1,2,4,8,16]:
            → for repeat in 1..30:
                 → invoke SCF → verify → write CSV

fib mode:
  deploy code → for memory → warm-up → for 1..30: invoke (fib(800000), Fast Doubling) → write CSV

s3 mode:
  upload data to AWS S3 → for platform → for memory → warm-up
  → for 1..30: invoke (storageType="s3") → write CSV (including Total_Time_ms)
```

### Parameter Matrix

| Parameter | audit | fib | s3 | azure |
|------|-------|-----|-----|-------|
| platforms | Tencent/Ali/AWS | Tencent/Ali/AWS | Tencent/Ali/AWS | Azure |
| memory (MB) | 128,256,512,1024,2048 | 128,256,512,1024,2048 | 256,512,1024,2048 | fixed |
| threads | 1,2,4,8,16 | — | — | 1,2,4,8,16 |
| repetitions | 30 | 30 | 30 | 10 |

**Approximately 3110 calls in total, expected to run 5-12 hours.**

### CSV Output Format

**cloud_audit_each.csv**: `CSP,Memory_MB,Thread,Run_ID,Execution_Time_ms`
- Thread format: "1 Thread", "2 Threads" ...

**FIV.csv**: `CSP,Memory_MB,Run_ID,Execution_Time_ms`
- fib(800000) computation time in milliseconds, Fast Doubling O(log n) algorithm

**s3.csv**: `CSP,Memory_MB,Run_ID,Execution_Time_ms,Total_Time_ms`
- Total_Time_ms = total time from client request initiation to response receipt

**azure_benchmark_data.csv**: `threads,test_id,exec_time,mem_usage`
- exec_time unit is seconds

### XML Configuration

Edit `test-config.xml` to customize test parameters:

```xml
<test type="audit" enabled="true">
    <platforms>Tencent,Ali,AWS</platforms>
    <memorySizes>128,256,512,1024,2048</memorySizes>
    <threadCounts>1,2,4,8,16</threadCounts>
    <repetitions>30</repetitions>
</test>
```

Set `enabled="false"` to skip a test.

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
