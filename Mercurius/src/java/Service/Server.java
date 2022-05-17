/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class Server {
    protected Connection conexion = null;
    protected String salt = null;
    private String host = "localhost";
    private String port = "3306";
    private String dbname = "Mercurius";
    private String user = "mercurius";
    private String password = "password";
    
    public void connect(){
        try{
            Class.forName("com.mysql.cj.jdbc.Driver");
            conexion = DriverManager.getConnection("jdbc:mysql://"+
                        host+":"+port+"/"+dbname+"?serverTimezone=UTC",user,password);
        }catch(ClassNotFoundException | SQLException e){
            System.out.println("Error connecting to the database! " + e);
            
        }
    }
    
    public void closeStatement(Statement statement){
        try{
            if(statement != null && !statement.isClosed()){
                statement.close();
                statement = null;
            }
        } catch(SQLException e){
            System.out.println("Error closing statement! " + e);   
        }
    }
    
    public void closeResultSet(ResultSet resultSet){
        try{
            if(resultSet != null && !resultSet.isClosed()){
                resultSet.close();
                resultSet = null;
            }
        } catch(SQLException e){
            System.out.println("Error closing ResultSet! " + e);      
        }
    }
    
    public void closePreparedStatement(PreparedStatement preparedStatement){
        try{
            if(preparedStatement != null && !preparedStatement.isClosed()){
                preparedStatement.close();
                preparedStatement = null;
            }
        } catch(SQLException e){
            System.out.println("Error closing preparedStatement! " + e);
        }
    }
    
    public void disconnect(){
        try{
            if(conexion != null && !conexion.isClosed()){
                conexion.close();
                conexion = null;
            }
        } catch(SQLException e){
            System.out.println("Error closing the connection! " + e);   
        } 
    }
    
    
    
    
}

