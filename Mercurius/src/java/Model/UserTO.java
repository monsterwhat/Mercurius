
package Model;

import java.sql.Blob;

public class UserTO {
    private int user_id;
    private String user_name;
    private Blob user_password;
    private int position_id;

    public UserTO(int user_id, String user_name, Blob user_password, int position_id) {
        this.user_id = user_id;
        this.user_name = user_name;
        this.user_password = user_password;
        this.position_id = position_id;
    }

    public int getUser_id() {
        return user_id;
    }

    public void setUser_id(int user_id) {
        this.user_id = user_id;
    }

    public String getUser_name() {
        return user_name;
    }

    public void setUser_name(String user_name) {
        this.user_name = user_name;
    }

    public Blob getUser_password() {
        return user_password;
    }

    public void setUser_password(Blob user_password) {
        this.user_password = user_password;
    }

    public int getPosition_id() {
        return position_id;
    }

    public void setPosition_id(int position_id) {
        this.position_id = position_id;
    }
    
    
}
