package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

// Essential Java Utility Imports
import java.util.List;     
import java.util.Optional; 

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    // Custom query method to filter by status (PENDING, ACTIVE, etc.)
    List<User> findByStatus(String status);
    
    // Used for authentication and JWT generation
    Optional<User> findByUsername(String username);
}