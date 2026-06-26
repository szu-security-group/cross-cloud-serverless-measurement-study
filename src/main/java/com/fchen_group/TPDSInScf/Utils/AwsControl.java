package com.fchen_group.TPDSInScf.Utils;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * AWS Lambda management
 * createFunction / invoke — mirrors TenYunControl.java and AliYunControl.java
 */
public class AwsControl {

    static String configFilePath = System.getProperty("user.dir") + "\\Properties";

    public AWSLambda createLambdaClient() throws Exception {
        FileInputStream fis = new FileInputStream(configFilePath);
        Properties props = new Properties();
        props.load(fis);
        fis.close();

        BasicAWSCredentials creds = new BasicAWSCredentials(
                props.getProperty("secretId"),
                props.getProperty("secretKey"));

        return AWSLambdaClientBuilder.standard()
                .withRegion(props.getProperty("regionName"))
                .withCredentials(new AWSStaticCredentialsProvider(creds))
                .build();
    }

    public AmazonS3 createS3Client() throws Exception {
        FileInputStream fis = new FileInputStream(configFilePath);
        Properties props = new Properties();
        props.load(fis);
        fis.close();

        BasicAWSCredentials creds = new BasicAWSCredentials(
                props.getProperty("secretId"),
                props.getProperty("secretKey"));

        return AmazonS3ClientBuilder.standard()
                .withRegion(props.getProperty("regionName"))
                .withCredentials(new AWSStaticCredentialsProvider(creds))
                .build();
    }

    /**
     * Build project with Maven, package as zip, upload to S3, create Lambda function.
     */
    public void createFunction(String projectDir, String mavenExecutable,
                                String functionName, String handler, String runtime,
                                int memorySize, int timeout) throws Exception {

        // 1. Maven build
        runMavenCommand(projectDir, mavenExecutable);
        System.out.println("Maven build OK");

        // 2. Upload fat JAR directly to S3 (Lambda can use JAR directly, no zip needed)
        String jarFilePath = projectDir + "\\target\\TPDSInSCF-1.0-SNAPSHOT_Benchmark-jar-with-dependencies.jar";
        String s3Key = "function-aws.jar";
        AmazonS3 s3Client = createS3Client();
        Properties props = new Properties();
        FileInputStream fis = new FileInputStream(configFilePath);
        props.load(fis);
        fis.close();
        String bucketName = props.getProperty("bucketName");
        s3Client.putObject(bucketName, s3Key, new File(jarFilePath));
        System.out.println("JAR uploaded to S3: " + s3Key);

        // 4. Get role ARN
        String accountId = props.getProperty("awsAccountId");
        String roleName = props.getProperty("roleName");
        String roleArn = "arn:aws:iam::" + accountId + ":role/" + roleName;

        // 5. Create or update function
        AWSLambda lambdaClient = createLambdaClient();

        FunctionCode code = new FunctionCode()
                .withS3Bucket(bucketName)
                .withS3Key(s3Key);

        try {
            CreateFunctionRequest req = new CreateFunctionRequest()
                    .withFunctionName(functionName)
                    .withHandler(handler)
                    .withRuntime(runtime)
                    .withRole(roleArn)
                    .withCode(code)
                    .withMemorySize(memorySize)
                    .withTimeout(timeout);
            CreateFunctionResult resp = lambdaClient.createFunction(req);
            System.out.println("Function created: " + resp.getFunctionArn());
        } catch (ResourceConflictException e) {
            System.out.println("Function already exists, updating code...");
            UpdateFunctionCodeRequest updateReq = new UpdateFunctionCodeRequest()
                    .withFunctionName(functionName)
                    .withS3Bucket(bucketName)
                    .withS3Key(s3Key);
            UpdateFunctionCodeResult updateResp = lambdaClient.updateFunctionCode(updateReq);
            System.out.println("Function code updated: " + updateResp.getFunctionArn());
        }
    }

    /**
     * Create zip containing the fat JAR for AWS Lambda deployment.
     * AWS Lambda Java runtime just needs the jar (or a zip with the jar inside).
     */
    private void createLambdaZip(String jarFilePath, String zipFilePath) throws IOException {
        File jarFile = new File(jarFilePath);
        String jarName = jarFile.getName();

        Path zipPath = Paths.get(zipFilePath);
        Files.deleteIfExists(zipPath);

        java.net.URI uri = java.net.URI.create("jar:" + zipPath.toUri());
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("create", "true");
        try (java.nio.file.FileSystem zfs = java.nio.file.FileSystems.newFileSystem(uri, env)) {
            Path jarInZip = zfs.getPath(jarName);
            Files.copy(Paths.get(jarFilePath), jarInZip);
        }
    }

    /**
     * Invoke Lambda function synchronously via SDK
     */
    public String invoke(String testData) {
        String result = null;
        try {
            FileInputStream fis = new FileInputStream(configFilePath);
            Properties props = new Properties();
            props.load(fis);
            fis.close();
            String functionName = props.getProperty("functionName");

            AWSLambda lambdaClient = createLambdaClient();

            InvokeRequest req = new InvokeRequest()
                    .withFunctionName(functionName)
                    .withPayload(testData)
                    .withInvocationType(InvocationType.RequestResponse);

            InvokeResult invokeResult = lambdaClient.invoke(req);

            result = new String(invokeResult.getPayload().array(), "UTF-8");
            System.out.println(result);

        } catch (Exception e) {
            System.out.println("Error invoking AWS Lambda function:");
            e.printStackTrace();
        }
        return result;
    }

    private void runMavenCommand(String projectDir, String mavenExecutable) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(projectDir));
        pb.command(mavenExecutable, "package", "-Pdevelopment-AWS", "-DskipTests");

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

        AwsControl control = new AwsControl();
        control.createFunction(projectDir, mavenExecutable, functionName,
                handler, runtime, memorySize, timeout);
    }
}
