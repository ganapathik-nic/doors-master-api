package org.gepnic.doors.masterapi.config;

public class AuditContextHolder {
    private static final ThreadLocal<Integer> count = new ThreadLocal<>();

    // 🚀 Method name: setRecordCount
    public static void setRecordCount(int value) { 
        count.set(value); 
    }

    // 🚀 Method name: getRecordCount
    public static Integer getRecordCount() { 
        return count.get(); 
    }

    public static void clear() { 
        count.remove(); 
    }
}