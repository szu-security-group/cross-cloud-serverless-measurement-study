package com.fchen_group.TPDSInScf.Utils;


import com.fchen_group.TPDSInScf.Core.ProofData;

public class ResponseClass {

    public Long download_time;
    public Long proofTime;
    public ProofData proofData;
    public String instanceId;



    public ResponseClass(ProofData proofData) {
        this.proofData=proofData;
    }



    public ResponseClass(){};
}
