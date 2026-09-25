package com.fchen_group.TPDSInScf.Utils;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fchen_group.TPDSInScf.Core.ChallengeData;

public class TenRequestClass {
    @JsonProperty("PARITY_SHARDS")
    public int PARITY_SHARDS;
    @JsonProperty("DaTA_SHARDS")
    public int DaTA_SHARDS;
    @JsonProperty("challengeData")
    public ChallengeData challengeData;
    @JsonProperty("bucketName")
    public String bucketName;
    @JsonProperty("regionName")
    public String regionName;
    @JsonProperty("secretId")
    public String secretId;
    @JsonProperty("secretKey")
    public String secretKey;
    @JsonProperty("threadNum")
    public int threadNum;
    @JsonProperty("testType")
    public String testType;
    @JsonProperty("storageType")
    public String storageType;
    @JsonProperty("fibN")
    public int fibN;
    // "fast" (default) = the handlers' fast-doubling fib(); "iter" = FibIter, the paper's O(n) task.
    @JsonProperty("fibAlgo")
    public String fibAlgo;

    public TenRequestClass() {}

    @JsonCreator
    public TenRequestClass(@JsonProperty("PARITY_SHARDS") int PARITY_SHARDS, @JsonProperty("DaTA_SHARDS") int DaTA_SHARDS, @JsonProperty("challengeData") ChallengeData challengeData, @JsonProperty("bucketName") String bucketName,
                           @JsonProperty("regionName")  String regionName, @JsonProperty("secretId") String secretId, @JsonProperty("secretKey") String secretKey, @JsonProperty("threadNum") int threadNum){
        this.PARITY_SHARDS=PARITY_SHARDS;
        this.DaTA_SHARDS=DaTA_SHARDS;
        this.challengeData=challengeData;
        this.bucketName=bucketName;
        this.regionName=regionName;
        this.secretId=secretId;
        this.secretKey=secretKey;
        this.threadNum=threadNum;
    }

    public int getThreadNum() {
        return threadNum;
    }

    public ChallengeData getChallengeData() {
        return challengeData;
    }

    public int getDaTA_SHARDS() {
        return DaTA_SHARDS;
    }

    public int getPARITY_SHARDS() {
        return PARITY_SHARDS;
    }

    public String getBucketName() {
        return bucketName;
    }

    public String getRegionName() {
        return regionName;
    }

    public String getSecretId() {
        return secretId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setThreadNum(int threadNum) {
        this.threadNum = threadNum;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public void setChallengeData(ChallengeData challengeData) {
        this.challengeData = challengeData;
    }

    public void setDaTA_SHARDS(int daTA_SHARDS) {
        DaTA_SHARDS = daTA_SHARDS;
    }

    public void setPARITY_SHARDS(int PARITY_SHARDS) {
        this.PARITY_SHARDS = PARITY_SHARDS;
    }

    public void setRegionName(String regionName) {
        this.regionName = regionName;
    }

    public void setSecretId(String secretId) {
        this.secretId = secretId;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getTestType() { return testType; }
    public void setTestType(String testType) { this.testType = testType; }
    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }
    public int getFibN() { return fibN; }
    public void setFibN(int fibN) { this.fibN = fibN; }
    public String getFibAlgo() { return fibAlgo; }
    public void setFibAlgo(String fibAlgo) { this.fibAlgo = fibAlgo; }
}
