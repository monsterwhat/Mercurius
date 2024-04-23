package Models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.io.Serializable;
import lombok.Data;

/**
 *
 * @author Al
 */

@Entity
@Data
@Table(name = "Users",
        uniqueConstraints = @UniqueConstraint(columnNames = {"username"}))
public class Users implements Serializable{
    
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column
    private String username;
    
    @Column
    private String password;
    
    @Column
    private String groupName;
    
    private Boolean status; //En caso de querer archivar o desabilitar

    public Users() {
    }

    public Users(Long id, String username, String password, String groupName, Boolean status) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.groupName = groupName;
        this.status = status;
    }

}
