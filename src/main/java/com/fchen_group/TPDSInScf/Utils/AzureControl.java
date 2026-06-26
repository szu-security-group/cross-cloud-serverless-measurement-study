package com.fchen_group.TPDSInScf.Utils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;

/**
 * Azure Functions management — deploy via Maven plugin, invoke via HTTP POST.
 * Azure has no "createFunction" SDK like AWS/Ali/Tencent; uses CLI/plugin.
 */
public class AzureControl {

    static String configFilePath = System.getProperty("user.dir") + "\\Properties";

    /**
     * Deploy function to Azure using Maven azure-functions:deploy
     */
    public void deploy(String projectDir, String mavenExecutable) throws Exception {
        // 1. Maven clean package (production-Azure profile)
        runMavenCommand(projectDir, mavenExecutable, "package", "-Pproduction-Azure", "-DskipTests");
        System.out.println("Maven build OK");

        // 2. Deploy with Maven plugin
        runMavenCommand(projectDir, mavenExecutable, "azure-functions:deploy", "-Pproduction-Azure");
        System.out.println("Azure deploy OK");
    }

    /**
     * Invoke Azure Function via HTTP POST to its public URL.
     * URL: https://{functionAppName}.azurewebsites.net/api/audit
     */
    public String invoke(String testData) {
        String result = null;
        try {
            FileInputStream fis = new FileInputStream(configFilePath);
            Properties props = new Properties();
            props.load(fis);
            fis.close();
            String functionUrl = props.getProperty("functionUrl");

            String url = "https://" + functionUrl + "/api/Audit";
            System.out.println("Azure function URL: " + url);

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(600000);
            conn.setRequestProperty("Content-Type", "application/json");

            OutputStream os = conn.getOutputStream();
            os.write(testData.getBytes("UTF-8"));
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            System.out.println("Azure invoke status: " + status);

            InputStream is = (status >= 200 && status < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            java.util.Scanner scanner = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
            result = scanner.hasNext() ? scanner.next() : "";
            scanner.close();
            System.out.println(result);

        } catch (Exception e) {
            System.out.println("Error invoking Azure Function:");
            e.printStackTrace();
        }
        return result;
    }

    private void runMavenCommand(String projectDir, String mavenExecutable,
                                  String... goals) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new File(projectDir));

        String[] cmd = new String[1 + goals.length];
        cmd[0] = mavenExecutable;
        System.arraycopy(goals, 0, cmd, 1, goals.length);
        pb.command(cmd);

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

        AzureControl control = new AzureControl();
        control.deploy(projectDir, mavenExecutable);
    }
}
