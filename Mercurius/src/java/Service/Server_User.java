package Service;

import java.sql.PreparedStatement;
import Model.UserTO;
import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.ArrayList;

public class Server_User extends Server {

    public boolean verifyName(String username) {
        Statement statement = null;
        ResultSet resultset = null;

        try {
            connect();
            statement = conexion.createStatement();
            String sql = "SELECT * FROM User WHERE user_name='" + username + "'";
            resultset = statement.executeQuery(sql);
            if (resultset.next()) {
                return false;
            }
        } catch (SQLException e) {
            System.out.println("Error verifying email! + " + e.getMessage());
        } finally {
            closeResultSet(resultset);
            closeStatement(statement);
            disconnect();
        }
        return true;
    }

    public UserTO loadUser(String username, String password) {

        Statement statement = null;
        ResultSet resultSet = null;
        UserTO userData = null;

        try {
            connect();

            statement = conexion.createStatement();
            String sql = "SELECT * FROM User WHERE user_name = '" + username + "' AND user_password = SHA2('" + password + "',256)";
            resultSet = statement.executeQuery(sql);
            if (resultSet.next()) {
                int user_id = resultSet.getInt("user_id");
                String user_name = resultSet.getString("user_name");
                Blob user_password = resultSet.getBlob("user_password");
                int position_id = resultSet.getInt("position_id");

                userData = new UserTO(user_id, user_name, user_password, position_id);
            }
        } catch (SQLException e) {
            System.out.println("Error trying to load userData (query)! " + e.getMessage());
        } finally {
            closeResultSet(resultSet);
            closeStatement(statement);
            disconnect();
        }
        return userData;
    }

    public boolean verifyUser(String username, String password) {
        Statement statement = null;
        ResultSet resultSet = null;

        try {
            connect();
            statement = conexion.createStatement();
            String sql = "SELECT * FROM User WHERE user_name = '" + username + "' AND user_password = SHA2('" + password + "',256)";
            resultSet = statement.executeQuery(sql);
            if (resultSet.next()) {
                return true;
            }
        } catch (SQLException e) {
            System.out.println("Error trying to verifyUser (query!)! " + e.getMessage());
        } finally {
            closeResultSet(resultSet);
            closeStatement(statement);
            disconnect();
        }
        return false;
    }

    public UserTO findUser(int id) {

        Statement statement = null;
        ResultSet resultSet = null;
        UserTO userData = null;

        try {
            connect();

            statement = conexion.createStatement();
            String sql = "SELECT * FROM User WHERE user_id = '" + id + "'";
            resultSet = statement.executeQuery(sql);
            if (resultSet.next()) {
                int user_id = resultSet.getInt("user_id");
                String user_name = resultSet.getString("user_name");
                Blob user_password = resultSet.getBlob("user_password");
                int position_id = resultSet.getInt("position_id");

                userData = new UserTO(user_id, user_name, user_password, position_id);
            }
        } catch (SQLException e) {
            System.out.println("Error trying to find user (query)! " + e.getMessage());
        } finally {
            closeResultSet(resultSet);
            closeStatement(statement);
            disconnect();
        }
        return userData;
    }

    public List<UserTO> returnUserList() {
        Statement statement = null;
        ResultSet resultSet = null;
        List<UserTO> userList = new ArrayList<>();

        try {

            connect();
            statement = conexion.createStatement();
            String sql = "SELECT * FROM User";
            resultSet = statement.executeQuery(sql);

            while (resultSet.next()) {
                int user_id = resultSet.getInt("user_id");
                String user_name = resultSet.getString("user_name");
                Blob user_password = resultSet.getBlob("user_password");
                int position_id = resultSet.getInt("position_id");

                UserTO userData = new UserTO(user_id, user_name, user_password, position_id);

                userList.add(userData);
            }
        } catch (SQLException e) {
            System.out.println("Error trying to load userList (query)! " + e.getMessage());
        } finally {
            closeResultSet(resultSet);
            closeStatement(statement);
            disconnect();
        }
        return userList;
    }

    public void insertUser(UserTO userData) {
        PreparedStatement preparedStatement = null;

        try {
            connect();
            String sql = "INSERT INTO User (user_id,user_name,user_password,position_id) VALUES (?,?,?,?)";
            preparedStatement = conexion.prepareStatement(sql);

            preparedStatement.setInt(1, userData.getUser_id());
            preparedStatement.setString(2, userData.getUser_name());
            preparedStatement.setBlob(3, userData.getUser_password());
            preparedStatement.setInt(4, userData.getPosition_id());
            preparedStatement.executeUpdate();

        } catch (SQLException e) {
            System.out.println("Error inserting user (query)! " + e.getMessage());
        } finally {
            closePreparedStatement(preparedStatement);
            disconnect();
        }
    }

    public void updateUser(UserTO userData) {
        PreparedStatement preparedStatement = null;

        try {
            connect();
            String sql = "UPDATE User SET user_name=?,user_passsword=?,position_id=? WHERE user_id='" + userData.getUser_id() + "'";
            preparedStatement = conexion.prepareStatement(sql);

            preparedStatement.setString(1, userData.getUser_name());
            preparedStatement.setBlob(2, userData.getUser_password());
            preparedStatement.setInt(3, userData.getPosition_id());
            preparedStatement.executeUpdate();

        } catch (SQLException e) {
            System.out.println("Error updating the user (query)! " + e.getMessage());
        } finally {
            closePreparedStatement(preparedStatement);
            disconnect();
        }
    }

    public void updatePassword(String username, String password) {
        PreparedStatement preparedStatement = null;

        try {
            connect();
            String sql = "UPDATE User SET user_password=? WHERE user_name = '" + username + "'";
            preparedStatement = conexion.prepareStatement(sql);

            preparedStatement.setString(1, password);
            preparedStatement.executeUpdate();

        } catch (SQLException e) {
            System.out.println("Error updating the password (query)! " + e.getMessage());
        } finally {
            closePreparedStatement(preparedStatement);
            disconnect();
        }

    }

    public void deleteUser(UserTO userData) {
        PreparedStatement preparedStatement = null;

        try {
            connect();
            String sql = "DELETE FROM User WHERE user_id='" + userData.getUser_id() + "'";
            preparedStatement = conexion.prepareStatement(sql);

            preparedStatement.executeUpdate();

        } catch (SQLException e) {
            System.out.println("Error deleting the user (query)! " + e.getMessage());
        } finally {
            closePreparedStatement(preparedStatement);
            disconnect();
        }
    }

}
