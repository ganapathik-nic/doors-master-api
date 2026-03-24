package org.gepnic.doors.masterapi.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.SystemMessage;
/**
 * AIra (Artificial Intelligence Reporting Agent)
 * This interface is managed by LangChain4j to handle Orchestration.
 */
 public interface AiraAgent {
    // 🚀 Remove the {{variable}} from here if you are passing everything in the prompt string
    @SystemMessage("You are AIra, a sovereign security auditor for the DOORS platform. " +
                   "Always provide SQL queries inside markdown blocks. " +
                   "Limit results to 15 unless specified.")
    String chat(String userMessage);
}