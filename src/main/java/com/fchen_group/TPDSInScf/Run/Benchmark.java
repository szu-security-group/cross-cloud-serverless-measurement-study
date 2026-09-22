package com.fchen_group.TPDSInScf.Run;

import com.alibaba.fastjson.JSON;
import com.fchen_group.TPDSInScf.Core.ChallengeData;
import com.fchen_group.TPDSInScf.Core.IntegrityAuditing;
import com.fchen_group.TPDSInScf.Core.ProofData;
import com.fchen_group.TPDSInScf.Utils.*;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/**
 * Cross-platform automated benchmarking framework for serverless audit performance.
 *
 * Supports 4 test types:
 *   audit — native cloud storage + integrity audit → 云审计各自.csv
 *   fib   — pure Fibonacci CPU benchmark → FIV.csv
 *   s3    — unified AWS S3 storage + audit → s3.csv
 *   azure — Azure-specific benchmark → azure_benchmark_data.csv
 *
 * Usage:
 *   java ... Benchmark [testType] [platforms...]
 *   java ... Benchmark audit Tencent Ali
 *   java ... Benchmark fib all
 *   java ... Benchmark all          (run everything)
 *
 * Test mode (reduced iterations, skip deploy):
 *   java -DtestMode=true ... Benchmark audit Tencent
 *
 * Slim deploy (upload a per-platform package instead of the 82 MB shared fat jar):
 *   java -DslimDeploy=true ... Benchmark deploy Tencent
 */
public class Benchmark {

    enum Platform { TENCENT, ALI, AWS, AZURE }

    static final int[] DEFAULT_MEMORY_SIZES = {128, 256, 512, 1024, 2048};
    static final int[] DEFAULT_THREAD_COUNTS = {1, 2, 4, 8, 16};
    static final int DEFAULT_REPETITIONS = 30;
    static final int DATA_SHARDS = 223;
    static final int BLOCK_SHARDS = 255;
    static final int CHALLENGE_LEN = 460;

    static final String PROJECT_DIR = System.getProperty("user.dir");
    static final String CONFIG_DIR = PROJECT_DIR + "\\Properties";
    static final String JAR_PATH = PROJECT_DIR + "\\target\\TPDSInSCF-1.0-SNAPSHOT_Benchmark-jar-with-dependencies.jar";
    static final String JRE_PATH = "D:\\tmp\\jre11.tar.gz";
    static final String XML_CONFIG_PATH = PROJECT_DIR + "\\test-config.xml";
    static final String MAVEN_EXE = System.getProperty("maven.executable", "mvn");
    static final String SLIM_DIR = PROJECT_DIR + "\\target\\slim";

    static Properties props;
    static Properties awsProps;  // AWS credentials for S3 unified storage test
    static boolean testMode = false;
    static boolean skipBuild = false;
    static boolean skipDeploy = false;
    static boolean slimDeploy = false;
    static final Map<Platform, File> slimJarCache = new EnumMap<>(Platform.class);

    // ==================== Main Entry ====================

    public static void main(String[] args) throws Exception {
        testMode = "true".equals(System.getProperty("testMode"));
        skipBuild = "true".equals(System.getProperty("skipBuild"));
        skipDeploy = "true".equals(System.getProperty("skipDeploy"));
        slimDeploy = "true".equals(System.getProperty("slimDeploy"));

        // Load XML config (optional)
        Map<String, XmlConfigParser.TestConfig> xmlConfig = loadXmlConfig();

        // Parse CLI: Benchmark [testType] [platforms...]
        String testTypeStr = "audit";
        Set<Platform> cliPlatforms = null;

        if (args.length > 0) {
            String first = args[0].toLowerCase();
            if (first.equals("audit") || first.equals("fib") || first.equals("s3") || first.equals("azure") || first.equals("all") || first.equals("deploy") || first.equals("coldstart")) {
                testTypeStr = first;
                if (args.length > 1) cliPlatforms = parsePlatforms(Arrays.copyOfRange(args, 1, args.length));
            } else {
                cliPlatforms = parsePlatforms(args);
            }
        }

        // Pre-build JAR (once) unless skipped
        if (!testMode && !skipBuild) {
            prebuildJar();
        }

        // Run tests
        if (testTypeStr.equals("all")) {
            runTest("audit", xmlConfig, cliPlatforms);
            runTest("fib", xmlConfig, cliPlatforms);
            runTest("s3", xmlConfig, cliPlatforms);
            runTest("azure", xmlConfig, cliPlatforms);
            runTest("coldstart", xmlConfig, cliPlatforms);
        } else {
            runTest(testTypeStr, xmlConfig, cliPlatforms);
        }

        System.out.println("Benchmark complete.");
    }

    static Map<String, XmlConfigParser.TestConfig> loadXmlConfig() {
        try {
            return XmlConfigParser.parse(XML_CONFIG_PATH);
        } catch (Exception e) {
            System.out.println("XML config not loaded, using defaults: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    static void runTest(String type, Map<String, XmlConfigParser.TestConfig> xmlConfig, Set<Platform> cliPlatforms) throws Exception {
        XmlConfigParser.TestConfig cfg = xmlConfig.get(type);
        if (cfg != null && !cfg.enabled) {
            System.out.println("Test '" + type + "' disabled in XML config, skipping.");
            return;
        }

        switch (type) {
            case "audit": runAuditBenchmark(cfg, cliPlatforms); break;
            case "fib":   runFibBenchmark(cfg, cliPlatforms);   break;
            case "s3":    runS3Benchmark(cfg, cliPlatforms);    break;
            case "azure": runAzureBenchmark(cfg);               break;
            case "deploy":    deployOnly(cliPlatforms);            break;
            case "coldstart": runColdStartBenchmark(cfg, cliPlatforms); break;
        }
    }

    // ==================== CLI Parsing ====================

    static Set<Platform> parsePlatforms(String[] args) {
        Set<Platform> set = EnumSet.noneOf(Platform.class);
        for (String a : args) {
            for (Platform p : Platform.values()) {
                if (p.name().equalsIgnoreCase(a)) set.add(p);
            }
        }
        return set.isEmpty() ? null : set;
    }

    static Set<Platform> resolvePlatforms(XmlConfigParser.TestConfig cfg, Set<Platform> cliPlatforms) {
        if (cliPlatforms != null && !cliPlatforms.isEmpty()) return cliPlatforms;
        if (cfg != null && !cfg.platforms.isEmpty()) {
            Set<Platform> set = EnumSet.noneOf(Platform.class);
            for (String name : cfg.platforms) {
                for (Platform p : Platform.values()) {
                    if (p.name().equalsIgnoreCase(name)) set.add(p);
                }
            }
            if (!set.isEmpty()) return set;
        }
        return EnumSet.allOf(Platform.class);
    }

    static int[] resolveMemories(XmlConfigParser.TestConfig cfg, int[] defaults) {
        if (testMode) return new int[]{512};
        if (cfg != null && cfg.memorySizes != null) return cfg.memorySizes;
        return defaults;
    }

    static int[] resolveThreads(XmlConfigParser.TestConfig cfg, int[] defaults) {
        if (testMode) return new int[]{1};
        if (cfg != null && cfg.threadCounts != null) return cfg.threadCounts;
        return defaults;
    }

    static int resolveReps(XmlConfigParser.TestConfig cfg, int defaultReps) {
        if (testMode) return 3;
        if (cfg != null) return cfg.repetitions;
        return defaultReps;
    }

    /** Deploy function code only (no benchmark), then exit. */
    static void deployOnly(Set<Platform> cliPlatforms) throws Exception {
        Set<Platform> platforms = cliPlatforms != null && !cliPlatforms.isEmpty()
            ? cliPlatforms : EnumSet.allOf(Platform.class);
        for (Platform p : platforms) {
            System.out.println("\n--- Deploying " + p + " ---");
            swapProperties(p);
            props = loadProperties();
            deployCode(p);
            System.out.println(p + " deploy complete.");
        }
    }

    // ==================== Maven Build ====================

    static void prebuildJar() throws Exception {
        System.out.println("=== Pre-building JAR (development-Azure) ===");
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(PROJECT_DIR));
        pb.command(MAVEN_EXE, "package", "-Pdevelopment-Azure", "-DskipTests");
        pb.inheritIO();
        Process proc = pb.start();
        int exit = proc.waitFor();
        if (exit != 0) throw new RuntimeException("Maven build failed with exit " + exit);
        System.out.println("JAR built: " + JAR_PATH);
    }

    /**
     * Maven profile that produces one platform's slim deployment package.
     * development-Ten must be excluded explicitly: it is activeByDefault, so naming a
     * production-* profile alone activates both and the result is a fat jar again.
     * AZURE returns null — it builds and uploads its own package via the Azure Maven plugin.
     */
    static String slimProfile(Platform p) {
        switch (p) {
            case TENCENT: return "production-Ten,!development-Ten";
            case ALI:     return "production-Ali,!development-Ten";
            case AWS:     return "production-AWS,!development-Ten";
            default:      return null;
        }
    }

    /**
     * Build one platform's slim package into target/slim/&lt;Platform&gt;.jar.
     * -DbenchmarkJarName redirects the assembly output to a separate file so the client jar
     * at JAR_PATH — which this JVM keeps loading classes from — is never overwritten.
     */
    static File buildSlimPackage(Platform p) throws Exception {
        String profile = slimProfile(p);
        if (profile == null) return new File(JAR_PATH);

        String jarName = "slim" + p;
        System.out.println("=== Building slim package for " + p + " (" + profile + ") ===");
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(PROJECT_DIR));
        pb.command(MAVEN_EXE, "package", "-P" + profile, "-DskipTests",
                   "-DbenchmarkJarName=" + jarName);
        pb.inheritIO();
        if (pb.start().waitFor() != 0) {
            throw new RuntimeException("Slim build failed for " + p);
        }

        File built = new File(PROJECT_DIR + "\\target\\" + jarName + "-jar-with-dependencies.jar");
        File out = new File(SLIM_DIR + "\\" + p + ".jar");
        out.getParentFile().mkdirs();
        Files.copy(built.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Slim package: " + out + " (" + out.length() / 1048576 + " MB)");
        return out;
    }

    /**
     * The jar to upload for a platform: its slim package when -DslimDeploy=true, otherwise
     * the shared fat client jar. Cached so a platform is built at most once per JVM run.
     */
    static File jarFor(Platform p) throws Exception {
        if (!slimDeploy) return new File(JAR_PATH);
        File cached = slimJarCache.get(p);
        if (cached == null) {
            cached = buildSlimPackage(p);
            slimJarCache.put(p, cached);
        }
        return cached;
    }

    // ================================================================
    //  AUDIT benchmark — 云审计各自.csv
    //  CSP,Memory_MB,Thread,Run_ID,Execution_Time_ms
    // ================================================================

    static void runAuditBenchmark(XmlConfigParser.TestConfig cfg, Set<Platform> cliPlatforms) throws Exception {
        Set<Platform> platforms = resolvePlatforms(cfg, cliPlatforms);
        platforms.remove(Platform.AZURE); // Azure uses separate format

        String csvPath = PROJECT_DIR + "\\" + (cfg != null && cfg.csvFile != null ? cfg.csvFile : "云审计各自.csv");
        int[] memSizes = resolveMemories(cfg, DEFAULT_MEMORY_SIZES);
        int[] threadCounts = resolveThreads(cfg, DEFAULT_THREAD_COUNTS);
        int reps = resolveReps(cfg, DEFAULT_REPETITIONS);

        System.out.println("\n========== AUDIT Benchmark ==========");
        writeCsvHeader(csvPath, "CSP,Memory_MB,Thread,Run_ID,Execution_Time_ms");

        for (Platform p : platforms) {
            try {
                benchmarkAuditPlatform(p, csvPath, memSizes, threadCounts, reps);
            } catch (Exception e) {
                System.err.println("PLATFORM " + p + " FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    static void benchmarkAuditPlatform(Platform p, String csvPath, int[] memSizes, int[] threadCounts, int reps) throws Exception {
        System.out.println("\n--- " + p + " (audit) ---");
        swapProperties(p);
        props = loadProperties();

        if (!testMode && !skipDeploy) deployCode(p);
        if (p == Platform.AWS) { System.out.println("  Waiting 60s for AWS deployment to settle..."); Thread.sleep(60000); }

        IntegrityAuditing ia = prepareData(p);
        ChallengeData cd = ia.audit(CHALLENGE_LEN);

        for (int memMB : memSizes) {
            System.out.println("  Memory=" + memMB + "MB");
            if (p != Platform.AZURE) {
                try { updateMemory(p, memMB); }
                catch (Exception e) { System.err.println("  Memory update failed: " + e.getMessage()); continue; }
            }

            // Warm-up
            try {
                String warmPayload = buildAuditPayload(cd, 1);
                invokeFunction(p, warmPayload);
                Thread.sleep(3000);
            } catch (Exception e) { System.err.println("  Warm-up failed: " + e.getMessage()); }

            for (int thread : threadCounts) {
                System.out.println("    Thread=" + thread);
                String payload = buildAuditPayload(cd, thread);

                for (int run = 1; run <= reps; run++) {
                    long execTimeMs = -1;
                    try {
                        String resultJson = invokeFunction(p, payload);
                        ResponseClass resp = JSON.parseObject(resultJson, ResponseClass.class);
                        execTimeMs = (resp.download_time + resp.proofTime) / 1_000_000;

                        if (!ia.verify(cd, resp.proofData)) {
                            System.err.println("    VERIFY FAILED: " + p + " " + memMB + "MB t=" + thread + " run=" + run);
                        }
                        System.out.println("      run " + run + ": " + execTimeMs + "ms");
                    } catch (Exception e) {
                        System.err.println("    Invoke failed: run=" + run + " - " + e.getMessage());
                    }

                    appendCsvRow(csvPath, auditCsvRow(p, memMB, thread, run, execTimeMs));
                    if (run < reps) Thread.sleep(500);
                }
            }
        }
    }

    // ================================================================
    //  FIB benchmark — FIV.csv
    //  CSP,Memory_MB,Run_ID,Execution_Time_ms
    // ================================================================

    static void runFibBenchmark(XmlConfigParser.TestConfig cfg, Set<Platform> cliPlatforms) throws Exception {
        Set<Platform> platforms = resolvePlatforms(cfg, cliPlatforms);
        platforms.remove(Platform.AZURE); // Azure excluded from unified FIV

        String csvPath = PROJECT_DIR + "\\" + (cfg != null && cfg.csvFile != null ? cfg.csvFile : "FIV.csv");
        int[] memSizes = resolveMemories(cfg, DEFAULT_MEMORY_SIZES);
        int reps = resolveReps(cfg, DEFAULT_REPETITIONS);
        int fibN = cfg != null ? cfg.fibN : 40;

        System.out.println("\n========== FIB Benchmark (fib(" + fibN + ")) ==========");
        writeCsvHeader(csvPath, "CSP,Memory_MB,Run_ID,Execution_Time_ms");

        for (Platform p : platforms) {
            try {
                benchmarkFibPlatform(p, csvPath, memSizes, reps, fibN);
            } catch (Exception e) {
                System.err.println("PLATFORM " + p + " FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    static void benchmarkFibPlatform(Platform p, String csvPath, int[] memSizes, int reps, int fibN) throws Exception {
        System.out.println("\n--- " + p + " (fib) ---");
        swapProperties(p);
        props = loadProperties();

        if (!testMode && !skipDeploy) deployCode(p);

        for (int memMB : memSizes) {
            System.out.println("  Memory=" + memMB + "MB");
            if (p != Platform.AZURE) {
                try { updateMemory(p, memMB); }
                catch (Exception e) { System.err.println("  Memory update failed: " + e.getMessage()); continue; }
            }

            // Warm-up
            try {
                invokeFunction(p, buildFibPayload(fibN));
                Thread.sleep(3000);
            } catch (Exception e) { System.err.println("  Warm-up failed: " + e.getMessage()); }

            String payload = buildFibPayload(fibN);
            for (int run = 1; run <= reps; run++) {
                long execTimeMs = -1;
                try {
                    String resultJson = invokeFunction(p, payload);
                    ResponseClass resp = JSON.parseObject(resultJson, ResponseClass.class);
                    execTimeMs = (resp.download_time + resp.proofTime) / 1_000_000;
                    System.out.println("    run " + run + ": " + execTimeMs + "ms (fib(" + fibN + "))");
                } catch (Exception e) {
                    System.err.println("    Invoke failed: run=" + run + " - " + e.getMessage());
                }

                appendCsvRow(csvPath, fibCsvRow(p, memMB, run, execTimeMs));
                if (run < reps) Thread.sleep(500);
            }
        }
    }

    // ================================================================
    //  S3 benchmark — s3.csv
    //  CSP,Memory_MB,Run_ID,Execution_Time_ms,Total_Time_ms
    // ================================================================

    static void runS3Benchmark(XmlConfigParser.TestConfig cfg, Set<Platform> cliPlatforms) throws Exception {
        Set<Platform> platforms = resolvePlatforms(cfg, cliPlatforms);
        platforms.remove(Platform.AZURE);

        String csvPath = PROJECT_DIR + "\\" + (cfg != null && cfg.csvFile != null ? cfg.csvFile : "s3.csv");
        int[] memSizes = resolveMemories(cfg, new int[]{256, 512, 1024, 2048});
        int reps = resolveReps(cfg, DEFAULT_REPETITIONS);

        // Load AWS credentials for S3 unified storage
        String s3Bucket = cfg != null && cfg.s3Bucket != null ? cfg.s3Bucket : "scftestbucket-aws";
        String s3Region = cfg != null && cfg.s3Region != null ? cfg.s3Region : "ap-northeast-1";
        awsProps = loadAwsProperties();

        System.out.println("\n========== S3 Unified Storage Benchmark ==========");
        System.out.println("S3 Bucket: " + s3Bucket + ", Region: " + s3Region);

        // One-time: KeyGen + OutSource + upload to S3
        swapProperties(Platform.AWS);
        props = loadProperties();
        IntegrityAuditing ia = prepareDataForS3(s3Bucket, s3Region);
        ChallengeData cd = ia.audit(CHALLENGE_LEN);

        writeCsvHeader(csvPath, "CSP,Memory_MB,Run_ID,Execution_Time_ms,Total_Time_ms");

        for (Platform p : platforms) {
            try {
                benchmarkS3Platform(p, csvPath, memSizes, reps, cd, ia, s3Bucket, s3Region);
            } catch (Exception e) {
                System.err.println("PLATFORM " + p + " FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    static void benchmarkS3Platform(Platform p, String csvPath, int[] memSizes, int reps,
                                     ChallengeData cd, IntegrityAuditing ia,
                                     String s3Bucket, String s3Region) throws Exception {
        System.out.println("\n--- " + p + " (s3) ---");
        swapProperties(p);
        props = loadProperties();

        if (!testMode && !skipDeploy) deployCode(p);

        for (int memMB : memSizes) {
            System.out.println("  Memory=" + memMB + "MB");
            if (p != Platform.AZURE) {
                try { updateMemory(p, memMB); }
                catch (Exception e) { System.err.println("  Memory update failed: " + e.getMessage()); continue; }
            }

            // Warm-up
            try {
                invokeFunction(p, buildS3Payload(cd, s3Bucket, s3Region));
                Thread.sleep(3000);
            } catch (Exception e) { System.err.println("  Warm-up failed: " + e.getMessage()); }

            String payload = buildS3Payload(cd, s3Bucket, s3Region);
            for (int run = 1; run <= reps; run++) {
                long execTimeMs = -1;
                long totalTimeMs = -1;
                try {
                    long t0 = System.nanoTime();
                    String resultJson = invokeFunction(p, payload);
                    long t1 = System.nanoTime();
                    totalTimeMs = (t1 - t0) / 1_000_000;

                    ResponseClass resp = JSON.parseObject(resultJson, ResponseClass.class);
                    execTimeMs = (resp.download_time + resp.proofTime) / 1_000_000;

                    if (!ia.verify(cd, resp.proofData)) {
                        System.err.println("    VERIFY FAILED: " + p + " " + memMB + "MB run=" + run);
                    }
                    System.out.println("    run " + run + ": exec=" + execTimeMs + "ms total=" + totalTimeMs + "ms");
                } catch (Exception e) {
                    System.err.println("    Invoke failed: run=" + run + " - " + e.getMessage());
                }

                appendCsvRow(csvPath, s3CsvRow(p, memMB, run, execTimeMs, totalTimeMs));
                if (run < reps) Thread.sleep(500);
            }
        }
    }

    // ================================================================
    //  AZURE benchmark — azure_benchmark_data.csv
    //  threads,test_id,exec_time,mem_usage
    // ================================================================

    static void runAzureBenchmark(XmlConfigParser.TestConfig cfg) throws Exception {
        String csvPath = PROJECT_DIR + "\\" + (cfg != null && cfg.csvFile != null ? cfg.csvFile : "azure_benchmark_data.csv");
        int[] threadCounts = resolveThreads(cfg, DEFAULT_THREAD_COUNTS);
        int reps = resolveReps(cfg, 10);

        System.out.println("\n========== Azure Benchmark ==========");
        swapProperties(Platform.AZURE);
        props = loadProperties();

        if (!testMode && !skipDeploy) deployCode(Platform.AZURE);

        IntegrityAuditing ia = prepareData(Platform.AZURE);
        ChallengeData cd = ia.audit(CHALLENGE_LEN);

        writeCsvHeader(csvPath, "threads,test_id,exec_time,mem_usage");

        for (int thread : threadCounts) {
            System.out.println("  Thread=" + thread);
            String payload = buildAuditPayload(cd, thread);

            // Warm-up
            try { invokeFunction(Platform.AZURE, payload); Thread.sleep(3000); }
            catch (Exception e) { System.err.println("  Warm-up failed: " + e.getMessage()); }

            for (int run = 1; run <= reps; run++) {
                double execTimeSec = -1;
                try {
                    String resultJson = invokeFunction(Platform.AZURE, payload);
                    ResponseClass resp = JSON.parseObject(resultJson, ResponseClass.class);
                    execTimeSec = (resp.download_time + resp.proofTime) / 1_000_000_000.0;

                    if (!ia.verify(cd, resp.proofData)) {
                        System.err.println("    VERIFY FAILED: Azure thread=" + thread + " run=" + run);
                    }
                    System.out.println("    run " + run + ": " + String.format("%.1f", execTimeSec) + "s");
                } catch (Exception e) {
                    System.err.println("    Invoke failed: run=" + run + " - " + e.getMessage());
                }

                String row = thread + "," + run + "," + (execTimeSec >= 0 ? String.format("%.1f", execTimeSec) : "Failed") + ",";
                appendCsvRow(csvPath, row);
                if (run < reps) Thread.sleep(500);
            }
        }
    }

    // ==================== Properties Management ====================

    static void swapProperties(Platform p) throws IOException {
        String suffix = p.name().toLowerCase();
        Path src = Paths.get(PROJECT_DIR, "Properties-" + suffix);
        Path dst = Paths.get(CONFIG_DIR);
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Switched to " + p + " config");
    }

    static Properties loadProperties() throws IOException {
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(CONFIG_DIR)) {
            p.load(fis);
        }
        return p;
    }

    static Properties loadAwsProperties() throws IOException {
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(PROJECT_DIR + "\\Properties-aws")) {
            p.load(fis);
        }
        return p;
    }

    // ==================== Initial Deploy ====================

    static void deployCode(Platform p) throws Exception {
        File jar = jarFor(p);
        System.out.println("Deploying function code for " + p + " ["
            + jar.getName() + ", " + jar.length() / 1048576 + " MB]...");
        switch (p) {
            case TENCENT: deployTencent(jar); break;
            case ALI:     deployAli(jar); break;
            case AWS:     deployAws(jar); break;
            case AZURE:   deployAzure(); break;
        }
    }

    static void deployTencent(File jar) throws Exception {
        String secretId = props.getProperty("secretId");
        String secretKey = props.getProperty("secretKey");
        String region = props.getProperty("regionName");
        String bucketName = props.getProperty("bucketName");
        String functionName = props.getProperty("functionName");
        String handler = props.getProperty("handler");
        String runtime = props.getProperty("runtime");
        int timeout = Integer.parseInt(props.getProperty("timeout"));

        CloudAPI cosAPI = new CloudAPI(CONFIG_DIR);
        cosAPI.multipartUpload(jar.getAbsolutePath(), 4, "function.jar");
        System.out.println("JAR uploaded to COS");

        com.tencentcloudapi.common.Credential cred =
            new com.tencentcloudapi.common.Credential(secretId, secretKey);
        com.tencentcloudapi.common.profile.ClientProfile cp =
            new com.tencentcloudapi.common.profile.ClientProfile();
        com.tencentcloudapi.scf.v20180416.ScfClient client =
            new com.tencentcloudapi.scf.v20180416.ScfClient(cred, region, cp);

        com.tencentcloudapi.scf.v20180416.models.Code code =
            new com.tencentcloudapi.scf.v20180416.models.Code();
        code.setCosBucketName(bucketName);
        code.setCosObjectName("function.jar");
        code.setCosBucketRegion(region);

        try {
            com.tencentcloudapi.scf.v20180416.models.CreateFunctionRequest req =
                new com.tencentcloudapi.scf.v20180416.models.CreateFunctionRequest();
            req.setFunctionName(functionName);
            req.setHandler(handler);
            req.setRuntime(runtime);
            req.setMemorySize(512L);
            req.setTimeout((long) timeout);
            req.setCode(code);
            client.CreateFunction(req);
            System.out.println("Tencent function created");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("409") || msg.contains("already exists")
                || msg.contains("ResourceInUse") || msg.contains("已存在")
                || msg.contains("FunctionName"))) {
                System.out.println("Function exists, updating code...");
                com.tencentcloudapi.scf.v20180416.models.UpdateFunctionCodeRequest upReq =
                    new com.tencentcloudapi.scf.v20180416.models.UpdateFunctionCodeRequest();
                upReq.setFunctionName(functionName);
                upReq.setHandler(handler);
                upReq.setCode(code);
                client.UpdateFunctionCode(upReq);
                System.out.println("Tencent function code updated");
            } else {
                throw e;
            }
        }
    }

    static void deployAli(File jar) throws Exception {
        String region = props.getProperty("regionName");
        String bucketName = props.getProperty("bucketName");
        String functionName = props.getProperty("functionName");
        String handler = props.getProperty("handler");
        String runtime = props.getProperty("runtime");
        String accountId = props.getProperty("aliAccountId");
        String secretId = props.getProperty("secretId");
        String secretKey = props.getProperty("secretKey");
        int timeout = Integer.parseInt(props.getProperty("timeout"));

        String zipPath = PROJECT_DIR + "\\target\\function.zip";
        createAliZip(jar.getAbsolutePath(), JRE_PATH, zipPath);
        System.out.println("Ali zip created: " + zipPath);

        AliCloudAPI aliAPI = new AliCloudAPI(CONFIG_DIR);
        // A single PUT of the 50-120 MB deploy zip gets reset by the Tokyo link
        // (7 MB succeeds, 52 MB does not), so upload it in ~8 MB parts.
        long zipBytes = new File(zipPath).length();
        int parts = (int) Math.min(16, Math.max(1, zipBytes / (8L * 1024 * 1024)));
        aliAPI.multipartUpload(zipPath, parts, "function.zip");
        System.out.println("Zip uploaded to OSS (" + parts + " parts)");

        String fcEndpoint = accountId + "." + region + ".fc.aliyuncs.com";
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
            .setAccessKeyId(secretId)
            .setAccessKeySecret(secretKey)
            .setEndpoint(fcEndpoint);
        com.aliyun.fc20230330.Client fcClient = new com.aliyun.fc20230330.Client(config);

        com.aliyun.fc20230330.models.InputCodeLocation code =
            new com.aliyun.fc20230330.models.InputCodeLocation();
        code.setOssBucketName(bucketName);
        code.setOssObjectName("function.zip");

        try {
            com.aliyun.fc20230330.models.CreateFunctionInput body =
                new com.aliyun.fc20230330.models.CreateFunctionInput();
            body.setFunctionName(functionName);
            body.setHandler(handler);
            body.setRuntime(runtime);
            body.setMemorySize(512);
            body.setTimeout(timeout);
            body.setCode(code);

            com.aliyun.fc20230330.models.CreateFunctionRequest req =
                new com.aliyun.fc20230330.models.CreateFunctionRequest();
            req.setBody(body);
            fcClient.createFunction(req);
            System.out.println("Ali function created");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("409") || msg.contains("already exists") || msg.contains("已存在"))) {
                System.out.println("Function exists, updating code...");
                com.aliyun.fc20230330.models.UpdateFunctionInput updateBody =
                    new com.aliyun.fc20230330.models.UpdateFunctionInput();
                updateBody.setCode(code);
                com.aliyun.fc20230330.models.UpdateFunctionRequest updateReq =
                    new com.aliyun.fc20230330.models.UpdateFunctionRequest();
                updateReq.setBody(updateBody);
                fcClient.updateFunction(functionName, updateReq);
                System.out.println("Ali function updated");
            } else {
                throw e;
            }
        }
    }

    static void createAliZip(String jarPath, String jrePath, String zipPath) throws IOException {
        String jarName = new File(jarPath).getName();
        String jreName = new File(jrePath).getName();

        Files.deleteIfExists(Paths.get(zipPath));
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        URI uri = URI.create("jar:" + Paths.get(zipPath).toUri());
        try (FileSystem zfs = FileSystems.newFileSystem(uri, env)) {
            Path bootstrapPath = zfs.getPath("bootstrap");
            String bootstrapContent = "#!/bin/bash\n" +
                "export FC_FUNC_CODE_PATH=/code\n" +
                "if [ ! -d /tmp/jre ]; then\n" +
                "  mkdir -p /tmp/jre && tar xzf /code/" + jreName + " -C /tmp/jre --strip-components=1 2>/dev/null\n" +
                "fi\n" +
                "/tmp/jre/bin/java -Dfile.encoding=UTF-8 -cp /code/" + jarName + " com.fchen_group.TPDSInScf.Run.AliHandle\n" +
                "while true; do sleep 1; done\n";
            Files.write(bootstrapPath, bootstrapContent.getBytes());
            setExecutable(bootstrapPath);

            Files.copy(Paths.get(jarPath), zfs.getPath(jarName));
            Files.copy(Paths.get(jrePath), zfs.getPath(jreName));
        }
    }

    static void setExecutable(Path path) throws IOException {
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Arrays.asList(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException e) {
            Set<PosixFilePermission> perms = new HashSet<>(Arrays.asList(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
            Files.setAttribute(path, "zip:permissions", perms);
        }
    }

    static void deployAws(File jar) throws Exception {
        String secretId = props.getProperty("secretId");
        String secretKey = props.getProperty("secretKey");
        String region = props.getProperty("regionName");
        String bucketName = props.getProperty("bucketName");
        String functionName = props.getProperty("functionName");
        String handler = props.getProperty("handler");
        String runtime = props.getProperty("runtime");
        String accountId = props.getProperty("awsAccountId");
        String roleName = props.getProperty("roleName");
        int timeout = Integer.parseInt(props.getProperty("timeout"));

        com.amazonaws.auth.BasicAWSCredentials awsCreds =
            new com.amazonaws.auth.BasicAWSCredentials(secretId, secretKey);
        com.amazonaws.services.s3.AmazonS3 s3Client =
            com.amazonaws.services.s3.AmazonS3ClientBuilder.standard()
                .withCredentials(new com.amazonaws.auth.AWSStaticCredentialsProvider(awsCreds))
                .withRegion(region)
                .build();
        s3Client.putObject(bucketName, "function-aws.jar", jar);
        System.out.println("JAR uploaded to S3");

        com.amazonaws.services.lambda.AWSLambda lambdaClient =
            com.amazonaws.services.lambda.AWSLambdaClientBuilder.standard()
                .withCredentials(new com.amazonaws.auth.AWSStaticCredentialsProvider(awsCreds))
                .withRegion(region)
                .build();

        String roleArn = "arn:aws:iam::" + accountId + ":role/" + roleName;
        com.amazonaws.services.lambda.model.FunctionCode code =
            new com.amazonaws.services.lambda.model.FunctionCode()
                .withS3Bucket(bucketName)
                .withS3Key("function-aws.jar");

        try {
            com.amazonaws.services.lambda.model.CreateFunctionRequest req =
                new com.amazonaws.services.lambda.model.CreateFunctionRequest()
                    .withFunctionName(functionName)
                    .withHandler(handler)
                    .withRuntime(runtime)
                    .withRole(roleArn)
                    .withCode(code)
                    .withMemorySize(512)
                    .withTimeout(timeout);
            lambdaClient.createFunction(req);
            System.out.println("AWS function created");
        } catch (com.amazonaws.services.lambda.model.ResourceConflictException e) {
            System.out.println("Function exists, updating code...");
            com.amazonaws.services.lambda.model.UpdateFunctionCodeRequest upReq =
                new com.amazonaws.services.lambda.model.UpdateFunctionCodeRequest()
                    .withFunctionName(functionName)
                    .withS3Bucket(bucketName)
                    .withS3Key("function-aws.jar");
            lambdaClient.updateFunctionCode(upReq);
            System.out.println("AWS function code updated");
        }
    }

    static void deployAzure() throws Exception {
        System.out.println("Azure deploy via Maven plugin...");
        String mvn = props.getProperty("mavenExecutable");
        String profile = "-Pproduction-Azure,!development-Ten";
        // 1) azure-functions:package — creates target/azure-functions/<appName> staging dir
        runMvn(mvn, "azure-functions:package", profile);
        // 2) azure-functions:deploy
        runMvn(mvn, "azure-functions:deploy", profile);
        System.out.println("Azure deploy complete");
    }

    static void runMvn(String mvnPath, String goal, String... extraArgs) throws IOException {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(mvnPath);
        cmd.add(goal);
        for (String a : extraArgs) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(PROJECT_DIR));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) System.out.println(line);
        }
        try {
            int rc = proc.waitFor();
            if (rc != 0) throw new RuntimeException("Maven command failed with exit code " + rc + ": " + cmd);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Maven command interrupted", e);
        }
    }

    // ==================== Memory Update ====================

    static void updateMemory(Platform p, int memoryMB) throws Exception {
        String secretId = props.getProperty("secretId");
        String secretKey = props.getProperty("secretKey");
        String region = props.getProperty("regionName");
        String functionName = props.getProperty("functionName");

        Exception lastEx = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                updateMemoryInternal(p, memoryMB, secretId, secretKey, region, functionName);
                return; // success
            } catch (Exception e) {
                lastEx = e;
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Updating") || msg.contains("updating") || msg.contains("in progress") || msg.contains("ResourceConflictException") || msg.contains("����"))) {
                    System.out.println("  Function updating, retry " + (attempt + 1) + "/10...");
                    Thread.sleep(10000);
                } else {
                    throw e; // non-retryable error
                }
            }
        }
        throw lastEx;
    }

    static void updateMemoryInternal(Platform p, int memoryMB,
                                      String secretId, String secretKey,
                                      String region, String functionName) throws Exception {

        switch (p) {
            case TENCENT: {
                com.tencentcloudapi.common.Credential cred =
                    new com.tencentcloudapi.common.Credential(secretId, secretKey);
                com.tencentcloudapi.common.profile.ClientProfile cp =
                    new com.tencentcloudapi.common.profile.ClientProfile();
                com.tencentcloudapi.scf.v20180416.ScfClient client =
                    new com.tencentcloudapi.scf.v20180416.ScfClient(cred, region, cp);

                com.tencentcloudapi.scf.v20180416.models.UpdateFunctionConfigurationRequest req =
                    new com.tencentcloudapi.scf.v20180416.models.UpdateFunctionConfigurationRequest();
                req.setFunctionName(functionName);
                req.setMemorySize((long) memoryMB);
                client.UpdateFunctionConfiguration(req);
                break;
            }
            case ALI: {
                String accountId = props.getProperty("aliAccountId");
                String fcEndpoint = accountId + "." + region + ".fc.aliyuncs.com";
                com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
                    .setAccessKeyId(secretId)
                    .setAccessKeySecret(secretKey)
                    .setEndpoint(fcEndpoint);
                com.aliyun.fc20230330.Client fcClient = new com.aliyun.fc20230330.Client(config);

                com.aliyun.fc20230330.models.UpdateFunctionInput body =
                    new com.aliyun.fc20230330.models.UpdateFunctionInput();
                body.setMemorySize(memoryMB);
                com.aliyun.fc20230330.models.UpdateFunctionRequest req =
                    new com.aliyun.fc20230330.models.UpdateFunctionRequest();
                req.setBody(body);
                fcClient.updateFunction(functionName, req);
                break;
            }
            case AWS: {
                com.amazonaws.auth.BasicAWSCredentials awsCreds =
                    new com.amazonaws.auth.BasicAWSCredentials(secretId, secretKey);
                com.amazonaws.services.lambda.AWSLambda lambdaClient =
                    com.amazonaws.services.lambda.AWSLambdaClientBuilder.standard()
                        .withCredentials(new com.amazonaws.auth.AWSStaticCredentialsProvider(awsCreds))
                        .withRegion(region)
                        .build();

                com.amazonaws.services.lambda.model.UpdateFunctionConfigurationRequest req =
                    new com.amazonaws.services.lambda.model.UpdateFunctionConfigurationRequest()
                        .withFunctionName(functionName)
                        .withMemorySize(memoryMB);
                lambdaClient.updateFunctionConfiguration(req);
                break;
            }
            default: break;
        }
    }

    // ==================== Data Preparation ====================

    /** Full data prep: KeyGen + OutSource + upload to platform storage */
    static IntegrityAuditing prepareData(Platform p) throws IOException {
        String filePath = props.getProperty("filePath");
        String tmpPath = props.getProperty("tmpFilePath");

        System.out.println("--- KeyGen + OutSource ---");
        IntegrityAuditing ia = new IntegrityAuditing(filePath, BLOCK_SHARDS, DATA_SHARDS);
        ia.genKey();

        String srcPath = tmpPath + "\\sourceFile.txt";
        String parPath = tmpPath + "\\parities.txt";
        writeSourceAndParity(ia, filePath, srcPath, parPath);
        System.out.println("Source + parity files written");

        uploadData(p, srcPath, parPath);
        System.out.println("Files uploaded to " + p + " storage");
        return ia;
    }

    /** Data prep for S3 test: KeyGen + OutSource + upload to unified S3 bucket */
    static IntegrityAuditing prepareDataForS3(String s3Bucket, String s3Region) throws IOException {
        String filePath = props.getProperty("filePath");
        String tmpPath = props.getProperty("tmpFilePath");

        System.out.println("--- KeyGen + OutSource (for S3) ---");
        IntegrityAuditing ia = new IntegrityAuditing(filePath, BLOCK_SHARDS, DATA_SHARDS);
        ia.genKey();

        String srcPath = tmpPath + "\\sourceFile.txt";
        String parPath = tmpPath + "\\parities.txt";
        writeSourceAndParity(ia, filePath, srcPath, parPath);
        System.out.println("Source + parity files written");

        // Upload to unified S3 bucket
        String awsKey = awsProps.getProperty("secretId");
        String awsSecret = awsProps.getProperty("secretKey");
        com.amazonaws.auth.BasicAWSCredentials creds =
            new com.amazonaws.auth.BasicAWSCredentials(awsKey, awsSecret);
        com.amazonaws.services.s3.AmazonS3 s3 =
            com.amazonaws.services.s3.AmazonS3ClientBuilder.standard()
                .withCredentials(new com.amazonaws.auth.AWSStaticCredentialsProvider(creds))
                .withRegion(s3Region)
                .build();
        s3.putObject(s3Bucket, "sourceFile.txt", new File(srcPath));
        s3.putObject(s3Bucket, "parities.txt", new File(parPath));
        System.out.println("Files uploaded to unified S3: " + s3Bucket);
        return ia;
    }

    static void writeSourceAndParity(IntegrityAuditing ia, String filePath, String srcPath, String parPath) throws IOException {
        int lun = ia.SHARD_NUMBER / ia.my_shard;
        int last = ia.SHARD_NUMBER % ia.my_shard;

        FileInputStream in = new FileInputStream(filePath);
        FileOutputStream osFile = new FileOutputStream(srcPath, false);
        FileOutputStream osParities = new FileOutputStream(parPath, false);

        for (int i = 0; i <= lun; i++) {
            if (i == lun) {
                for (int j = 0; j < last; j++) in.read(ia.originalData[j]);
                for (int j = 0; j < last; j++) osFile.write(ia.originalData[j]);
                ia.outSource(last);
                for (int j = 0; j < last; j++) osParities.write(ia.parity[j]);
                break;
            }
            for (int j = 0; j < ia.my_shard; j++) in.read(ia.originalData[j]);
            for (int j = 0; j < ia.my_shard; j++) osFile.write(ia.originalData[j]);
            ia.outSource(ia.my_shard);
            for (int j = 0; j < ia.my_shard; j++) osParities.write(ia.parity[j]);
        }
        in.close();
        osFile.close();
        osParities.close();
    }

    static void uploadData(Platform p, String srcPath, String parPath) throws IOException {
        String secretId = props.getProperty("secretId");
        String secretKey = props.getProperty("secretKey");
        String region = props.getProperty("regionName");
        String bucketName = props.getProperty("bucketName");
        int blockNum = Integer.parseInt(props.getProperty("blockNum"));

        switch (p) {
            case ALI:
                new AliCloudAPI(CONFIG_DIR).uploadFile(srcPath, "sourceFile.txt");
                new AliCloudAPI(CONFIG_DIR).uploadFile(parPath, "parities.txt");
                break;
            case AWS: {
                com.amazonaws.auth.BasicAWSCredentials creds =
                    new com.amazonaws.auth.BasicAWSCredentials(secretId, secretKey);
                com.amazonaws.services.s3.AmazonS3 s3 =
                    com.amazonaws.services.s3.AmazonS3ClientBuilder.standard()
                        .withCredentials(new com.amazonaws.auth.AWSStaticCredentialsProvider(creds))
                        .withRegion(region)
                        .build();
                s3.putObject(bucketName, "sourceFile.txt", new File(srcPath));
                s3.putObject(bucketName, "parities.txt", new File(parPath));
                break;
            }
            case AZURE: {
                com.azure.storage.blob.BlobServiceClient blobService =
                    new com.azure.storage.blob.BlobServiceClientBuilder()
                        .connectionString(secretId)
                        .buildClient();
                com.azure.storage.blob.BlobContainerClient container =
                    blobService.getBlobContainerClient(bucketName);
                container.getBlobClient("sourceFile.txt").uploadFromFile(srcPath, true);
                container.getBlobClient("parities.txt").uploadFromFile(parPath, true);
                break;
            }
            case TENCENT:
                new CloudAPI(CONFIG_DIR).multipartUpload(srcPath, blockNum, "sourceFile.txt");
                new CloudAPI(CONFIG_DIR).multipartUpload(parPath, 1, "parities.txt");
                break;
        }
    }

    // ==================== Payload Construction ====================

    /** Build payload for audit test (native cloud storage) */
    static String buildAuditPayload(ChallengeData cd, int threadNum) {
        TenRequestClass req = new TenRequestClass(
            BLOCK_SHARDS - DATA_SHARDS, DATA_SHARDS, cd,
            props.getProperty("bucketName"),
            props.getProperty("regionName"),
            props.getProperty("secretId"),
            props.getProperty("secretKey"),
            threadNum
        );
        return JSON.toJSONString(req);
    }

    /** Build payload for Fibonacci test */
    static String buildFibPayload(int fibN) {
        TenRequestClass req = new TenRequestClass(
            BLOCK_SHARDS - DATA_SHARDS, DATA_SHARDS, null,
            props.getProperty("bucketName"),
            props.getProperty("regionName"),
            props.getProperty("secretId"),
            props.getProperty("secretKey"),
            1
        );
        req.testType = "fib";
        req.fibN = fibN;
        return JSON.toJSONString(req);
    }

    /** Build payload for S3 unified storage test */
    static String buildS3Payload(ChallengeData cd, String s3Bucket, String s3Region) {
        TenRequestClass req = new TenRequestClass(
            BLOCK_SHARDS - DATA_SHARDS, DATA_SHARDS, cd,
            s3Bucket,
            s3Region,
            awsProps.getProperty("secretId"),
            awsProps.getProperty("secretKey"),
            1
        );
        req.storageType = "s3";
        return JSON.toJSONString(req);
    }

    // ==================== Function Invocation ====================

    static String invokeFunction(Platform p, String payload) throws Exception {
        switch (p) {
            case TENCENT: return new TenYunControl().invoke(payload);
            case ALI:     return new AliYunControl().invoke(payload);
            case AWS:     return new AwsControl().invoke(payload);
            case AZURE:   return new AzureControl().invoke(payload);
        }
        throw new IllegalArgumentException("Unknown platform: " + p);
    }

    // ==================== CSV Output ====================

    static void writeCsvHeader(String csvPath, String header) throws IOException {
        File f = new File(csvPath);
        if (f.exists() && f.length() > 10) {
            return; // already has data, don't overwrite
        }
        try (FileWriter fw = new FileWriter(csvPath, false)) {
            fw.write("﻿"); // BOM for Excel
            fw.write(header + "\n");
        }
    }

    static void appendCsvRow(String csvPath, String row) {
        try (FileWriter fw = new FileWriter(csvPath, true)) {
            fw.write(row + "\n");
        } catch (IOException e) {
            System.err.println("CSV write error: " + e.getMessage());
        }
    }

    // --- Row formatters ---

    /** Format: Tencent, Ali, AWS, Azure */
    static String cspName(Platform p) {
        switch (p) {
            case TENCENT: return "Tencent";
            case ALI:     return "Ali";
            case AWS:     return "AWS";
            case AZURE:   return "Azure";
            default:      return p.name();
        }
    }

    /** Thread format: "1 Thread", "2 Threads" (matching paper) */
    static String threadLabel(int n) {
        return n == 1 ? "1 Thread" : n + " Threads";
    }

    // ================================================================
    //  COLD START benchmark — cold_start.csv
    //  Alternates memory sizes to force new container creation.
    //  Verifies instanceId change to confirm cold start (§4.5 method).
    //  CSP,Memory_MB,Is_Cold,Run_ID,Execution_Time_ms,Instance_ID
    // ================================================================

    static void runColdStartBenchmark(XmlConfigParser.TestConfig cfg, Set<Platform> cliPlatforms) throws Exception {
        Set<Platform> platforms = resolvePlatforms(cfg, cliPlatforms);
        boolean hasAzure = platforms.remove(Platform.AZURE);

        if (hasAzure) {
            runAzureColdStart(cfg);
        }

        if (platforms.isEmpty()) {
            if (!hasAzure) System.out.println("No platforms with memory-switch capability for cold start test.");
            return;
        }

        String csvPath = PROJECT_DIR + "\\cold_start.csv";
        int[] memPairs = testMode ? new int[]{512, 1024} : (cfg != null && cfg.memorySizes != null ? cfg.memorySizes : DEFAULT_MEMORY_SIZES);
        int reps = 3; // cold start: 3 measurements per memory switch

        writeCsvHeader(csvPath, "CSP,Memory_MB,Is_Cold,Run_ID,Execution_Time_ms,Instance_ID,Total_Time_ms");

        if (!testMode && !skipBuild) prebuildJar();

        for (Platform p : platforms) {
            System.out.println("\n=== COLD START: " + cspName(p) + " ===");
            swapProperties(p);
            props = loadProperties();
            if (!testMode && !skipDeploy) deployCode(p);

            // One-time data preparation (same as audit)
            IntegrityAuditing ia = prepareData(p);
            ChallengeData cd = ia.audit(CHALLENGE_LEN);

            // Cold start measurement: alternate between adjacent memory pairs
            for (int i = 0; i < memPairs.length - 1; i++) {
                int memA = memPairs[i];
                int memB = memPairs[i + 1];

                // Phase 1: A → B (cold start at B)
                System.out.println("  Mem " + memA + " → " + memB);
                updateMemory(p, memA);
                Thread.sleep(5000);
                String payload = buildAuditPayload(cd, 1);
                String prevId = null, currId = null;

                // Warm call at A (or first call after update — may also be cold)
                try {
                    String json = invokeFunction(p, payload);
                    ResponseClass resp = JSON.parseObject(json, ResponseClass.class);
                    prevId = resp.instanceId != null ? resp.instanceId : "unknown";
                    System.out.println("    A(" + memA + "MB) instance=" + prevId);
                } catch (Exception e) { System.err.println("    A call failed: " + e.getMessage()); }

                // Switch memory → force new container
                updateMemory(p, memB);
                Thread.sleep(5000);

                // Measure cold start at B
                for (int r = 1; r <= reps; r++) {
                    long execMs = -1;
                    long totalMs = -1;
                    boolean isCold = false;
                    try {
                        long t0 = System.nanoTime();
                        String json = invokeFunction(p, payload);
                        long t1 = System.nanoTime();
                        totalMs = (t1 - t0) / 1_000_000;
                        ResponseClass resp = JSON.parseObject(json, ResponseClass.class);
                        execMs = (resp.download_time + resp.proofTime) / 1_000_000;
                        currId = resp.instanceId != null ? resp.instanceId : "unknown";
                        isCold = (prevId != null && !currId.equals(prevId));
                        System.out.println("    B(" + memB + "MB) run=" + r + " instance=" + currId + " cold=" + isCold + " time=" + execMs + "ms total=" + totalMs + "ms");
                        prevId = currId;
                    } catch (Exception e) {
                        System.err.println("    B call failed: " + e.getMessage());
                        currId = null;
                        totalMs = -1;
                    }
                    appendCsvRow(csvPath, cspName(p) + "," + memB + "," + isCold + "," + r + "," + (execMs >= 0 ? execMs : "Failed") + "," + (currId != null ? currId : "") + "," + (totalMs >= 0 ? totalMs : "Failed"));
                    if (r < reps) Thread.sleep(2000);
                }

                // Phase 2: B → A (cold start at A, reverse direction)
                System.out.println("  Mem " + memB + " → " + memA);
                updateMemory(p, memB);
                Thread.sleep(5000);
                prevId = null;
                try {
                    String json = invokeFunction(p, payload);
                    ResponseClass resp = JSON.parseObject(json, ResponseClass.class);
                    prevId = resp.instanceId != null ? resp.instanceId : "unknown";
                    System.out.println("    B(" + memB + "MB) instance=" + prevId);
                } catch (Exception e) { System.err.println("    B call failed: " + e.getMessage()); }

                updateMemory(p, memA);
                Thread.sleep(5000);

                for (int r = 1; r <= reps; r++) {
                    long execMs = -1;
                    long totalMs = -1;
                    boolean isCold = false;
                    try {
                        long t0 = System.nanoTime();
                        String json = invokeFunction(p, payload);
                        long t1 = System.nanoTime();
                        totalMs = (t1 - t0) / 1_000_000;
                        ResponseClass resp = JSON.parseObject(json, ResponseClass.class);
                        execMs = (resp.download_time + resp.proofTime) / 1_000_000;
                        currId = resp.instanceId != null ? resp.instanceId : "unknown";
                        isCold = (prevId != null && !currId.equals(prevId));
                        System.out.println("    A(" + memA + "MB) run=" + r + " instance=" + currId + " cold=" + isCold + " time=" + execMs + "ms total=" + totalMs + "ms");
                        prevId = currId;
                    } catch (Exception e) {
                        System.err.println("    A call failed: " + e.getMessage());
                        currId = null;
                        totalMs = -1;
                    }
                    appendCsvRow(csvPath, cspName(p) + "," + memA + "," + isCold + "," + r + "," + (execMs >= 0 ? execMs : "Failed") + "," + (currId != null ? currId : "") + "," + (totalMs >= 0 ? totalMs : "Failed"));
                    if (r < reps) Thread.sleep(2000);
                }
            }
        }
        System.out.println("Cold start benchmark complete → " + csvPath);
    }

    // ================================================================
    //  AZURE COLD START — azure_cold_start.csv
    //  Azure Consumption Plan has no memory-switch API.
    //  Uses time-based waiting for instance recycling + instanceId comparison.
    //  Also records allocatedMemoryMB to determine MinimumMemory.
    //  CSP,Run_ID,Execution_Time_ms,Instance_ID,Allocated_Memory_MB,Total_Time_ms
    // ================================================================

    static void runAzureColdStart(XmlConfigParser.TestConfig cfg) throws Exception {
        int reps = testMode ? 3 : 10;
        int waitMinutes = testMode ? 2 : 30;

        String csvPath = PROJECT_DIR + "\\azure_cold_start.csv";
        writeCsvHeader(csvPath, "CSP,Run_ID,Execution_Time_ms,Instance_ID,Allocated_Memory_MB,Total_Time_ms");

        System.out.println("\n=== AZURE COLD START ===");
        System.out.println("  Reps=" + reps + "  Wait between runs=" + waitMinutes + "min");
        swapProperties(Platform.AZURE);
        props = loadProperties();
        if (!testMode && !skipDeploy) deployCode(Platform.AZURE);

        IntegrityAuditing ia = prepareData(Platform.AZURE);
        ChallengeData cd = ia.audit(CHALLENGE_LEN);
        String payload = buildAuditPayload(cd, 1); // single thread for cold start

        String prevInstanceId = null;

        for (int run = 1; run <= reps; run++) {
            long execMs = -1;
            long totalMs = -1;
            boolean isCold = false;
            String instanceId = null;
            long allocatedMemMB = -1;

            try {
                long t0 = System.nanoTime();
                String json = invokeFunction(Platform.AZURE, payload);
                long t1 = System.nanoTime();
                totalMs = (t1 - t0) / 1_000_000;
                ResponseClass resp = JSON.parseObject(json, ResponseClass.class);
                execMs = (resp.download_time + resp.proofTime) / 1_000_000;
                instanceId = resp.instanceId != null ? resp.instanceId : "unknown";
                allocatedMemMB = resp.allocatedMemoryMB != null ? resp.allocatedMemoryMB : -1;
                isCold = (prevInstanceId != null && !instanceId.equals(prevInstanceId));

                System.out.println("  run=" + run + " instance=" + instanceId
                    + " cold=" + isCold + " time=" + execMs + "ms total=" + totalMs + "ms mem=" + allocatedMemMB + "MB");

                prevInstanceId = instanceId;
            } catch (Exception e) {
                System.err.println("  Invoke failed: run=" + run + " - " + e.getMessage());
                instanceId = null;
                totalMs = -1;
            }

            appendCsvRow(csvPath, "Azure," + run + ","
                + (execMs >= 0 ? execMs : "Failed") + ","
                + (instanceId != null ? instanceId : "") + ","
                + allocatedMemMB + ","
                + (totalMs >= 0 ? totalMs : "Failed"));

            if (run < reps) {
                System.out.println("  Waiting " + waitMinutes + "min for instance recycling...");
                Thread.sleep(waitMinutes * 60 * 1000L);
            }
        }

        System.out.println("Azure cold start complete → " + csvPath);
        System.out.println("  MinimumMemory = min of Allocated_Memory_MB column");
        System.out.println("  Cold start time = execution times where instanceId changed");
    }

    /** 云审计各自.csv row */
    static String auditCsvRow(Platform p, int memMB, int thread, int runId, long execTimeMs) {
        String timeStr = execTimeMs >= 0 ? String.valueOf(execTimeMs) : "Failed";
        return cspName(p) + "," + memMB + "," + threadLabel(thread) + "," + runId + "," + timeStr;
    }

    /** FIV.csv row */
    static String fibCsvRow(Platform p, int memMB, int runId, long execTimeMs) {
        String timeStr = execTimeMs >= 0 ? String.valueOf(execTimeMs) : "";
        return cspName(p) + "," + memMB + "," + runId + "," + timeStr;
    }

    /** s3.csv row */
    static String s3CsvRow(Platform p, int memMB, int runId, long execTimeMs, long totalTimeMs) {
        String execStr = execTimeMs >= 0 ? String.valueOf(execTimeMs) : "Failed";
        String totalStr = totalTimeMs >= 0 ? String.valueOf(totalTimeMs) : "Failed";
        return cspName(p) + "," + memMB + "," + runId + "," + execStr + "," + totalStr;
    }
}
