package service;

import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import model.*;

public class User_Service extends Service {

    public boolean VerifyUsername(String UserName) {
        Statement statement = null;
        ResultSet resultset = null;

        try {
            connect();

            statement = connection.createStatement();
            String sql = "SELECT * FROM user WHERE user_name='" + UserName + "'";
            resultset = statement.executeQuery(sql);
            if (resultset.next()) {
                return true;
            }

        } catch (SQLException e) {
            System.out.println("Error verifying E-mail! + " + e.getMessage());
        } finally {
            closeResultSet(resultset);
            closeStatement(statement);
            disconnect();
        }
        return false;
    }
    
    public boolean verifyLoginBoolean(String user_name, String user_password) {

        Statement statement = null;
        ResultSet resultSet = null;

        try {
            connect();

            statement = connection.createStatement();
            String sql = "SELECT * FROM user WHERE user_name = '" + user_name + "' AND user_password = '" + user_password + "'";
            resultSet = statement.executeQuery(sql);
            if (resultSet.next()) {
                return true;
            }
        } catch (SQLException e) {
            System.out.println("Error tratando de cargar datos de usuario (conectando!)! " + e.getMessage());
        } finally {
            closeResultSet(resultSet);
            closeStatement(statement);
            disconnect();
        }
        return false;
    }
    
    public userTO verifyLogin(String user_name, String user_password) {

        Statement statement = null;
        ResultSet resultSet = null;
        userTO UserTO = null;

        try {
            connect();

            statement = connection.createStatement();
            String sql = "SELECT * FROM user WHERE user_name = '" + user_name + "' AND user_password = '" + user_password + "'";
            resultSet = statement.executeQuery(sql);
            if (resultSet.next()) {
                int position_idTO = resultSet.getInt("position_id");
                int user_idTO = resultSet.getInt("user_id");
                String user_nameTO = resultSet.getString("user_name");
                Blob user_passwordTO = resultSet.getBlob("user_password");

                UserTO = new userTO(position_idTO,user_idTO,user_nameTO,user_passwordTO);
            }
        } catch (SQLException e) {
            System.out.println("Error tratando de cargar datos de usuario (conectando!)! " + e.getMessage());
        } finally {
            closeResultSet(resultSet);
            closeStatement(statement);
            disconnect();
        }
        return UserTO;
    }
    
    public List<userTO> loadUserData() {
        Statement statement = null;
        ResultSet resultSet = null;
        List<userTO> userList = new ArrayList<>();

        try {

            connect();
            statement = connection.createStatement();
            String sql = "SELECT * FROM user";
            resultSet = statement.executeQuery(sql);

            while (resultSet.next()) {
                int position_id = resultSet.getInt("position_id");
                int user_id = resultSet.getInt("user_id");
                String user_name = resultSet.getString("user_name");
                Blob user_password = resultSet.getBlob("user_password");

                userTO usuarioTO = new userTO(position_id, user_id, user_name, user_password);

                userList.add(usuarioTO);
            }
        } catch (SQLException e) {
            System.out.println("Error al cargar la lista de usuarios! " + e.getMessage());
        } finally {
            closeResultSet(resultSet);
            closeStatement(statement);
            disconnect();
        }
        return userList;
    }

}
