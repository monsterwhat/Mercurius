
package Model;

import java.sql.Time;

public class User_LogTO {
    private int user_id;
    private int log_id;
    private Time time;

    public User_LogTO(int user_id, int log_id, Time time) {
        this.user_id = user_id;
        this.log_id = log_id;
        this.time = time;
    }
    
    public int getUser_id() {
        return user_id;
    }

    public void setUser_id(int user_id) {
        this.user_id = user_id;
    }

    public int getLog_id() {
        return log_id;
    }

    public void setLog_id(int log_id) {
        this.log_id = log_id;
    }

    public Time getTime() {
        return time;
    }

    public void setTime(Time time) {
        this.time = time;
    }
    
    
}
