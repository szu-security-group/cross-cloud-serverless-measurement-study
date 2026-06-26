package com.fchen_group.TPDSInScf.Utils;

import com.aliyun.fc20230330.Client;
import com.aliyun.fc20230330.models.*;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Alibaba Cloud Function Compute (FC) management
 * createFunction / invoke — mirrors TenYunControl.java
 */
public class AliYunControl {

    static String configFilePath = System.getProperty("user.dir") + "\\Properties";

    public Client createClient() throws Exception {
        String accessKeyId;
        String accessKeySecret;
        String region;

        FileInputStream fis = new FileInputStream(configFilePath);
        Properties props = new Properties();
        props.load(fis);
        fis.close();

        accessKeyId = props.getProperty("secretId");
        accessKeySecret = props.getProperty("secretKey");
        region = props.getProperty("regionName");

        // FC 3.0 endpoint: {accountId}.{region}.fc.aliyuncs.com
        String accountId = props.getProperty("aliAccountId");
        String endpoint = accountId + "." + region + ".fc.aliyuncs.com";
        System.out.println("FC endpoint: " + endpoint);

        Config config = new Config()
                .setAccessKeyId(accessKeyId)
                .setAccessKeySecret(accessKeySecret)
                .setEndpoint(endpoint)
                .setConnectTimeout(10000)
                .setReadTimeout(600000);

        return new Client(config);
    }

    /**
     * Build project with Maven, package as zip (bootstrap + jar), upload to OSS, and create FC function.
     * Uses OSS reference to avoid base64-encoding large zip files.
     */
    public void createFunction(String projectDir, String mavenExecutable,
                                String functionName, String handler, String runtime,
                                int memorySize, int timeout) throws Exception {

        // 1. Maven build
        runMavenCommand(projectDir, mavenExecutable);
        System.out.println("Maven build OK");

        // 2. Package jar into zip with bootstrap script
        String jarFilePath = projectDir + "\\target\\TPDSInSCF-1.0-SNAPSHOT_Benchmark-jar-with-dependencies.jar";
        String zipFilePath = projectDir + "\\target\\function.zip";
        createFunctionZip(jarFilePath, zipFilePath);
        System.out.println("Zip created: " + zipFilePath);

        // 3. Upload zip to OSS first (avoids base64 timeout for large files)
        String ossObjectName = "function.zip";
        AliCloudAPI aliAPI = new AliCloudAPI(configFilePath);
        aliAPI.uploadFile(zipFilePath, ossObjectName);
        System.out.println("Zip uploaded to OSS: " + ossObjectName);

        // 4. Get OSS bucket name
        FileInputStream fis = new FileInputStream(configFilePath);
        Properties props = new Properties();
        props.load(fis);
        fis.close();
        String bucketName = props.getProperty("bucketName");

        // 5. Create or update function via SDK, referencing code in OSS
        Client client = createClient();

        InputCodeLocation code = new InputCodeLocation();
        code.setOssBucketName(bucketName);
        code.setOssObjectName(ossObjectName);

        try {
            CreateFunctionInput body = new CreateFunctionInput();
            body.setFunctionName(functionName);
            body.setHandler(handler);
            body.setRuntime(runtime);
            body.setMemorySize(memorySize);
            body.setTimeout(timeout);
            body.setCode(code);

            CreateFunctionRequest req = new CreateFunctionRequest();
            req.setBody(body);

            CreateFunctionResponse resp = client.createFunction(req);
            System.out.println("Function created: " + resp.getHeaders());
        } catch (TeaException e) {
            if (e.getCode().equals("409") || e.getMessage().contains("already exists")) {
                System.out.println("Function already exists, updating code...");
                UpdateFunctionInput updateBody = new UpdateFunctionInput();
                updateBody.setCode(code);
                UpdateFunctionRequest updateReq = new UpdateFunctionRequest();
                updateReq.setBody(updateBody);
                UpdateFunctionResponse updateResp = client.updateFunction(functionName, updateReq);
                System.out.println("Function updated: " + updateResp.getHeaders());
            } else {
                throw e;
            }
        }
    }

    /**
     * Create zip containing bootstrap script and fat JAR.
     * Uses NIO ZipFileSystem to set Unix execute permissions on bootstrap.
     */
    private void createFunctionZip(String jarFilePath, String zipFilePath) throws IOException {
        String jarName = new File(jarFilePath).getName();

        Path zipPath = Paths.get(zipFilePath);
        Files.deleteIfExists(zipPath);

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + zipPath.toUri());
        try (FileSystem zfs = FileSystems.newFileSystem(uri, env)) {
            // Write bootstrap with execute permissions (rwxr-xr-x = 0755)
            Path bootstrapPath = zfs.getPath("bootstrap");
            String bootstrapContent = "#!/bin/bash\n" +
                    "export FC_FUNC_CODE_PATH=/code\n" +
                    "java -cp /code/" + jarName + " com.fchen_group.TPDSInScf.Run.AliHandle\n" +
                    "while true; do sleep 1; done\n";
            Files.write(bootstrapPath, bootstrapContent.getBytes());

            Set<PosixFilePermission> perms = new HashSet<>(Arrays.asList(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE));
            try {
                Files.setPosixFilePermissions(bootstrapPath, perms);
            } catch (UnsupportedOperationException e) {
                // Windows fallback: use zip:permissions with the same Set
                System.out.println("POSIX not supported, using zip:permissions attribute");
                Files.setAttribute(bootstrapPath, "zip:permissions", perms);
            }

            // Copy the JAR into the zip
            Path jarInZip = zfs.getPath(jarName);
            Files.copy(Paths.get(jarFilePath), jarInZip);
        }
    }

    /**
     * Invoke FC function synchronously via SDK
     */
    public String invoke(String testData) {
        String result = null;
        try {
            String functionName;
            FileInputStream fis = new FileInputStream(configFilePath);
            Properties props = new Properties();
            props.load(fis);
            fis.close();
            functionName = props.getProperty("functionName");

            Client client = createClient();

            InvokeFunctionRequest req = new InvokeFunctionRequest();
            req.setBody(new ByteArrayInputStream(testData.getBytes("UTF-8")));
            req.setQualifier("LATEST");

            InvokeFunctionResponse resp = client.invokeFunction(functionName, req);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = resp.getBody().read(buf)) != -1) {
                buffer.write(buf, 0, n);
            }
            result = buffer.toString("UTF-8");
            System.out.println(result);

        } catch (Exception e) {
            System.out.println("Error invoking Alibaba FC function:");
            e.printStackTrace();
        }
        return result;
    }

    private void runMavenCommand(String projectDir, String mavenExecutable) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(projectDir));
        pb.command(mavenExecutable, "package", "-Pdevelopment-Ali", "-DskipTests");

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Maven command failed with exit code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Maven command was interrupted", e);
        }
    }

    public static void main(String[] args) throws Exception {
        String projectDir = System.getProperty("user.dir");
        Properties props = new Properties();
        FileInputStream fis = new FileInputStream(configFilePath);
        props.load(fis);
        fis.close();

        String mavenExecutable = props.getProperty("mavenExecutable");
        String functionName = props.getProperty("functionName");
        String handler = props.getProperty("handler");
        String runtime = props.getProperty("runtime");
        int memorySize = Integer.parseInt(props.getProperty("memorySize"));
        int timeout = Integer.parseInt(props.getProperty("timeout"));

        AliYunControl control = new AliYunControl();
        control.createFunction(projectDir, mavenExecutable, functionName,
                handler, runtime, memorySize, timeout);
    }
}
