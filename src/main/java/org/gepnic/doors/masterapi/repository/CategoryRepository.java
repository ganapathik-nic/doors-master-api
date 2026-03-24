package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    // Standard JpaRepository provides findAll() and save()
}