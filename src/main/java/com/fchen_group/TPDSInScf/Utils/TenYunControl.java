package com.fchen_group.TPDSInScf.Utils;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.scf.v20180416.ScfClient;
import com.tencentcloudapi.scf.v20180416.models.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

public class TenYunControl  {


    static String cosConfigFilePath = System.getProperty("user.dir")+"\\Properties";
    public static ScfClient createClient() throws IOException {
        String secretId;
        String secretKey;
        String region;
        FileInputStream propertiesFIS = new FileInputStream(cosConfigFilePath);
        Properties properties = new Properties();
        properties.load(propertiesFIS);
        propertiesFIS.close();
        secretId = properties.getProperty("secretId");
        secretKey = properties.getProperty("secretKey");
        region = properties.getProperty("regionName");
        Credential cred = new Credential(secretId, secretKey);
        ClientProfile clientProfile = new ClientProfile();
        ScfClient client = new ScfClient(cred, region, clientProfile);

        return client;
    }

    public void createFunction(String projectDir,String mavenExecutable,String functionName, String handler, String runtime, int memorySize, int timeout, String region) throws IOException {
        String jarFilePath=projectDir+"\\target\\TPDSInSCF-1.0-SNAPSHOT_Benchmark-jar-with-dependencies.jar";

        runMavenCommand(projectDir,mavenExecutable);
        System.out.println("ok");
        createFunctionWithJar(jarFilePath,functionName,handler,runtime,memorySize,timeout,region);
    }
    public void createFunctionWithJar(String jarFilePath, String functionName, String handler, String runtime, int memorySize, int timeout, String region) {
        try {
            ScfClient client = createClient();
            client.setRegion(region);
            CreateFunctionRequest req = new CreateFunctionRequest();
            req.setFunctionName(functionName);
            req.setHandler(handler);
            req.setRuntime(runtime);
            req.setMemorySize((long) memorySize);
            req.setTimeout((long) timeout);
            // 读取JAR文件的字节内容
            byte[] jarBytes = Files.readAllBytes(Paths.get(jarFilePath));

            // 将JAR文件内容编码为Base64字符串
            String jarBase64 = java.util.Base64.getEncoder().encodeToString(jarBytes);
            // 设置Code对象
            Code code = new Code();
            code.setZipFile(jarBase64);
            req.setCode(code);

            // 创建函数
            CreateFunctionResponse resp = client.CreateFunction(req);
            System.out.println(CreateFunctionResponse.toJsonString(resp));

        } catch (TencentCloudSDKException e) {
            System.out.println("Error: " + e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void runMavenCommand(String projectDir,String mavenExecutable) throws IOException {
        // 替换为你 Maven 可执行文件的完整路径


        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(new File(projectDir));
        processBuilder.command(mavenExecutable, "clean", "package", "-Pproduction-Ten");

        Process process = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading Maven output", e);
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

    public String invoke(String testData) {
        String result = null;
        try {
            String functionName;
            String regionName;
            FileInputStream propertiesFIS = new FileInputStream(cosConfigFilePath);
            Properties properties = new Properties();
            properties.load(propertiesFIS);
            propertiesFIS.close();
            functionName = properties.getProperty("functionName");
            regionName = properties.getProperty("regionName");
            ScfClient client = createClient();
            client.setRegion(regionName);
            InvokeFunctionRequest req = new InvokeFunctionRequest();
            req.setFunctionName(functionName);
            req.setNamespace("default");
            req.setQualifier("$LATEST");
            req.setEvent(testData);
            InvokeFunctionResponse output = client.InvokeFunction(req);
            Result outputResult = output.getResult();
            result = outputResult.getRetMsg();
            System.out.println(result);
        } catch (TencentCloudSDKException e) {
            System.out.println("???");
            System.out.println("Error: " + e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return result;
    }



    public static void main(String[] args) throws Exception {
        String projectDir = System.getProperty("user.dir");
        Properties properties = new Properties();
        try {
            FileInputStream fis = new FileInputStream(cosConfigFilePath);
            properties.load(fis);
            fis.close();

            // 从 properties 文件中读取变量
            String mavenExecutable = properties.getProperty("mavenExecutable");
            String functionName = properties.getProperty("functionName");
            String handler = properties.getProperty("handler");
            String runtime = properties.getProperty("runtime");
            int memorySize = Integer.parseInt(properties.getProperty("memorySize"));
            int timeout = Integer.parseInt(properties.getProperty("timeout"));
            String region = properties.getProperty("regionName");

            // 打印输出验证读取是否成功
            System.out.println("Maven Executable: " + mavenExecutable);
            System.out.println("Function Name: " + functionName);
            System.out.println("Handler: " + handler);
            System.out.println("Runtime: " + runtime);
            System.out.println("Memory Size: " + memorySize);
            System.out.println("Timeout: " + timeout);
            System.out.println("Region: " + region);

            // 继续你的其他代码逻辑
            TenYunControl tenYunControl=new TenYunControl();
            tenYunControl.createFunction(projectDir,mavenExecutable,functionName,handler,runtime,memorySize,timeout,region);
        } catch (IOException e) {
            System.out.println("配置文件读取失败：" + e.getMessage());
        }
    }
}
