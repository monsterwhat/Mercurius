package service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;


public class Service {
    
    protected Connection connection = null;
    private String DB_host = "localhost";
    private String DB_port = "3306";
    private String DB_name = "mercurius";
    private String DB_user = "Mercury";
    private String DB_password = "admin";
    
    public void connect(){
        try{
            Class.forName("com.mysql.cj.jdbc.Driver");
            connection = DriverManager.getConnection("jdbc:mysql://"+
                        DB_host+":"+DB_port+"/"+DB_name+"?serverTimezone=UTC",DB_user,DB_password);
        }catch(ClassNotFoundException | SQLException e){
            System.out.println("Error al conectarse a la base de datos! " + e);
            
        }
    }
    
    public void closeStatement(Statement statement){
        try{
            if(statement != null && !statement.isClosed()){
                statement.close();
                statement = null;
            }
        } catch(SQLException e){
            System.out.println("Error cerrando el Statement! " + e);   
        }
    }
    
    public void closeResultSet(ResultSet resultSet){
        try{
            if(resultSet != null && !resultSet.isClosed()){
                resultSet.close();
                resultSet = null;
            }
        } catch(SQLException e){
            System.out.println("Error cerrando el ResultSet! " + e);      
        }
    }
    
    public void closePreparedStatement(PreparedStatement preparedStatement){
        try{
            if(preparedStatement != null && !preparedStatement.isClosed()){
                preparedStatement.close();
                preparedStatement = null;
            }
        } catch(SQLException e){
            System.out.println("Error al cerrar el preparedStatement! " + e);
        }
    }
    
    public void disconnect(){
        try{
            if(connection != null && !connection.isClosed()){
                connection.close();
                connection = null;
            }
        } catch(SQLException e){
            System.out.println("Error al tratar de cerrar la conecion! " + e);   
        } 
    }
    
}
