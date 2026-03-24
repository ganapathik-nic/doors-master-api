 package org.gepnic.doors.masterapi.dto; // Ensure this matches your package structure

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Data;
import java.util.List;
import java.util.ArrayList;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SelectionOption {
    private String id;
    private String name;
    private List<String> parameters;

    // Manual constructor to handle legacy calls that only provide ID and Name
    public SelectionOption(String id, String name) {
        this.id = id;
        this.name = name;
        this.parameters = new ArrayList<>();
    }
}