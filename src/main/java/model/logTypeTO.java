package model;

import java.io.Serializable;


public class logTypeTO implements Serializable{
    
    private String log_name;
    private int log_type_id;

    public logTypeTO() {
    }

    public String getLog_name() {
        return log_name;
    }

    public void setLog_name(String log_name) {
        this.log_name = log_name;
    }

    public int getLog_type_id() {
        return log_type_id;
    }

    public void setLog_type_id(int log_type_id) {
        this.log_type_id = log_type_id;
    }
    
    
    
}

