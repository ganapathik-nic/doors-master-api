package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.model.Category;
import org.gepnic.doors.masterapi.repository.CategoryRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * MASTER: Category Controller
 * Manages the master data for query categories used by the Governance Library.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/master/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryRepository categoryRepository;
    private final SqlTemplateRepository sqlTemplateRepository;

    /**
     * Fetches all categories defined in the Category Master table.
     * Used by the "Propose New Query" dropdown and Category Management page.
     */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<Category>>> listCategories() {
        log.info("DOORS-MASTER: Fetching query categories from registry");
        List<Category> categories = categoryRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success(categories, "Categories retrieved successfully"));
    }

    /**
     * Adds a new category to the master table.
     */
    @PostMapping("/add")
    public ResponseEntity<ApiResponse<Category>> addCategory(@jakarta.validation.Valid @RequestBody org.gepnic.doors.masterapi.dto.CategoryRequest request) {
        Category category = new Category(null, request.code(), request.name(), request.parentId());
        log.info("DOORS-MASTER: Registering new category: {}", category.getCode());
        
        // Ensure the code is stored in uppercase for system-wide consistency
        if (category.getCode() != null) {
            category.setCode(category.getCode().toUpperCase());
        }
        
        Category saved = categoryRepository.save(category);
        return ResponseEntity.ok(ApiResponse.success(saved, "New category registered successfully"));
    }

    /**
     * ASPECT: Governance Integrity
     * Deletes a category only if it is not currently linked to any SQL Templates.
     */
    
     @DeleteMapping("/{id}")
public ResponseEntity<ApiResponse<?>> deleteCategory(@PathVariable Long id) {
    log.info("DOORS-MASTER: Attempting to delete category ID: {}", id);

    return categoryRepository.findById(id).<ResponseEntity<ApiResponse<?>>>map(category -> {
        // Check usage
        long usageCount = sqlTemplateRepository.countByCategory(category.getCode());
        
        if (usageCount > 0) {
            return ResponseEntity.<ApiResponse<?>>status(400)
                .body(ApiResponse.error("Deletion Blocked: Category in use by " + usageCount + " queries.", 400));
        }

        categoryRepository.delete(category);
        return ResponseEntity.<ApiResponse<?>>ok(ApiResponse.success(null, "Category deleted successfully"));
        
    }).orElseGet(() -> ResponseEntity.<ApiResponse<?>>status(404)
        .body(ApiResponse.error("Category not found", 404)));
}
}
